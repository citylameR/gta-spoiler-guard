package com.chickenbuter.spoilerguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

/** Полноэкранный прозрачный слой, который рисует плашки по экранным координатам. */
class OverlayView(context: Context) : View(context) {

    private val rects = ArrayList<Rect>()
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
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics
        )
    }
    private val labelText = "🙈 спойлер GTA VI"
    private val shortLabel = "🙈"

    fun setRects(newRects: List<Rect>) {
        if (newRects == rects) return
        rects.clear()
        rects.addAll(newRects)
        invalidate()
    }

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rects.isEmpty()) return
        // На случай если окно оверлея не начинается ровно с (0,0) экрана.
        getLocationOnScreen(loc)
        val fm = label.fontMetrics
        val textH = fm.descent - fm.ascent
        for (r in rects) {
            tmp.set(
                (r.left - loc[0]).toFloat(),
                (r.top - loc[1]).toFloat(),
                (r.right - loc[0]).toFloat(),
                (r.bottom - loc[1]).toFloat(),
            )
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
