package com.example.videohr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 在预览上绘制人脸框 + 心率标签 + 额头心率曲线窗。
 * 曲线窗优先放在人脸框正上方（视觉正处脑门位置）；上方空间不足时画进额头区域。
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Item(
        val rect: RectF,
        val label: String,
        val color: Int,
        val curve: FloatArray? = null,     // CHROM BPM 历史（最近 ~20 秒）
        val curvePos: FloatArray? = null,  // POS BPM 历史
        val curveMin: Float = 0f,
        val curveMax: Float = 0f,
    )

    private var items: List<Item> = emptyList()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f
        isFakeBoldText = true
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
        color = 0x99FFFFFF.toInt()
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x44FFFFFF
    }
    private val path = Path()

    fun setItems(newItems: List<Item>) {
        items = newItems
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (item in items) {
            boxPaint.color = item.color
            canvas.drawRect(item.rect, boxPaint)

            val hasCurve = item.curve != null && item.curve.size >= 2
            if (!hasCurve) {
                drawLabel(canvas, item, item.rect.left, item.rect.top - 8f)
                continue
            }

            // ---- 额头心率曲线窗 ----
            val chartW = item.rect.width()
            val chartH = minOf(item.rect.height() * 0.55f, 180f)
            var top = item.rect.top - chartH - 8f
            if (top < 0f) top = item.rect.top + 8f // 空间不足，画进额头区域
            val left = item.rect.left
            val right = item.rect.right
            val bottom = top + chartH

            bgPaint.color = 0x99000000.toInt()
            canvas.drawRoundRect(RectF(left, top, right, bottom), 10f, 10f, bgPaint)
            boxPaint.color = item.color
            boxPaint.strokeWidth = 2f
            canvas.drawRoundRect(RectF(left, top, right, bottom), 10f, 10f, boxPaint)
            boxPaint.strokeWidth = 4f

            // 标题（当前值）
            textPaint.color = item.color
            canvas.drawText(item.label, left + 10f, top + 38f, textPaint)

            // 曲线区域（标题之下）
            val areaTop = top + 48f
            val areaBot = bottom - 10f
            val span = (item.curveMax - item.curveMin).coerceAtLeast(1f)

            // 上下刻度 + 中线
            canvas.drawText("%.0f".format(item.curveMax), left + 8f, areaTop + 10f, axisPaint)
            canvas.drawText("%.0f".format(item.curveMin), left + 8f, areaBot, axisPaint)
            canvas.drawLine(left, (areaTop + areaBot) / 2f, right, (areaTop + areaBot) / 2f, gridPaint)

            drawCurve(canvas, item.curve, item.color, left, right, areaTop, areaBot, item.curveMin, span)
            drawCurve(canvas, item.curvePos, 0xFFFF9800.toInt(), left, right, areaTop, areaBot, item.curveMin, span)
        }
    }

    private fun drawLabel(canvas: Canvas, item: Item, x: Float, baseline: Float) {
        val tw = textPaint.measureText(item.label)
        val pad = 10f
        val th = textPaint.textSize
        var ly = baseline - th - 2 * pad
        if (ly < 0) ly = item.rect.bottom + pad
        bgPaint.color = item.color and 0x00FFFFFF or 0xAA000000.toInt()
        canvas.drawRect(x, ly, x + tw + 2 * pad, ly + th + 2 * pad, bgPaint)
        textPaint.color = Color.WHITE
        canvas.drawText(item.label, x + pad, ly + pad + th * 0.85f, textPaint)
    }

    /** 曲线右对齐（最新值在最右），y 轴按 [min, min+span] 映射 */
    private fun drawCurve(
        canvas: Canvas,
        data: FloatArray?,
        color: Int,
        left: Float,
        right: Float,
        areaTop: Float,
        areaBot: Float,
        min: Float,
        span: Float,
    ) {
        val d = data ?: return
        if (d.size < 2) return
        path.reset()
        for (i in d.indices) {
            val x = right - (d.size - 1 - i).toFloat() / (d.size - 1) * (right - left)
            val y = areaBot - ((d[i] - min) / span).coerceIn(0f, 1f) * (areaBot - areaTop)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        curvePaint.color = color
        canvas.drawPath(path, curvePaint)
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
