package com.chickenbuter.spoilerguard

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Экран настроек, собранный кодом: без AndroidX и XML-разметки. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var openSettings: Button
    private lateinit var blurHint: TextView
    private lateinit var col: LinearLayout
    private lateinit var stats: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val statsTick = object : Runnable {
        override fun run() {
            stats.text = ScanStats.summary()
            ui.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.get(this)

        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }

        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(12), 0, dp(8))
        }
        col.addView(status)

        openSettings = Button(this).apply {
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        col.addView(openSettings)

        section("Скорость")
        stats = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        col.addView(stats)
        col.addView(Button(this).apply {
            text = "Сбросить статистику"
            setOnClickListener {
                ScanStats.reset()
                stats.text = ScanStats.summary()
            }
        })

        col.addView(hint(
            "Если переключатель сервиса серый и написано «Ограниченная настройка»: " +
                "Настройки → Приложения → GTA Spoiler Guard → ⋮ (вверху справа) → " +
                "«Разрешить ограниченные настройки», потом включи сервис ещё раз."
        ))

        switch(prefs, "Защита включена", Prefs.KEY_ENABLED, Prefs.isEnabled(prefs))
        col.addView(hint("То же самое переключается плиткой «Спойлеры GTA» в шторке — добавь её через карандаш в быстрых настройках."))

        switch(prefs, "Блюр вместо плашки", Prefs.KEY_BLUR, Prefs.blur(prefs))
        blurHint = hint("")
        col.addView(blurHint)

        switch(prefs, "Закрывать карточку целиком", Prefs.KEY_COVER_CARD, Prefs.coverCard(prefs))
        col.addView(hint("Закрывается вся строка ленты, где нашёлся текст: пост, сообщение, карточка видео вместе с картинкой. Альбомы в Telegram — целиком."))

        switch(prefs, "Параноидальный режим прокрутки", Prefs.KEY_PARANOID, Prefs.paranoid(prefs))
        col.addView(hint("Пока лента прокручивается, она закрыта целиком и открывается после проверки. Ничего не мелькнёт, но листать будет непривычно."))

        switch(prefs, "Слова-спойлеры рядом с GTA", Prefs.KEY_CONTEXT_ENABLED, Prefs.contextEnabled(prefs))
        col.addView(hint("Слова из второго списка (концовка, умер, ending…) закрываются, только если на экране есть GTA или в этом приложении GTA была за последние 3 минуты."))

        section("Всегда закрывать")
        col.addView(hint("По одной фразе на строку. Регистр не важен. Пробел = любой разделитель или слитно: «gta 6» ловит GTA6, GTA-6, #gta_6. * в конце = любое окончание: «леонид*». Строки с // — комментарии."))
        val keywordsEditor = editor(Prefs.keywords(prefs))

        section("Слова-спойлеры (только рядом с GTA)")
        val spoilerEditor = editor(Prefs.spoilerWords(prefs))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Сохранить"
            setOnClickListener {
                prefs.edit()
                    .putString(Prefs.KEY_KEYWORDS, keywordsEditor.text.toString())
                    .putString(Prefs.KEY_SPOILER_WORDS, spoilerEditor.text.toString())
                    .apply()
                Toast.makeText(this@MainActivity, "Сохранено", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "Стандартные"
            setOnClickListener {
                prefs.edit().remove(Prefs.KEY_KEYWORDS).remove(Prefs.KEY_SPOILER_WORDS).apply()
                keywordsEditor.setText(Prefs.DEFAULT_KEYWORDS.joinToString("\n"))
                spoilerEditor.setText(Prefs.DEFAULT_SPOILER_WORDS.joinToString("\n"))
            }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(buttons)

        col.addView(hint("Само это окно сервис не закрывает, поэтому списки здесь всегда видны. Доступа в интернет у приложения нет."))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(col)
            // targetSdk 35+ рисует под системными панелями — отступаем вручную.
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }
        setContentView(scroll)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(statsTick)
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(statsTick)
        ui.post(statsTick)
        val on = isServiceEnabled()
        status.text = if (on) "✅ Сервис включён"
        else "⚠️ Сервис выключен. Включи его в спецвозможностях: «Скачанные приложения» → GTA Spoiler Guard."
        openSettings.text = if (on) "Настройки спецвозможностей" else "Включить в спецвозможностях"

        val blurOk = getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true
        blurHint.text = if (blurOk) "Системный блюр доступен."
        else "Сейчас система отключила блюр (энергосбережение или настройка «Размытие на уровне окон» в разделе для разработчиков) — будут плашки."
    }

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, SpoilerGuardService::class.java)
        val list = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun switch(prefs: SharedPreferences, title: String, key: String, value: Boolean) {
        col.addView(Switch(this).apply {
            text = title
            isChecked = value
            setPadding(0, dp(16), 0, dp(4))
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        })
    }

    private fun section(title: String) {
        col.addView(TextView(this).apply {
            text = title
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(20), 0, dp(4))
        })
    }

    private fun editor(lines: List<String>): EditText {
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
            minLines = 6
            typeface = Typeface.MONOSPACE
            setText(lines.joinToString("\n"))
        }
        col.addView(e, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return e
    }

    private fun hint(s: String) = TextView(this).apply {
        text = s
        alpha = 0.7f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }
}
