package com.chickenbuter.spoilerguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

/**
 * Полноэкранный прозрачный слой. Рисует:
 * - плашки по экранным координатам (когда блюр выключен или недоступен);
 * - «завесу» параноидального режима над прокручиваемой лентой.
 */
class OverlayView(context: Context) : View(context) {

    private val rects = ArrayList<Rect>()
    private var veil: Rect? = null
    private val loc = IntArray(2)
    private val tmp = RectF()

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val radius = dp(10f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(28, 24, 40) // полностью непрозрачно
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.rgb(255, 79, 154)
    }
    private val veilFill = Paint().apply {
        style = Paint.Style.FILL
        color = Color.rgb(18, 16, 26) // тоже непрозрачно: во время прокрутки ничего не видно
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics
        )
    }
    private val labelText = "🙈 спойлер GTA VI"
    private val shortLabel = "🙈"
    private val veilText = "🙈 проверяю…"

    fun setRects(newRects: List<Rect>) {
        if (newRects == rects) return
        rects.clear()
        newRects.mapTo(rects) { Rect(it) }
        invalidate()
    }

    fun setVeil(r: Rect?) {
        if (r == veil) return
        veil = r?.let { Rect(it) }
        invalidate()
    }

    fun hasVeil() = veil != null

    /** Сдвигает плашки внутри прокручиваемого контейнера до следующего скана. */
    fun shift(container: Rect, dx: Int, dy: Int) {
        var changed = false
        for (r in rects) {
            if (Rect.intersects(r, container)) {
                r.offset(-dx, -dy)
                changed = true
            }
        }
        if (changed) invalidate()
    }

    private fun toLocal(r: Rect) {
        tmp.set(
            (r.left - loc[0]).toFloat(),
            (r.top - loc[1]).toFloat(),
            (r.right - loc[0]).toFloat(),
            (r.bottom - loc[1]).toFloat(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rects.isEmpty() && veil == null) return
        // На случай если окно оверлея не начинается ровно с (0,0) экрана.
        getLocationOnScreen(loc)
        val fm = label.fontMetrics
        val textH = fm.descent - fm.ascent

        veil?.let { v ->
            toLocal(v)
            canvas.drawRect(tmp, veilFill)
            val y = tmp.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(veilText, tmp.centerX(), y, label)
        }

        for (r in rects) {
            toLocal(r)
            canvas.drawRoundRect(tmp, radius, radius, fill)
            canvas.drawRoundRect(tmp, radius, radius, stroke)

            if (tmp.height() >= textH + dp(4f)) {
                val text = if (label.measureText(labelText) + dp(12f) <= tmp.width()) labelText
                else if (label.measureText(shortLabel) + dp(4f) <= tmp.width()) shortLabel
                else null
                if (text != null) {
                    val y = tmp.centerY() - (fm.ascent + fm.descent) / 2f
                    canvas.drawText(text, tmp.centerX(), y, label)
                }
            }
        }
    }
}
