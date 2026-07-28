package com.example.videohr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/** 简单脉搏波形显示 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var data: FloatArray? = null
        set(value) { field = value; invalidate() }
    var lineColor: Int = 0xFF4CAF50.toInt()
        set(value) { field = value; invalidate() }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x33FFFFFF
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        canvas.drawLine(0f, h / 2, w, h / 2, gridPaint)
        val d = data ?: return
        if (d.size < 2) return

        var maxAbs = 1e-6f
        for (v in d) maxAbs = maxOf(maxAbs, abs(v))
        path.reset()
        val mid = h / 2
        val amp = h * 0.42f / maxAbs
        for (i in d.indices) {
            val x = i.toFloat() / (d.size - 1) * w
            val y = mid - d[i] * amp
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        wavePaint.color = lineColor
        canvas.drawPath(path, wavePaint)
    }
}
