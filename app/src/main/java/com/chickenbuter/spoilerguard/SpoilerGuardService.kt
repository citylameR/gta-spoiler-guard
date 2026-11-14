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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Сердце приложения.
 *
 * 1. Система присылает события «контент изменился / прокрутили / сменилось окно».
 * 2. Не чаще раза в [MIN_SCAN_INTERVAL_MS] мс на фоновом потоке обходим дерево
 *    элементов всех окон на экране и ищем ключевые фразы в тексте и contentDescription.
 * 3. Координаты совпадений (или целой карточки вокруг них) отдаём [OverlayView],
 *    который рисует поверх всего непрозрачные плашки. Касания проходят насквозь.
 */
class SpoilerGuardService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var prefs: SharedPreferences

    private var windowManager: WindowManager? = null
    private var overlay: OverlayView? = null

    @Volatile private var enabled = true
    @Volatile private var coverCard = true
    @Volatile private var matcher = KeywordMatcher(Prefs.DEFAULT_KEYWORDS)

    private val scanScheduled = AtomicBoolean(false)
    @Volatile private var lastScanAt = 0L

    private val scanRunnable = Runnable { scan() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        workerThread = HandlerThread("spoiler-scan").apply { start() }
        worker = Handler(workerThread.looper)

        prefs = Prefs.get(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        reloadPrefs()

        addOverlay()
        scheduleScan()
    }

    private fun reloadPrefs() {
        enabled = Prefs.isEnabled(prefs)
        coverCard = Prefs.coverCard(prefs)
        matcher = KeywordMatcher(Prefs.keywords(prefs))
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        reloadPrefs()
        if (!enabled) main.post { overlay?.setRects(emptyList()) }
        scheduleScan()
    }

    private fun addOverlay() {
        val wm = getSystemService(WindowManager::class.java) ?: return
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
            windowManager = wm
            overlay = view
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось добавить оверлей", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !enabled) return

        // Быстрый путь: если система сообщила, на сколько прокрутили,
        // сразу сдвигаем плашки, не дожидаясь пересканирования.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val dx = event.scrollDeltaX
            val dy = event.scrollDeltaY
            if (abs(dx) > 1 || abs(dy) > 1) {
                val src = event.source
                if (src != null) {
                    val container = Rect()
                    src.getBoundsInScreen(container)
                    overlay?.shift(container, if (abs(dx) > 1) dx else 0, if (abs(dy) > 1) dy else 0)
                }
            }
        }
        scheduleScan()
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
        lastScanAt = SystemClock.uptimeMillis()

        if (!enabled) {
            main.post { overlay?.setRects(emptyList()) }
            return
        }

        val m = matcher
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
            // Своё окно не трогаем, иначе закроем список слов в настройках.
            if (root.packageName?.toString() == packageName) continue

            val windowBounds = Rect()
            w.getBoundsInScreen(windowBounds)
            try {
                collectMatches(root, m, card, windowBounds, found)
            } catch (e: Exception) {
                // Дерево могло поменяться прямо во время обхода — просто пропускаем окно.
                Log.w(TAG, "Ошибка обхода окна", e)
            }
        }

        val result = mergeOverlapping(found)
        main.post { overlay?.setRects(result) }
    }

    private fun collectMatches(
        root: AccessibilityNodeInfo,
        m: KeywordMatcher,
        card: Boolean,
        windowBounds: Rect,
        out: MutableList<Rect>,
    ) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.push(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_NODES_PER_WINDOW) {
            val node = stack.pop()
            visited++

            // Поле ввода не закрываем, чтобы было видно, что печатаешь.
            val hit = !node.isEditable &&
                (m.matches(node.text) || m.matches(node.contentDescription))

            if (hit) {
                if (node.isVisibleToUser) {
                    val target = if (card) expandToCard(node, windowBounds) else node
                    val r = Rect()
                    target.getBoundsInScreen(r)
                    if (r.intersect(windowBounds) && !r.isEmpty) out.add(r)
                }
                continue // внутрь совпавшего элемента не спускаемся
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
    }
}
