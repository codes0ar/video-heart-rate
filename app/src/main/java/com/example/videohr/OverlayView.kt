package com.example.videohr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** 在预览上绘制人脸框 + 心率标签 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Item(val rect: RectF, val label: String, val color: Int)

    private var items: List<Item> = emptyList()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 38f
        isFakeBoldText = true
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setItems(newItems: List<Item>) {
        items = newItems
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (item in items) {
            boxPaint.color = item.color
            canvas.drawRect(item.rect, boxPaint)

            val label = item.label
            val tw = textPaint.measureText(label)
            val pad = 10f
            val th = textPaint.textSize
            var lx = item.rect.left
            var ly = item.rect.top - th - 2 * pad
            if (ly < 0) ly = item.rect.bottom + pad
            bgPaint.color = item.color and 0x00FFFFFF or 0xAA000000.toInt()
            canvas.drawRect(lx, ly, lx + tw + 2 * pad, ly + th + 2 * pad, bgPaint)
            textPaint.color = Color.WHITE
            canvas.drawText(label, lx + pad, ly + pad + th * 0.85f, textPaint)
        }
    }

    companion object {
        val TRACK_COLORS = intArrayOf(
            0xFF4CAF50.toInt(), // 绿
            0xFF00BCD4.toInt(), // 青
            0xFFFFC107.toInt(), // 黄
            0xFFE91E63.toInt(), // 粉
        )

        fun colorFor(id: Int): Int = TRACK_COLORS[(id - 1) % TRACK_COLORS.size]
    }
}
