package com.chickenbuter.spoilerguard

import android.os.SystemClock

/** Статистика сканов для экрана настроек. Живёт в процессе приложения, никуда не пишется. */
object ScanStats {
    /** Вызов дольше этого порога — настоящий запрос к приложению, а не ответ из кеша. */
    const val REQUEST_THRESHOLD_NS = 200_000L

    private var scans = 0L
    private var idle = 0L           // сканы, где не пришлось читать экран вообще
    private var fullWindows = 0L    // полных обходов окон
    private var listDiffs = 0L      // прокрутка/изменение списка: прочитаны только новые строки
    private var subtrees = 0L       // обходов изменившихся поддеревьев
    private var refreshed = 0L      // перепроверок найденных элементов
    private var lastMs = 0L
    private var lastNodes = 0
    private var lastKind = ""
    private var lastMatches = 0
    private var maxMs = 0L
    private var avgMs = 0.0
    private var lastAt = 0L

    private var calls = 0L          // все обращения к элементам
    private var requests = 0L       // из них настоящие запросы к приложению
    private var requestNs = 0L      // суммарное ожидание ответа
    private var lastRequests = 0

    @Volatile var cacheInfo = "неизвестно"

    class Scan {
        var full = 0
        var listDiff = 0
        var subtree = 0
        var refreshed = 0
        var nodes = 0
        var calls = 0
        var requests = 0
        var requestNs = 0L

        /** Засекает обращение к элементу. */
        inline fun <T> call(block: () -> T): T {
            val t0 = System.nanoTime()
            try {
                return block()
            } finally {
                val dt = System.nanoTime() - t0
                calls++
                if (dt >= REQUEST_THRESHOLD_NS) {
                    requests++
                    requestNs += dt
                }
            }
        }
    }

    @Synchronized
    fun record(durationMs: Long, s: Scan, matches: Int) {
        scans++
        lastAt = SystemClock.uptimeMillis()
        lastMatches = matches
        fullWindows += s.full
        listDiffs += s.listDiff
        subtrees += s.subtree
        refreshed += s.refreshed
        calls += s.calls
        requests += s.requests
        requestNs += s.requestNs
        if (s.full == 0 && s.listDiff == 0 && s.subtree == 0 && s.refreshed == 0) {
            idle++
            return
        }
        lastMs = durationMs
        lastNodes = s.nodes
        lastRequests = s.requests
        lastKind = buildString {
            if (s.full > 0) append("полный×").append(s.full).append(' ')
            if (s.listDiff > 0) append("строки×").append(s.listDiff).append(' ')
            if (s.subtree > 0) append("поддерево×").append(s.subtree).append(' ')
            if (s.refreshed > 0) append("проверка×").append(s.refreshed)
        }.trim()
        if (durationMs > maxMs) maxMs = durationMs
        // Экспоненциальное среднее, чтобы цифра не прыгала.
        avgMs = if (avgMs == 0.0) durationMs.toDouble() else avgMs * 0.9 + durationMs * 0.1
    }

    @Synchronized
    fun reset() {
        scans = 0L; idle = 0L; fullWindows = 0L; listDiffs = 0L; subtrees = 0L; refreshed = 0L
        lastMs = 0L; lastNodes = 0; lastKind = ""; lastMatches = 0; lastRequests = 0
        maxMs = 0L; avgMs = 0.0
        calls = 0L; requests = 0L; requestNs = 0L
    }

    @Synchronized
    fun summary(): String {
        if (scans == 0L) return "Сканов пока не было. Включи сервис и полистай любую ленту."
        val ago = (SystemClock.uptimeMillis() - lastAt) / 1000
        val avgReqMs = if (requests > 0) requestNs / requests / 1e6 else 0.0
        val hitRate = if (calls > 0) 100 - requests * 100 / calls else 0
        return buildString {
            append("Последний: ").append(lastMs).append(" мс, элементов: ").append(lastNodes)
            append(", запросов: ").append(lastRequests).append('\n')
            append("  (").append(lastKind).append(")\n")
            append("В среднем: ").append(avgMs.toLong()).append(" мс, максимум: ").append(maxMs).append(" мс\n")
            append("Сканов: ").append(scans).append(", без чтения экрана: ").append(idle).append('\n')
            append("Полных: ").append(fullWindows).append(", строк: ").append(listDiffs)
            append(", поддеревьев: ").append(subtrees).append(", проверок: ").append(refreshed).append('\n')
            append("Обращений: ").append(calls).append(", из кеша: ").append(hitRate).append("%\n")
            append("Ответ приложения: ").append(String.format("%.1f", avgReqMs)).append(" мс на запрос\n")
            append("Кеш элементов: ").append(cacheInfo).append('\n')
            append("Закрыто сейчас: ").append(lastMatches).append(" (").append(ago).append(" с назад)")
        }
    }
}
