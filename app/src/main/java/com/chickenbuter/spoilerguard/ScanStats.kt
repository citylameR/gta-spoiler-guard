package com.chickenbuter.spoilerguard

import android.os.SystemClock

/** Статистика сканов для экрана настроек. Живёт в процессе приложения, никуда не пишется. */
object ScanStats {
    private var lastMs = 0L
    private var lastWindows = 0
    private var lastNodes = 0
    private var lastMatches = 0
    private var maxMs = 0L
    private var scans = 0L
    private var cached = 0L // сканы, где ни одно окно не пришлось обходить заново
    private var avgMs = 0.0
    private var lastAt = 0L

    @Synchronized
    fun record(durationMs: Long, windows: Int, nodes: Int, matches: Int) {
        scans++
        lastAt = SystemClock.uptimeMillis()
        lastMatches = matches
        if (windows == 0) {
            cached++
            return
        }
        lastMs = durationMs
        lastWindows = windows
        lastNodes = nodes
        if (durationMs > maxMs) maxMs = durationMs
        // Экспоненциальное среднее, чтобы цифра не прыгала.
        avgMs = if (avgMs == 0.0) durationMs.toDouble() else avgMs * 0.9 + durationMs * 0.1
    }

    @Synchronized
    fun reset() {
        lastMs = 0L
        lastWindows = 0
        lastNodes = 0
        lastMatches = 0
        maxMs = 0L
        scans = 0L
        cached = 0L
        avgMs = 0.0
    }

    @Synchronized
    fun summary(): String {
        if (scans == 0L) return "Сканов пока не было. Включи сервис и полистай любую ленту."
        val ago = (SystemClock.uptimeMillis() - lastAt) / 1000
        return buildString {
            append("Последний обход: ").append(lastMs).append(" мс, окон: ").append(lastWindows)
            append(", элементов: ").append(lastNodes).append('\n')
            append("В среднем: ").append(avgMs.toLong()).append(" мс, максимум: ").append(maxMs).append(" мс\n")
            append("Сканов: ").append(scans).append(", из кеша: ").append(cached).append('\n')
            append("Закрыто сейчас: ").append(lastMatches).append(" (").append(ago).append(" с назад)")
        }
    }
}
