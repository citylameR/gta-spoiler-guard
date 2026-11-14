package com.chickenbuter.spoilerguard

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.max

/**
 * Сердце приложения.
 *
 * Ищем на экране:
 * - основные фразы (GTA 6, Vice City…) — закрываются всегда;
 * - слова-спойлеры (концовка, умер…) — только если в этом окне сейчас есть GTA
 *   или в этом приложении GTA встречалась за последние [CONTEXT_TTL_MS] мс.
 *
 * Чтение чужого экрана дорогое: каждый запрос к элементу обслуживается главным потоком
 * того приложения. Поэтому работы как можно меньше:
 * - **частичный скан**: на «контент изменился» обходим только изменившееся поддерево
 *   ([AccessibilityEvent.getSource]) и заменяем совпадения внутри его границ;
 * - **слежение при прокрутке**: найденные элементы обновляем через [AccessibilityNodeInfo.refresh]
 *   (один запрос на элемент) и сразу двигаем за ними блюр;
 * - **полный скан окна** — при смене экрана, при прокрутке (не чаще раза в
 *   [FULL_SCAN_MIN_INTERVAL_MS]) и когда частичных изменений слишком много;
 * - на Android 13+ элементы подгружаются пачками (prefetch), элементы за экраном пропускаются.
 */
class SpoilerGuardService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var prefs: SharedPreferences

    private var windowManager: WindowManager? = null
    private var overlay: OverlayView? = null
    private var blurPanes: BlurPanes? = null
    private var blurAvailable = false
    private val blurListener = Consumer<Boolean> { enabled ->
        blurAvailable = enabled
        render(lastRects)
    }

    @Volatile private var enabled = true
    @Volatile private var coverCard = true
    @Volatile private var contextEnabled = true
    @Volatile private var paranoid = true
    @Volatile private var blurWanted = true
    @Volatile private var matcher = KeywordMatcher(Prefs.DEFAULT_KEYWORDS)
    @Volatile private var spoilerMatcher = KeywordMatcher(Prefs.DEFAULT_SPOILER_WORDS)

    /** Пакет приложения → когда в нём последний раз видели упоминание GTA. */
    private val gtaSeenAt = ConcurrentHashMap<String, Long>()

    // ---- Состояние окон: только фоновый поток ----
    private class Hit(
        val spoiler: Boolean,          // false — основная фраза, true — слово-спойлер
        val textNode: AccessibilityNodeInfo,
        var target: AccessibilityNodeInfo, // карточка вокруг текста (или сам текст)
        val textBounds: Rect,
        val rect: Rect,
    )

    private class WindowState(val pkg: String) {
        val hits = ArrayList<Hit>()
        var lastFullAt = 0L
    }

    private val windowCache = HashMap<Int, WindowState>()

    // ---- Работа, накопленная из событий: пишет главный поток, читает фоновый ----
    @Volatile private var allDirty = true
    private val fullDirty: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val scrolled: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val partialEvents = ConcurrentLinkedQueue<AccessibilityEvent>()

    private val scanScheduled = AtomicBoolean(false)
    @Volatile private var lastScanAt = 0L
    @Volatile private var lastBigScrollAt = 0L

    private var lastRects: List<Rect> = emptyList()
    private var screenHeight = 0

    private val scanRunnable = Runnable { scan() }
    private val scrollQuietRunnable = Runnable { scheduleScan() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        workerThread = HandlerThread("spoiler-scan").apply { start() }
        worker = Handler(workerThread.looper)

        prefs = Prefs.get(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        reloadPrefs()

        addOverlay()
        blurPanes = BlurPanes(this)
        windowManager?.let { wm ->
            blurAvailable = wm.isCrossWindowBlurEnabled
            wm.addCrossWindowBlurEnabledListener(mainExecutor, blurListener)
            screenHeight = wm.currentWindowMetrics.bounds.height()
        }
        scheduleScan()
    }

    private fun reloadPrefs() {
        enabled = Prefs.isEnabled(prefs)
        coverCard = Prefs.coverCard(prefs)
        contextEnabled = Prefs.contextEnabled(prefs)
        paranoid = Prefs.paranoid(prefs)
        blurWanted = Prefs.blur(prefs)
        matcher = KeywordMatcher(Prefs.keywords(prefs))
        spoilerMatcher = KeywordMatcher(Prefs.spoilerWords(prefs))
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        reloadPrefs()
        allDirty = true
        main.post {
            if (!enabled || !paranoid) overlay?.setVeil(null)
            render(if (enabled) lastRects else emptyList())
        }
        scheduleScan()
    }

    private fun addOverlay() {
        val wm = getSystemService(WindowManager::class.java) ?: return
        windowManager = wm
        val view = OverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            // Оверлей спецвозможностей считается доверенным:
            // Android 12+ пропускает касания сквозь него в приложение под ним.
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "GtaSpoilerGuardOverlay"
        }
        try {
            wm.addView(view, lp)
            overlay = view
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось добавить оверлей", e)
        }
    }

    /** Только главный поток. */
    private fun render(rects: List<Rect>) {
        lastRects = rects
        val useBlur = blurWanted && blurAvailable && blurPanes != null
        if (useBlur) {
            overlay?.setRects(emptyList())
            blurPanes?.setRects(rects)
        } else {
            blurPanes?.clear()
            overlay?.setRects(rects)
        }
    }

    // ======================= События (главный поток) =======================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !enabled) return
        val type = event.eventType
        if (event.packageName?.toString() == packageName) {
            // Своё окно не сканируем, но при открытии настроек пересчитываем остальные.
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        }
        val windowId = event.windowId

        when {
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                windowId < 0 -> allDirty = true

            type == AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                scrolled.add(windowId)
                fullDirty.add(windowId)
                onScrolled(event)
            }

            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Частичный скан: копия события, источник достанем уже на фоновом потоке.
                if (partialEvents.size < MAX_PARTIAL_EVENTS) {
                    partialEvents.add(AccessibilityEvent(event))
                } else {
                    fullDirty.add(windowId)
                }
            }

            else -> fullDirty.add(windowId)
        }
        scheduleScan()
    }

    private fun onScrolled(event: AccessibilityEvent) {
        val dx = event.scrollDeltaX.let { if (abs(it) > 1) it else 0 }
        val dy = event.scrollDeltaY.let { if (abs(it) > 1) it else 0 }
        val needContainer = paranoid || dx != 0 || dy != 0
        if (!needContainer) return // не делаем лишний запрос к приложению на главном потоке

        val src = event.source ?: return
        val container = Rect()
        src.getBoundsInScreen(container)

        // Если система сообщила, на сколько прокрутили, сразу сдвигаем закрытые области.
        if (dx != 0 || dy != 0) {
            overlay?.shift(container, dx, dy)
            blurPanes?.shift(container, dx, dy)
        }

        // Параноидальный режим: большую ленту закрываем целиком, пока она едет.
        // Маленькие карусели (баннеры, полки) не трогаем, иначе они были бы закрыты всегда.
        if (paranoid && screenHeight > 0 &&
            container.height() >= screenHeight * PARANOID_MIN_HEIGHT_FRACTION
        ) {
            lastBigScrollAt = SystemClock.uptimeMillis()
            overlay?.setVeil(container)
            main.removeCallbacks(scrollQuietRunnable)
            main.postDelayed(scrollQuietRunnable, SCROLL_QUIET_MS)
        }
    }

    private fun scheduleScan(minDelay: Long = 0L) {
        if (!::worker.isInitialized) return
        if (scanScheduled.compareAndSet(false, true)) {
            val sinceLast = SystemClock.uptimeMillis() - lastScanAt
            val delay = max(minDelay, MIN_SCAN_INTERVAL_MS - sinceLast)
            worker.postDelayed(scanRunnable, delay)
        }
    }

    // ======================= Скан (фоновый поток) =======================

    private fun scan() {
        // Сбрасываем флаги до работы: события во время скана запланируют следующий.
        scanScheduled.set(false)
        val startedAt = SystemClock.uptimeMillis()
        lastScanAt = startedAt

        val all = allDirty
        allDirty = false
        val full = HashSet(fullDirty).also { fullDirty.removeAll(it) }
        val scroll = HashSet(scrolled).also { scrolled.removeAll(it) }
        val partials = ArrayList<AccessibilityEvent>()
        while (true) partials.add(partialEvents.poll() ?: break)

        if (!enabled) {
            windowCache.clear()
            main.post {
                overlay?.setVeil(null)
                render(emptyList())
            }
            return
        }

        val m = matcher
        val sm = if (contextEnabled) spoilerMatcher else null
        val card = coverCard

        val ws: List<AccessibilityWindowInfo> = try {
            windows ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val byId = HashMap<Int, AccessibilityWindowInfo>()
        for (w in ws) {
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            byId[w.id] = w
        }
        windowCache.keys.retainAll(byId.keys)

        var fullCount = 0
        var partialCount = 0
        var refreshCount = 0
        var nodes = 0

        // 1. Прокрутка: быстро обновляем уже найденные элементы и сразу рисуем.
        if (!all) {
            var moved = false
            for (id in scroll) {
                val st = windowCache[id] ?: continue
                val w = byId[id] ?: continue
                if (st.hits.isEmpty()) continue
                refreshCount += refreshHits(st, w, m, sm)
                moved = true
            }
            if (moved) {
                val quick = compose(startedAt)
                main.post { render(quick) }
            }
        }

        // 2. Полные сканы: новые окна, смена экрана, прокрутка (с ограничением частоты).
        val fullDone = HashSet<Int>()
        for ((id, w) in byId) {
            val st = windowCache[id]
            val wanted = all || st == null || id in full
            if (!wanted) continue
            if (!all && st != null && startedAt - st.lastFullAt < FULL_SCAN_MIN_INTERVAL_MS) {
                // Слишком часто: отложим, но не потеряем.
                fullDirty.add(id)
                continue
            }
            val res = fullScan(w, m, sm, card) ?: continue
            res.lastFullAt = startedAt
            windowCache[id] = res
            fullDone.add(id)
            fullCount++
            nodes += lastVisited
        }

        // 3. Частичные сканы: только изменившиеся поддеревья.
        for (ev in partials) {
            val id = ev.windowId
            if (id in fullDone) continue
            val st = windowCache[id]
            val w = byId[id] ?: continue
            if (st == null) {
                fullDirty.add(id)
                continue
            }
            if (st.pkg == packageName) continue
            val src = try { ev.source } catch (e: Exception) { null } ?: continue
            val wb = Rect()
            w.getBoundsInScreen(wb)
            val sb = Rect()
            src.getBoundsInScreen(sb)
            if (!sb.isEmpty && !Rect.intersects(sb, wb)) continue // изменение за экраном
            if (sb.isEmpty || sb.width().toLong() * sb.height() > wb.width().toLong() * wb.height() * PARTIAL_MAX_AREA_FRACTION) {
                // Поменялась большая часть окна — дешевле обойти всё окно.
                fullDirty.add(id)
                continue
            }
            st.hits.removeAll { sb.contains(it.textBounds) }
            try {
                nodes += collectMatches(src, m, sm, card, wb, st.hits)
            } catch (e: Exception) {
                fullDirty.add(id)
            }
            partialCount++
        }

        val result = compose(startedAt)
        ScanStats.record(
            durationMs = SystemClock.uptimeMillis() - startedAt,
            full = fullCount,
            partial = partialCount,
            refreshed = refreshCount,
            nodes = nodes,
            matches = result.size,
        )

        // Отложенные полные сканы — запланировать, чтобы не зависли.
        if (fullDirty.isNotEmpty()) scheduleScan(FULL_SCAN_MIN_INTERVAL_MS)

        main.post {
            render(result)
            // Ленту открываем, только если этот скан начался уже после остановки прокрутки.
            // Иначе планируем ещё одну проверку, чтобы завеса не осталась висеть.
            val ov = overlay
            if (ov != null && ov.hasVeil()) {
                if (startedAt - lastBigScrollAt >= SCROLL_QUIET_MS) {
                    ov.setVeil(null)
                } else {
                    main.removeCallbacks(scrollQuietRunnable)
                    main.postDelayed(scrollQuietRunnable, SCROLL_QUIET_MS)
                }
            }
        }
    }

    /** Собирает итоговые области из кеша окон с учётом контекста GTA. */
    private fun compose(now: Long): List<Rect> {
        val found = ArrayList<Rect>()
        for (st in windowCache.values) {
            if (st.pkg == packageName) continue
            var hasGta = false
            var hasSpoiler = false
            for (h in st.hits) if (h.spoiler) hasSpoiler = true else hasGta = true
            if (hasGta && st.pkg.isNotEmpty()) gtaSeenAt[st.pkg] = now
            val seen = gtaSeenAt[st.pkg]
            val contextActive = hasGta || (seen != null && now - seen <= CONTEXT_TTL_MS)
            for (h in st.hits) {
                if (!h.spoiler || (hasSpoiler && contextActive)) found.add(h.rect)
            }
        }
        return mergeOverlapping(found)
    }

    /**
     * Обновляет найденные элементы после прокрутки: новые координаты и проверка,
     * что элемент всё ещё показывает тот же текст (списки переиспользуют view).
     * Возвращает число обновлённых элементов.
     */
    private fun refreshHits(
        st: WindowState,
        w: AccessibilityWindowInfo,
        m: KeywordMatcher,
        sm: KeywordMatcher?,
    ): Int {
        val wb = Rect()
        w.getBoundsInScreen(wb)
        var n = 0
        val it = st.hits.iterator()
        while (it.hasNext()) {
            val h = it.next()
            n++
            val alive = try { h.textNode.refresh() } catch (e: Exception) { false }
            if (!alive || !h.textNode.isVisibleToUser) { it.remove(); continue }
            val mt = if (h.spoiler) sm else m
            val text = h.textNode.text
            val desc = h.textNode.contentDescription
            if (mt == null || !(mt.matches(text) || mt.matches(desc))) { it.remove(); continue }
            if (h.target !== h.textNode) {
                val ok = try { h.target.refresh() } catch (e: Exception) { false }
                if (!ok) h.target = h.textNode
            }
            h.textNode.getBoundsInScreen(h.textBounds)
            val r = Rect()
            h.target.getBoundsInScreen(r)
            if (!r.intersect(wb) || r.isEmpty) { it.remove(); continue }
            h.rect.set(r)
        }
        return n
    }

    private var lastVisited = 0

    private fun fullScan(
        w: AccessibilityWindowInfo,
        m: KeywordMatcher,
        sm: KeywordMatcher?,
        card: Boolean,
    ): WindowState? {
        lastVisited = 0
        val root = try { rootOf(w) } catch (e: Exception) { null } ?: return null
        val pkg = root.packageName?.toString() ?: ""
        val st = WindowState(pkg)
        if (pkg == packageName) return st

        val wb = Rect()
        w.getBoundsInScreen(wb)
        lastVisited = try {
            collectMatches(root, m, sm, card, wb, st.hits)
        } catch (e: Exception) {
            // Дерево поменялось прямо во время обхода — пересканируем в следующий раз.
            Log.w(TAG, "Ошибка обхода окна", e)
            fullDirty.add(w.id)
            0
        }
        return st
    }

    /** Возвращает число просмотренных элементов. */
    private fun collectMatches(
        root: AccessibilityNodeInfo,
        m: KeywordMatcher,
        sm: KeywordMatcher?,
        card: Boolean,
        windowBounds: Rect,
        out: MutableList<Hit>,
    ): Int {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.push(root)
        var visited = 0
        val b = Rect()

        while (stack.isNotEmpty() && visited < MAX_NODES_PER_WINDOW) {
            val node = stack.pop()
            visited++

            // Элемент целиком за пределами экрана (длинная страница в Chrome, ScrollView) —
            // пропускаем его вместе со всеми детьми. Пустые обёртки (0×0) не трогаем:
            // у них бывают видимые дети.
            node.getBoundsInScreen(b)
            if (!b.isEmpty && !Rect.intersects(b, windowBounds)) continue

            // Поле ввода не закрываем, чтобы было видно, что печатаешь.
            if (!node.isEditable) {
                val text = node.text
                val desc = node.contentDescription
                val isGta = m.matches(text) || m.matches(desc)
                val isSpoiler = !isGta && sm != null && (sm.matches(text) || sm.matches(desc))
                if (isGta || isSpoiler) {
                    if (node.isVisibleToUser) {
                        val target = if (card) expandToCard(node, windowBounds) else node
                        val r = Rect()
                        target.getBoundsInScreen(r)
                        if (r.intersect(windowBounds) && !r.isEmpty) {
                            out.add(Hit(isSpoiler, node, target, Rect(b), r))
                        }
                    }
                    if (isGta) continue // внутрь совпавшего элемента не спускаемся
                }
            }

            for (i in node.childCount - 1 downTo 0) {
                val child = try { childOf(node, i) } catch (e: Exception) { null }
                if (child != null) stack.push(child)
            }
        }
        return visited
    }

    // Android 13+: каждый запрос к приложению сразу подтягивает пачку соседних элементов
    // (до 50) в локальный кеш, и следующие getChild() отвечают без межпроцессного вызова.
    private fun rootOf(w: AccessibilityWindowInfo): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) w.getRoot(PREFETCH) else w.root

    private fun childOf(n: AccessibilityNodeInfo, i: Int): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) n.getChild(i, PREFETCH) else n.getChild(i)

    private fun parentOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            n.getParent(AccessibilityNodeInfo.FLAG_PREFETCH_ANCESTORS)
        else n.parent

    /**
     * Поднимаемся от совпавшего текста к родителям, пока элемент похож на «карточку»
     * (пост, превью видео, сообщение), а не на весь список. Так закрывается и превью-картинка
     * рядом с заголовком, которую само дерево элементов прочитать не может.
     */
    private fun expandToCard(node: AccessibilityNodeInfo, windowBounds: Rect): AccessibilityNodeInfo {
        val maxH = windowBounds.height() * CARD_MAX_HEIGHT_FRACTION
        val maxArea = windowBounds.width().toLong() * windowBounds.height() * CARD_MAX_AREA_FRACTION
        var best = node
        var cur: AccessibilityNodeInfo? = parentOf(node)
        var levels = 0
        val b = Rect()
        while (cur != null && levels < CARD_MAX_LEVELS) {
            cur.getBoundsInScreen(b)
            val area = b.width().toLong() * b.height()
            if (b.height() > maxH || area > maxArea) break
            best = cur
            cur = parentOf(cur)
            levels++
        }
        return best
    }

    private fun mergeOverlapping(rects: List<Rect>): List<Rect> {
        if (rects.isEmpty()) return rects
        val out = ArrayList<Rect>()
        for (r in rects.sortedBy { it.top }) {
            val hit = out.firstOrNull { Rect.intersects(it, r) }
            if (hit != null) hit.union(r) else out.add(Rect(r))
        }
        return out
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
        } catch (_: Exception) {}
        main.removeCallbacksAndMessages(null)
        try { windowManager?.removeCrossWindowBlurEnabledListener(blurListener) } catch (_: Exception) {}
        blurPanes?.destroy()
        blurPanes = null
        overlay?.let { v -> try { windowManager?.removeView(v) } catch (_: Exception) {} }
        overlay = null
        if (::workerThread.isInitialized) workerThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SpoilerGuard"
        private const val MIN_SCAN_INTERVAL_MS = 40L
        private const val FULL_SCAN_MIN_INTERVAL_MS = 150L
        private const val MAX_PARTIAL_EVENTS = 40
        private const val PARTIAL_MAX_AREA_FRACTION = 0.6f
        private const val MAX_NODES_PER_WINDOW = 5000
        private const val CARD_MAX_LEVELS = 6
        private const val CARD_MAX_HEIGHT_FRACTION = 0.45f
        private const val CARD_MAX_AREA_FRACTION = 0.40f
        private const val CONTEXT_TTL_MS = 3 * 60_000L
        private const val SCROLL_QUIET_MS = 120L
        private const val PARANOID_MIN_HEIGHT_FRACTION = 0.4f
        private const val PREFETCH =
            AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or
                AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE
    }
}
