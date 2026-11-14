package com.chickenbuter.spoilerguard

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val FILE = "spoiler_guard"
    const val KEY_ENABLED = "enabled"
    const val KEY_COVER_CARD = "cover_card"
    const val KEY_KEYWORDS = "keywords"

    /**
     * Стандартный список. Одна строка = одна фраза.
     * Пробел между словами означает «любой разделитель или ничего»,
     * поэтому «gta 6» ловит и «GTA6», и «GTA-6», и «#gta_6».
     * Фраза совпадает только целиком: «gta vi» НЕ сработает на «GTA videos».
     */
    val DEFAULT_KEYWORDS = listOf(
        "gta 6", "gta vi", "gta six",
        "grand theft auto 6", "grand theft auto vi",
        "гта 6", "гта vi", "гта6",
        "vice city", "вайс сити", "вайс-сити",
        "leonida", "леонида",
        "lucia caminos", "люсия каминос",
        "jason duval", "джейсон дюваль",
        "port gellhorn",
        "rockstar games",
    )

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(p: SharedPreferences) = p.getBoolean(KEY_ENABLED, true)
    fun coverCard(p: SharedPreferences) = p.getBoolean(KEY_COVER_CARD, true)

    fun keywords(p: SharedPreferences): List<String> {
        val raw = p.getString(KEY_KEYWORDS, null) ?: return DEFAULT_KEYWORDS
        return raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
    }
}

/** Ищет фразы в тексте с учётом границ слов, регистра и разделителей. */
class KeywordMatcher(keywords: List<String>) {
    private val regex: Regex? = buildRegex(keywords)

    fun matches(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty() || regex == null) return false
        return regex.containsMatchIn(normalize(text))
    }

    companion object {
        private const val SEP = "[\\s\\u00A0\\-_.:#/|·•]*"
        private const val NOT_WORD_BEFORE = "(?<![\\p{L}\\p{N}])"
        private const val NOT_WORD_AFTER = "(?![\\p{L}\\p{N}])"

        fun normalize(s: CharSequence): String =
            s.toString().lowercase().replace('ё', 'е')

        private fun buildRegex(keywords: List<String>): Regex? {
            val parts = keywords
                .map { normalize(it).trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .map { kw ->
                    kw.split(Regex("[\\s\\-_]+"))
                        .filter { it.isNotEmpty() }
                        .joinToString(SEP) { Regex.escape(it) }
                }
                .filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            return Regex(NOT_WORD_BEFORE + "(?:" + parts.joinToString("|") + ")" + NOT_WORD_AFTER)
        }
    }
}
