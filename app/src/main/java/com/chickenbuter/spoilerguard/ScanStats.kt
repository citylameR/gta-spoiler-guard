package com.chickenbuter.spoilerguard

import android.os.SystemClock

/** Статистика сканов для экрана настроек. Живёт в процессе приложения, никуда не пишется. */
object ScanStats {
    private var scans = 0L
    private var idle = 0L           // сканы, где не пришлось читать экран вообще
    private var fullWindows = 0L    // полных обходов окон
    private var partials = 0L       // частичных обходов поддеревьев
    private var refreshed = 0L      // обновлений найденных элементов при прокрутке
    private var lastMs = 0L
    private var lastNodes = 0
    private var lastKind = ""
    private var lastMatches = 0
    private var maxMs = 0L
    private var avgMs = 0.0
    private var lastAt = 0L

    @Synchronized
    fun record(durationMs: Long, full: Int, partial: Int, refreshed: Int, nodes: Int, matches: Int) {
        scans++
        lastAt = SystemClock.uptimeMillis()
        lastMatches = matches
        fullWindows += full
        partials += partial
        this.refreshed += refreshed
        if (full == 0 && partial == 0 && refreshed == 0) {
            idle++
            return
        }
        lastMs = durationMs
        lastNodes = nodes
        lastKind = buildString {
            if (full > 0) append("полный×").append(full).append(' ')
            if (partial > 0) append("частичный×").append(partial).append(' ')
            if (refreshed > 0) append("слежение×").append(refreshed)
        }.trim()
        if (durationMs > maxMs) maxMs = durationMs
        // Экспоненциальное среднее, чтобы цифра не прыгала.
        avgMs = if (avgMs == 0.0) durationMs.toDouble() else avgMs * 0.9 + durationMs * 0.1
    }

    @Synchronized
    fun reset() {
        scans = 0L; idle = 0L; fullWindows = 0L; partials = 0L; refreshed = 0L
        lastMs = 0L; lastNodes = 0; lastKind = ""; lastMatches = 0
        maxMs = 0L; avgMs = 0.0
    }

    @Synchronized
    fun summary(): String {
        if (scans == 0L) return "Сканов пока не было. Включи сервис и полистай любую ленту."
        val ago = (SystemClock.uptimeMillis() - lastAt) / 1000
        return buildString {
            append("Последний: ").append(lastMs).append(" мс, элементов: ").append(lastNodes)
            append(" (").append(lastKind).append(")\n")
            append("В среднем: ").append(avgMs.toLong()).append(" мс, максимум: ").append(maxMs).append(" мс\n")
            append("Сканов: ").append(scans).append(", без чтения экрана: ").append(idle).append('\n')
            append("Полных: ").append(fullWindows).append(", частичных: ").append(partials)
            append(", слежений: ").append(refreshed).append('\n')
            append("Закрыто сейчас: ").append(lastMatches).append(" (").append(ago).append(" с назад)")
        }
    }
}
