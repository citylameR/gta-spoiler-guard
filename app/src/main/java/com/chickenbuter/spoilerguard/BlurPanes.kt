package com.chickenbuter.spoilerguard

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Настоящий блюр: на каждую область — отдельное маленькое окно спецвозможностей
 * с системным размытием фона (Window.setBackgroundBlurRadius, Android 12+).
 * У окна, добавленного через WindowManager напрямую, такого API нет, поэтому
 * каждое окно — это Dialog без рамки. Окна переиспользуются из пула.
 *
 * Если система отключила размытие (энергосбережение, настройка разработчика),
 * сервис вместо этого рисует обычные плашки.
 */
class BlurPanes(private val context: Context) {

    private class Pane(val dialog: Dialog, val label: TextView) {
        var shown = false
        val bounds = Rect()
    }

    private val panes = ArrayList<Pane>()

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    private val blurRadiusPx = dp(28f).toInt()
    private val cornerPx = dp(10f)

    fun setRects(rects: List<Rect>) {
        val wanted = rects.take(MAX_PANES)
        while (panes.size < wanted.size) {
            val p = createPane() ?: break
            panes.add(p)
        }
        for (i in panes.indices) {
            val pane = panes[i]
            if (i < wanted.size) {
                place(pane, wanted[i])
            } else if (pane.shown) {
                try { pane.dialog.hide() } catch (_: Exception) {}
                pane.shown = false
            }
        }
    }

    fun shift(container: Rect, dx: Int, dy: Int) {
        for (pane in panes) {
            if (pane.shown && Rect.intersects(pane.bounds, container)) {
                val r = Rect(pane.bounds)
                r.offset(-dx, -dy)
                place(pane, r)
            }
        }
    }

    fun clear() = setRects(emptyList())

    fun destroy() {
        for (p in panes) try { p.dialog.dismiss() } catch (_: Exception) {}
        panes.clear()
    }

    private fun place(pane: Pane, r: Rect) {
        if (pane.shown && pane.bounds == r) return
        val window = pane.dialog.window ?: return
        val lp = window.attributes
        lp.x = r.left
        lp.y = r.top
        lp.width = r.width()
        lp.height = r.height()
        window.attributes = lp
        pane.bounds.set(r)
        pane.label.visibility =
            if (r.height() >= dp(22f) && r.width() >= dp(28f)) TextView.VISIBLE else TextView.GONE
        if (!pane.shown) {
            try {
                pane.dialog.show()
                pane.shown = true
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось показать окно блюра", e)
            }
        }
    }

    private fun createPane(): Pane? = try {
        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        val label = TextView(context).apply {
            text = "🙈"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        dialog.setContentView(label)
        dialog.setCancelable(false)
        val w = dialog.window!!
        w.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        w.setFormat(PixelFormat.TRANSLUCENT)
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        // Фон окна задаёт форму размытия (скругления) и добавляет тонировку,
        // чтобы мелкий текст не просвечивал сквозь блюр.
        w.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = cornerPx
            setColor(Color.argb(150, 20, 16, 32))
            setStroke(dp(1.5f).toInt(), Color.rgb(255, 79, 154))
        })
        w.setBackgroundBlurRadius(blurRadiusPx)
        val lp = w.attributes
        lp.gravity = Gravity.TOP or Gravity.START
        lp.windowAnimations = 0
        lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.fitInsetsTypes = 0
        lp.title = "GtaSpoilerGuardBlur"
        w.attributes = lp
        Pane(dialog, label)
    } catch (e: Exception) {
        Log.e(TAG, "Не удалось создать окно блюра", e)
        null
    }

    companion object {
        private const val TAG = "SpoilerGuardBlur"
        private const val MAX_PANES = 24
    }
}
