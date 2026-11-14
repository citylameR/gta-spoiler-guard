package com.chickenbuter.spoilerguard

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
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

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.get(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }

        col.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
        })

        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(12), 0, dp(8))
        }
        col.addView(status)

        openSettings = Button(this).apply {
            text = "Открыть настройки спецвозможностей"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        col.addView(openSettings)

        col.addView(hint(
            "Если переключатель сервиса серый и написано «Ограниченная настройка»: " +
                "Настройки → Приложения → GTA Spoiler Guard → ⋮ (вверху справа) → " +
                "«Разрешить ограниченные настройки», потом включи сервис ещё раз."
        ))

        col.addView(Switch(this).apply {
            text = "Защита включена"
            isChecked = Prefs.isEnabled(prefs)
            setPadding(0, dp(16), 0, dp(4))
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(Prefs.KEY_ENABLED, on).apply()
            }
        })
        col.addView(hint("То же самое переключается плиткой «Спойлеры GTA» в шторке — добавь её через карандаш в быстрых настройках."))

        col.addView(Switch(this).apply {
            text = "Закрывать карточку целиком"
            isChecked = Prefs.coverCard(prefs)
            setPadding(0, dp(16), 0, dp(4))
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(Prefs.KEY_COVER_CARD, on).apply()
            }
        })
        col.addView(hint("Вместе с текстом закрывается пост или превью видео целиком, включая картинку. Если выключить, закрывается только сам текст."))

        col.addView(TextView(this).apply {
            text = "Слова и фразы (по одной на строку)"
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(20), 0, dp(4))
        })
        col.addView(hint("Регистр не важен. Пробел = любой разделитель или слитно: «gta 6» ловит GTA6, GTA-6, #gta_6. Совпадение только целым словом. Строки с // — комментарии."))

        val editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
            minLines = 8
            typeface = Typeface.MONOSPACE
            setText(Prefs.keywords(prefs).joinToString("\n"))
        }
        col.addView(editor, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Сохранить"
            setOnClickListener {
                prefs.edit().putString(Prefs.KEY_KEYWORDS, editor.text.toString()).apply()
                Toast.makeText(this@MainActivity, "Сохранено", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "Стандартные"
            setOnClickListener {
                prefs.edit().remove(Prefs.KEY_KEYWORDS).apply()
                editor.setText(Prefs.DEFAULT_KEYWORDS.joinToString("\n"))
            }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(buttons)

        col.addView(hint("Само это окно сервис не закрывает, поэтому список слов здесь всегда виден. Доступа в интернет у приложения нет."))

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

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        status.text = if (on) "✅ Сервис включён" else "⚠️ Сервис выключен. Включи его в спецвозможностях: «Скачанные приложения» → GTA Spoiler Guard."
        openSettings.text = if (on) "Настройки спецвозможностей" else "Включить в спецвозможностях"
    }

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, SpoilerGuardService::class.java)
        val list = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun hint(s: String) = TextView(this).apply {
        text = s
        alpha = 0.7f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }
}
