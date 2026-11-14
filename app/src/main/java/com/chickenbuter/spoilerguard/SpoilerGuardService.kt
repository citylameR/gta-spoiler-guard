package com.chickenbuter.spoilerguard

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.max

/**
 * Сердце приложения.
 *
 * 1. Система присылает события «контент изменился / прокрутили / сменилось окно».
 * 2. Не чаще раза в [MIN_SCAN_INTERVAL_MS] мс на фоновом потоке обходим дерево
 *    элементов всех окон и ищем:
 *    - основные фразы (GTA 6, Vice City…) — закрываются всегда;
 *    - слова-спойлеры (концовка, умер…) — только если в этом окне сейчас есть GTA
 *      или в этом приложении GTA встречалась за последние [CONTEXT_TTL_MS] мс.
 * 3. Найденные области закрываются блюром ([BlurPanes]) или плашками ([OverlayView]).
 * 4. Параноидальный режим: пока большая лента прокручивается, она закрыта целиком,
 *    и открывается только после скана, начатого уже после остановки.
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !enabled) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            onScrolled(event)
        }
        scheduleScan()
    }

    private fun onScrolled(event: AccessibilityEvent) {
        val src = event.source ?: return
        val container = Rect()
        src.getBoundsInScreen(container)
        if (src.packageName?.toString() == packageName) return

        // Быстрый путь: если система сообщила, на сколько прокрутили, сдвигаем закрытые области.
        val dx = event.scrollDeltaX.let { if (abs(it) > 1) it else 0 }
        val dy = event.scrollDeltaY.let { if (abs(it) > 1) it else 0 }
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

    private fun scheduleScan() {
        if (!::worker.isInitialized) return
        if (scanScheduled.compareAndSet(false, true)) {
            val sinceLast = SystemClock.uptimeMillis() - lastScanAt
            val delay = max(0L, MIN_SCAN_INTERVAL_MS - sinceLast)
            worker.postDelayed(scanRunnable, delay)
        }
    }

    /** Выполняется на фоновом потоке. */
    private fun scan() {
        // Сбрасываем флаг до обхода: события, пришедшие во время скана, запланируют ещё один.
        scanScheduled.set(false)
        val startedAt = SystemClock.uptimeMillis()
        lastScanAt = startedAt

        if (!enabled) {
            main.post {
                overlay?.setVeil(null)
                render(emptyList())
            }
            return
        }

        val m = matcher
        val sm = spoilerMatcher
        val useContext = contextEnabled
        val card = coverCard
        val found = ArrayList<Rect>()

        val ws: List<AccessibilityWindowInfo> = try {
            windows ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        for (w in ws) {
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = try { w.root } catch (e: Exception) { null } ?: continue
            val pkg = root.packageName?.toString() ?: ""
            // Своё окно не трогаем, иначе закроем список слов в настройках.
            if (pkg == packageName) continue

            val windowBounds = Rect()
            w.getBoundsInScreen(windowBounds)
            val gtaHits = ArrayList<Rect>()
            val spoilerHits = ArrayList<Rect>()
            try {
                collectMatches(root, m, if (useContext) sm else null, card, windowBounds, gtaHits, spoilerHits)
            } catch (e: Exception) {
                // Дерево могло поменяться прямо во время обхода — просто пропускаем окно.
                Log.w(TAG, "Ошибка обхода окна", e)
            }

            found.addAll(gtaHits)
            if (gtaHits.isNotEmpty() && pkg.isNotEmpty()) gtaSeenAt[pkg] = startedAt
            if (spoilerHits.isNotEmpty()) {
                val seen = gtaSeenAt[pkg]
                val contextActive = gtaHits.isNotEmpty() ||
                    (seen != null && startedAt - seen <= CONTEXT_TTL_MS)
                if (contextActive) found.addAll(spoilerHits)
            }
        }

        val result = mergeOverlapping(found)
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

    private fun collectMatches(
        root: AccessibilityNodeInfo,
        m: KeywordMatcher,
        sm: KeywordMatcher?,
        card: Boolean,
        windowBounds: Rect,
        gtaOut: MutableList<Rect>,
        spoilerOut: MutableList<Rect>,
    ) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.push(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_NODES_PER_WINDOW) {
            val node = stack.pop()
            visited++

            // Поле ввода не закрываем, чтобы было видно, что печатаешь.
            if (!node.isEditable) {
                val text = node.text
                val desc = node.contentDescription
                val out: MutableList<Rect>? = when {
                    m.matches(text) || m.matches(desc) -> gtaOut
                    sm != null && (sm.matches(text) || sm.matches(desc)) -> spoilerOut
                    else -> null
                }
                if (out != null) {
                    if (node.isVisibleToUser) {
                        val target = if (card) expandToCard(node, windowBounds) else node
                        val r = Rect()
                        target.getBoundsInScreen(r)
                        if (r.intersect(windowBounds) && !r.isEmpty) out.add(r)
                    }
                    if (out === gtaOut) continue // внутрь совпавшего элемента не спускаемся
                }
            }

            for (i in node.childCount - 1 downTo 0) {
                val child = try { node.getChild(i) } catch (e: Exception) { null }
                if (child != null) stack.push(child)
            }
        }
    }

    /**
     * Поднимаемся от совпавшего текста к родителям, пока элемент похож на «карточку»
     * (пост, превью видео, сообщение), а не на весь список. Так закрывается и превью-картинка
     * рядом с заголовком, которую само дерево элементов прочитать не может.
     */
    private fun expandToCard(node: AccessibilityNodeInfo, windowBounds: Rect): AccessibilityNodeInfo {
        val maxH = windowBounds.height() * CARD_MAX_HEIGHT_FRACTION
        val maxArea = windowBounds.width().toLong() * windowBounds.height() * CARD_MAX_AREA_FRACTION
        var best = node
        var cur: AccessibilityNodeInfo? = node.parent
        var levels = 0
        val b = Rect()
        while (cur != null && levels < CARD_MAX_LEVELS) {
            cur.getBoundsInScreen(b)
            val area = b.width().toLong() * b.height()
            if (b.height() > maxH || area > maxArea) break
            best = cur
            cur = cur.parent
            levels++
        }
        return best
    }

    private fun mergeOverlapping(rects: List<Rect>): List<Rect> {
        if (rects.size < 2) return rects
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
        private const val MIN_SCAN_INTERVAL_MS = 60L
        private const val MAX_NODES_PER_WINDOW = 5000
        private const val CARD_MAX_LEVELS = 6
        private const val CARD_MAX_HEIGHT_FRACTION = 0.45f
        private const val CARD_MAX_AREA_FRACTION = 0.40f
        private const val CONTEXT_TTL_MS = 3 * 60_000L
        private const val SCROLL_QUIET_MS = 150L
        private const val PARANOID_MIN_HEIGHT_FRACTION = 0.4f
    }
}
