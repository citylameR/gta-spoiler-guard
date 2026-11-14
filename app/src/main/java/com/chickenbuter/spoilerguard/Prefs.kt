package com.chickenbuter.spoilerguard

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val FILE = "spoiler_guard"
    const val KEY_ENABLED = "enabled"
    const val KEY_COVER_CARD = "cover_card"
    const val KEY_KEYWORDS = "keywords"
    const val KEY_CONTEXT_ENABLED = "context_enabled"
    const val KEY_SPOILER_WORDS = "spoiler_words"
    const val KEY_PARANOID = "paranoid_scroll"
    const val KEY_BLUR = "blur"

    /**
     * Основной список: закрывается всегда.
     * Пробел между словами = «любой разделитель или ничего»: «gta 6» ловит GTA6, GTA-6, #gta_6.
     * Фраза совпадает только целым словом: «gta vi» НЕ сработает на «GTA videos».
     * Звёздочка в конце слова = любое окончание: «концовк*» ловит концовка/концовку/концовке.
     */
    val DEFAULT_KEYWORDS = listOf(
        "gta 6", "gta vi", "gta six",
        "grand theft auto 6", "grand theft auto vi",
        "гта 6", "гта vi", "гта6",
        "vice city", "вайс сити", "вайс-сити",
        "leonida", "леонид*",
        "lucia caminos", "люси* каминос",
        "jason duval", "джейсон* дювал*",
        "port gellhorn",
        "rockstar games",
    )

    /**
     * Слова-спойлеры: закрываются, только если рядом есть контекст GTA —
     * на этом же экране или в этом же приложении за последние минуты.
     */
    val DEFAULT_SPOILER_WORDS = listOf(
        "спойлер*", "концовк*", "финал*", "в конце", "эпилог*", "титры",
        "последн* мисси*", "сюжет*", "твист*", "поворот*",
        "умер*", "умира*", "умрет", "умрёт", "погиб*", "смерт*",
        "убил*", "убива*", "убьет", "убьёт", "убий*", "убит*",
        "предал*", "предат*", "предаст",
        "lucia", "люси*", "jason", "джейсон*",
        "spoiler*", "ending*", "the end", "final mission", "last mission",
        "epilogue", "credits", "plot", "twist*",
        "dies", "died", "dying", "death", "dead",
        "kill*", "betray*", "murder*",
    )

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(p: SharedPreferences) = p.getBoolean(KEY_ENABLED, true)
    fun coverCard(p: SharedPreferences) = p.getBoolean(KEY_COVER_CARD, true)
    fun contextEnabled(p: SharedPreferences) = p.getBoolean(KEY_CONTEXT_ENABLED, true)
    fun paranoid(p: SharedPreferences) = p.getBoolean(KEY_PARANOID, true)
    fun blur(p: SharedPreferences) = p.getBoolean(KEY_BLUR, true)

    fun keywords(p: SharedPreferences) = lines(p, KEY_KEYWORDS, DEFAULT_KEYWORDS)
    fun spoilerWords(p: SharedPreferences) = lines(p, KEY_SPOILER_WORDS, DEFAULT_SPOILER_WORDS)

    private fun lines(p: SharedPreferences, key: String, default: List<String>): List<String> {
        val raw = p.getString(key, null) ?: return default
        return raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
    }
}

/** Ищет фразы в тексте с учётом границ слов, регистра, разделителей и окончаний (*). */
class KeywordMatcher(keywords: List<String>) {
    private val regex: Regex? = buildRegex(keywords)

    fun matches(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty() || regex == null) return false
        return regex.containsMatchIn(normalize(text))
    }

    companion object {
        private const val SEP = "[\\s\\u00A0\\-_.:#/|·•]*"
        private const val WORD_TAIL = "[\\p{L}\\p{N}]*"
        private const val NOT_WORD_BEFORE = "(?<![\\p{L}\\p{N}])"
        private const val NOT_WORD_AFTER = "(?![\\p{L}\\p{N}])"

        fun normalize(s: CharSequence): String =
            s.toString().lowercase().replace('ё', 'е')

        private fun token(t: String): String =
            if (t.endsWith("*")) Regex.escape(t.trimEnd('*')) + WORD_TAIL
            else Regex.escape(t)

        private fun buildRegex(keywords: List<String>): Regex? {
            val parts = keywords
                .map { normalize(it).trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .map { kw ->
                    kw.split(Regex("[\\s\\-_]+"))
                        .filter { it.isNotEmpty() && it.trimEnd('*').isNotEmpty() }
                        .joinToString(SEP) { token(it) }
                }
                .filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            return Regex(NOT_WORD_BEFORE + "(?:" + parts.joinToString("|") + ")" + NOT_WORD_AFTER)
        }
    }
}
