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
 * Чтение чужого экрана дорогое: каждый запрос к элементу обслуживает главный поток
 * того приложения, а во время прокрутки он занят отрисовкой. Поэтому запросов как можно меньше:
 *
 * - **Строки списков.** При полном обходе запоминаем, какие строки (дочерние элементы)
 *   есть у каждого прокручиваемого списка. При прокрутке и при изменении списка читаем
 *   только строки, которых в прошлый раз не было. Строка, остававшаяся на экране, показывает
 *   то же самое — иначе пришло бы событие об изменении её контента.
 *   Переиспользованная строка (RecyclerView, LazyColumn) уходила с экрана, поэтому в прошлом
 *   наборе её нет, и её прочитаем.
 * - **Поддерево.** Небольшое изменение (таймер, счётчик) — обходим только изменившийся элемент.
 *   ScrollView, WebView и прочие «списки» с 1–2 детьми — обход поддерева,
 *   не чаще раза в [SUBTREE_MIN_INTERVAL_MS].
 * - **Проверка найденного.** Найденные элементы обновляем через [AccessibilityNodeInfo.refresh]
 *   (новые координаты + текст всё ещё совпадает) и сразу двигаем за ними блюр.
 * - **Полный обход окна** — новое окно, смена экрана, большие изменения
 *   (не чаще раза в [FULL_SCAN_MIN_INTERVAL_MS]) и один раз через [SCROLL_SETTLE_MS]
 *   после остановки прокрутки как подстраховка.
 * - Android 13+: элементы подгружаются пачками (prefetch); элементы за экраном пропускаются.
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

    // ===================== Состояние окон: только фоновый поток =====================

    private class Hit(
        val spoiler: Boolean,               // false — основная фраза, true — слово-спойлер
        val textNode: AccessibilityNodeInfo,
        var target: AccessibilityNodeInfo,  // карточка вокруг текста (или сам текст)
        val textBounds: Rect,
        val rect: Rect,
    )

    private class WindowState(val pkg: String) {
        val hits = ArrayList<Hit>()
        var lastFullAt = 0L
        /** Прокручиваемый список → его строки, которые уже прочитаны. */
        val lists = HashMap<AccessibilityNodeInfo, HashSet<AccessibilityNodeInfo>>()
        /** Поддерево → когда его обходили (для лимита частоты). */
        val subtreeAt = HashMap<AccessibilityNodeInfo, Long>()
        /** Поддеревья, обход которых отложен из-за лимита частоты. */
        val deferred = LinkedHashSet<AccessibilityNodeInfo>()
        /**
         * Список целиком сообщил об изменении контента. Система склеивает события
         * нескольких строк в одно событие про список, поэтому изменённую «на месте» строку
         * сравнение строк не заметит. Подстраховка: полный обход, но не чаще [SAFETY_FULL_INTERVAL_MS].
         */
        var safetyDue = false
    }

    private val windowCache = HashMap<Int, WindowState>()

    /** Параметры одного скана (чтобы не таскать их через все функции). */
    private class Ctx(
        val m: KeywordMatcher,
        val sm: KeywordMatcher?,
        val card: Boolean,
        val now: Long,
        val stats: ScanStats.Scan,
    )

    // ========== Работа, накопленная из событий: пишет главный поток, читает фоновый ==========

    @Volatile private var allDirty = true
    private val fullDirty: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val scrolledWindows: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val contentEvents = ConcurrentLinkedQueue<AccessibilityEvent>()
    private val scrollEvents = ConcurrentLinkedQueue<AccessibilityEvent>()

    /** Окна, где шла прокрутка: после остановки один раз обойдём их полностью. */
    private val settleWindows: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val settleRunnable = Runnable {
        fullDirty.addAll(settleWindows)
        settleWindows.clear()
        scheduleScan()
    }

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

        ScanStats.cacheInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "Android 13+, пачки до 50"
        } else {
            "Android < 13, без пачек"
        }

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

    // =========================== События (главный поток) ===========================

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
                scrolledWindows.add(windowId)
                // Копия события: источник достанем уже на фоновом потоке.
                if (scrollEvents.size < MAX_QUEUED_EVENTS) {
                    scrollEvents.add(AccessibilityEvent(event))
                } else {
                    fullDirty.add(windowId)
                }
                settleWindows.add(windowId)
                main.removeCallbacks(settleRunnable)
                main.postDelayed(settleRunnable, SCROLL_SETTLE_MS)
                onScrolled(event)
            }

            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (contentEvents.size < MAX_QUEUED_EVENTS) {
                    contentEvents.add(AccessibilityEvent(event))
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
        // Без паранойи и без известного сдвига контейнер не нужен:
        // не делаем лишний запрос к приложению на главном потоке.
        if (!paranoid && dx == 0 && dy == 0) return

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

    // =========================== Скан (фоновый поток) ===========================

    private fun scan() {
        // Сбрасываем флаги до работы: события во время скана запланируют следующий.
        scanScheduled.set(false)
        val startedAt = SystemClock.uptimeMillis()
        lastScanAt = startedAt

        val all = allDirty
        allDirty = false
        val full = HashSet(fullDirty).also { fullDirty.removeAll(it) }
        val scrolled = HashSet(scrolledWindows).also { scrolledWindows.removeAll(it) }
        val scrollEvs = drain(scrollEvents)
        val contentEvs = drain(contentEvents)

        if (!enabled) {
            windowCache.clear()
            main.post {
                overlay?.setVeil(null)
                render(emptyList())
            }
            return
        }

        val ctx = Ctx(
            m = matcher,
            sm = if (contextEnabled) spoilerMatcher else null,
            card = coverCard,
            now = startedAt,
            stats = ScanStats.Scan(),
        )

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

        val refreshedNow = HashSet<Int>()

        // 1. Прокрутка: сначала двигаем уже найденное — это дёшево — и сразу рисуем.
        if (!all) {
            var moved = false
            for (id in scrolled) {
                val st = windowCache[id] ?: continue
                val w = byId[id] ?: continue
                if (st.hits.isEmpty()) continue
                refreshHits(st, bounds(w), ctx)
                refreshedNow.add(id)
                moved = true
            }
            if (moved) {
                val quick = compose(startedAt)
                main.post { render(quick) }
            }
        }

        // 2. Полные обходы: новые окна, смена экрана, большие изменения, подстраховка.
        val fullDone = HashSet<Int>()
        for ((id, w) in byId) {
            val st = windowCache[id]
            val safety = st != null && st.safetyDue && startedAt - st.lastFullAt >= SAFETY_FULL_INTERVAL_MS
            if (!(all || st == null || id in full || safety)) continue
            if (!all && st != null && startedAt - st.lastFullAt < FULL_SCAN_MIN_INTERVAL_MS) {
                fullDirty.add(id) // слишком часто — отложим, но не потеряем
                continue
            }
            val res = fullScan(w, ctx) ?: continue
            windowCache[id] = res
            fullDone.add(id)
        }

        // 3. События прокрутки и изменения контента: читаем только изменившееся.
        val touched = HashSet<Int>()
        val handled = HashSet<AccessibilityNodeInfo>()
        val events = scrollEvs.map { it to true } + contentEvs.map { it to false }
        for ((ev, isScroll) in events) {
            val id = ev.windowId
            if (id in fullDone) continue
            val st = windowCache[id]
            val w = byId[id] ?: continue
            if (st == null) { fullDirty.add(id); continue }
            if (st.pkg == packageName) continue
            val src = ctx.stats.call { try { ev.source } catch (e: Exception) { null } } ?: continue
            if (!handled.add(src)) continue // этот элемент уже обработан в этом скане
            try {
                if (!handleChange(st, src, bounds(w), isScroll, ctx)) fullDirty.add(id)
                touched.add(id)
            } catch (e: Exception) {
                fullDirty.add(id)
            }
        }

        // 4. Отложенные обходы поддеревьев, у которых истёк лимит частоты.
        for ((id, st) in windowCache) {
            if (st.deferred.isEmpty() || id in fullDone) continue
            val w = byId[id] ?: continue
            val wb = bounds(w)
            val itr = st.deferred.iterator()
            while (itr.hasNext()) {
                val node = itr.next()
                val last = st.subtreeAt[node] ?: 0L
                if (startedAt - last < SUBTREE_MIN_INTERVAL_MS) continue
                itr.remove()
                try {
                    val fresh = ctx.stats.call { node.refresh() }
                    if (fresh) rescanSubtree(st, node, wb, ctx)
                } catch (e: Exception) {
                    fullDirty.add(id)
                }
                touched.add(id)
            }
        }

        // 5. После изменений списков перепроверяем найденное: строки могли удалить
        //    или переиспользовать под другой контент.
        for (id in touched) {
            if (id in refreshedNow || id in fullDone) continue
            val st = windowCache[id] ?: continue
            val w = byId[id] ?: continue
            if (st.hits.isNotEmpty()) refreshHits(st, bounds(w), ctx)
        }

        val result = compose(startedAt)
        ScanStats.record(SystemClock.uptimeMillis() - startedAt, ctx.stats, result.size)

        // Отложенная работа — запланировать, чтобы не зависла.
        val hasDeferred = windowCache.values.any { it.deferred.isNotEmpty() }
        val safetyAt = windowCache.values.filter { it.safetyDue }
            .minOfOrNull { it.lastFullAt + SAFETY_FULL_INTERVAL_MS }
        if (fullDirty.isNotEmpty() || hasDeferred) {
            scheduleScan(minOf(FULL_SCAN_MIN_INTERVAL_MS, SUBTREE_MIN_INTERVAL_MS))
        } else if (safetyAt != null) {
            scheduleScan(max(0L, safetyAt - SystemClock.uptimeMillis()))
        }

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

    /**
     * Изменился элемент [src] (прокрутили его или поменялся его контент).
     * Возвращает false, если дешевле обойти окно целиком.
     */
    private fun handleChange(
        st: WindowState,
        src: AccessibilityNodeInfo,
        wb: Rect,
        isScroll: Boolean,
        ctx: Ctx,
    ): Boolean {
        val sb = Rect()
        src.getBoundsInScreen(sb)
        if (!sb.isEmpty && !Rect.intersects(sb, wb)) return true // изменение за экраном

        // Известный список с настоящими строками — читаем только новые строки.
        val prev = st.lists[src]
        if (prev != null && src.childCount > 2) {
            diffList(st, src, prev, wb, ctx)
            if (!isScroll) st.safetyDue = true
            return true
        }

        // Прокручиваемое (ScrollView, WebView, незнакомый список) — поддерево с лимитом частоты.
        if (src.isScrollable) {
            val last = st.subtreeAt[src] ?: 0L
            if (ctx.now - last < SUBTREE_MIN_INTERVAL_MS) {
                st.deferred.add(src)
            } else {
                rescanSubtree(st, src, wb, ctx)
            }
            return true
        }

        // Остальное: маленькое изменение — поддерево, большое — весь экран.
        val windowArea = wb.width().toLong() * wb.height()
        val area = sb.width().toLong() * sb.height()
        if (sb.isEmpty || area > windowArea * PARTIAL_MAX_AREA_FRACTION) return false
        rescanSubtree(st, src, wb, ctx)
        return true
    }

    /** Сравнивает строки списка с прошлым набором и обходит только новые. */
    private fun diffList(
        st: WindowState,
        list: AccessibilityNodeInfo,
        prev: HashSet<AccessibilityNodeInfo>,
        wb: Rect,
        ctx: Ctx,
    ) {
        val now = HashSet<AccessibilityNodeInfo>()
        for (i in 0 until list.childCount) {
            val row = ctx.stats.call { try { siblingOf(list, i) } catch (e: Exception) { null } } ?: continue
            now.add(row)
            if (row !in prev) {
                ctx.stats.nodes += collectMatches(row, wb, st, ctx)
            }
        }
        st.lists[list] = now
        ctx.stats.listDiff++
    }

    /** Обходит поддерево [node] заново и заменяет найденное внутри его границ. */
    private fun rescanSubtree(st: WindowState, node: AccessibilityNodeInfo, wb: Rect, ctx: Ctx) {
        val sb = Rect()
        node.getBoundsInScreen(sb)
        st.hits.removeAll { sb.contains(it.textBounds) }
        st.subtreeAt[node] = ctx.now
        ctx.stats.nodes += collectMatches(node, wb, st, ctx)
        ctx.stats.subtree++
    }

    private fun fullScan(w: AccessibilityWindowInfo, ctx: Ctx): WindowState? {
        val root = ctx.stats.call { try { rootOf(w) } catch (e: Exception) { null } } ?: return null
        val pkg = root.packageName?.toString() ?: ""
        val st = WindowState(pkg)
        st.lastFullAt = ctx.now
        if (pkg == packageName) return st
        try {
            ctx.stats.nodes += collectMatches(root, bounds(w), st, ctx)
        } catch (e: Exception) {
            // Дерево поменялось прямо во время обхода — пересканируем в следующий раз.
            Log.w(TAG, "Ошибка обхода окна", e)
            fullDirty.add(w.id)
        }
        ctx.stats.full++
        return st
    }

    /** Собирает итоговые области из кеша окон с учётом контекста GTA. */
    private fun compose(now: Long): List<Rect> {
        val found = ArrayList<Rect>()
        for (st in windowCache.values) {
            if (st.pkg == packageName) continue
            var hasGta = false
            for (h in st.hits) if (!h.spoiler) { hasGta = true; break }
            if (hasGta && st.pkg.isNotEmpty()) gtaSeenAt[st.pkg] = now
            val seen = gtaSeenAt[st.pkg]
            val contextActive = hasGta || (seen != null && now - seen <= CONTEXT_TTL_MS)
            for (h in st.hits) {
                if (!h.spoiler || contextActive) found.add(h.rect)
            }
        }
        return mergeOverlapping(found)
    }

    /**
     * Перепроверяет найденные элементы: новые координаты и то, что элемент всё ещё
     * показывает подходящий текст (списки переиспользуют строки под другой контент).
     */
    private fun refreshHits(st: WindowState, wb: Rect, ctx: Ctx) {
        val itr = st.hits.iterator()
        while (itr.hasNext()) {
            val h = itr.next()
            ctx.stats.refreshed++
            val alive = ctx.stats.call { try { h.textNode.refresh() } catch (e: Exception) { false } }
            if (!alive || !h.textNode.isVisibleToUser) { itr.remove(); continue }
            val mt = if (h.spoiler) ctx.sm else ctx.m
            val text = h.textNode.text
            val desc = h.textNode.contentDescription
            if (mt == null || !(mt.matches(text) || mt.matches(desc))) { itr.remove(); continue }
            if (h.target !== h.textNode) {
                val ok = ctx.stats.call { try { h.target.refresh() } catch (e: Exception) { false } }
                if (!ok) h.target = h.textNode
            }
            h.textNode.getBoundsInScreen(h.textBounds)
            val r = Rect()
            h.target.getBoundsInScreen(r)
            if (!r.intersect(wb) || r.isEmpty) { itr.remove(); continue }
            h.rect.set(r)
        }
    }

    /**
     * Обходит поддерево: ищет совпадения и попутно запоминает строки прокручиваемых списков.
     * Возвращает число просмотренных элементов.
     */
    private fun collectMatches(root: AccessibilityNodeInfo, wb: Rect, st: WindowState, ctx: Ctx): Int {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.push(root)
        var visited = 0
        val b = Rect()
        val sm = ctx.sm

        while (stack.isNotEmpty() && visited < MAX_NODES_PER_SCAN) {
            val node = stack.pop()
            visited++

            // Элемент целиком за пределами экрана (длинная страница в Chrome, ScrollView) —
            // пропускаем его вместе со всеми детьми. Пустые обёртки (0×0) не трогаем:
            // у них бывают видимые дети.
            node.getBoundsInScreen(b)
            if (!b.isEmpty && !Rect.intersects(b, wb)) continue

            // Поле ввода не закрываем, чтобы было видно, что печатаешь.
            if (!node.isEditable) {
                val text = node.text
                val desc = node.contentDescription
                val isGta = ctx.m.matches(text) || ctx.m.matches(desc)
                val isSpoiler = !isGta && sm != null && (sm.matches(text) || sm.matches(desc))
                if (isGta || isSpoiler) {
                    if (node.isVisibleToUser) addHit(st, node, Rect(b), isSpoiler, wb, ctx)
                    if (isGta) continue // внутрь совпавшего элемента не спускаемся
                }
            }

            val count = node.childCount
            val rows = if (count > 2 && node.isScrollable) HashSet<AccessibilityNodeInfo>() else null
            for (i in count - 1 downTo 0) {
                val child = ctx.stats.call { try { childOf(node, i) } catch (e: Exception) { null } }
                if (child != null) {
                    stack.push(child)
                    rows?.add(child)
                }
            }
            if (rows != null) st.lists[node] = rows
        }
        return visited
    }

    private fun addHit(
        st: WindowState,
        node: AccessibilityNodeInfo,
        textBounds: Rect,
        spoiler: Boolean,
        wb: Rect,
        ctx: Ctx,
    ) {
        val target = if (ctx.card) expandToCard(node, wb, ctx) else node
        val r = Rect()
        target.getBoundsInScreen(r)
        if (!r.intersect(wb) || r.isEmpty) return
        // Тот же элемент мог быть найден раньше (переиспользованная строка) — заменяем.
        st.hits.removeAll { it.textNode == node }
        st.hits.add(Hit(spoiler, node, target, textBounds, r))
    }

    /**
     * Поднимаемся от совпавшего текста к родителям, пока элемент похож на «карточку»
     * (пост, превью видео, сообщение), а не на весь список. Так закрывается и превью-картинка
     * рядом с заголовком, которую само дерево элементов прочитать не может.
     */
    private fun expandToCard(node: AccessibilityNodeInfo, wb: Rect, ctx: Ctx): AccessibilityNodeInfo {
        val maxH = wb.height() * CARD_MAX_HEIGHT_FRACTION
        val maxArea = wb.width().toLong() * wb.height() * CARD_MAX_AREA_FRACTION
        var best = node
        var cur: AccessibilityNodeInfo? = ctx.stats.call { parentOf(node) }
        var levels = 0
        val b = Rect()
        while (cur != null && levels < CARD_MAX_LEVELS) {
            cur.getBoundsInScreen(b)
            val area = b.width().toLong() * b.height()
            if (b.height() > maxH || area > maxArea) break
            best = cur
            val c: AccessibilityNodeInfo = cur
            cur = ctx.stats.call { parentOf(c) }
            levels++
        }
        return best
    }

    // Android 13+: каждый запрос к приложению сразу подтягивает пачку элементов
    // (до 50) в локальный кеш, и следующие обращения отвечают без межпроцессного вызова.
    private fun rootOf(w: AccessibilityWindowInfo): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) w.getRoot(PREFETCH) else w.root

    private fun childOf(n: AccessibilityNodeInfo, i: Int): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) n.getChild(i, PREFETCH) else n.getChild(i)

    /** Строки списка: подтягиваем соседей одним запросом, а не всё поддерево каждой. */
    private fun siblingOf(n: AccessibilityNodeInfo, i: Int): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            n.getChild(i, AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS)
        else n.getChild(i)

    private fun parentOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            n.getParent(AccessibilityNodeInfo.FLAG_PREFETCH_ANCESTORS)
        else n.parent

    private fun bounds(w: AccessibilityWindowInfo): Rect = Rect().also { w.getBoundsInScreen(it) }

    private fun <T> drain(q: ConcurrentLinkedQueue<T>): List<T> {
        val out = ArrayList<T>()
        while (true) out.add(q.poll() ?: break)
        return out
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
        private const val SUBTREE_MIN_INTERVAL_MS = 150L
        private const val SCROLL_SETTLE_MS = 400L
        private const val SAFETY_FULL_INTERVAL_MS = 1000L
        private const val MAX_QUEUED_EVENTS = 40
        private const val PARTIAL_MAX_AREA_FRACTION = 0.6f
        private const val MAX_NODES_PER_SCAN = 5000
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
