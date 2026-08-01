package com.example.videohr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * 单个人脸的调试图表：
 *  - 上半部：R/G/B 原始颜色变化曲线（各自均值归一，固定±刻度，白色为提取的脉搏波）
 *  - 下半部：0.75~3Hz 频谱（白色），黄色竖线为算法锁定的心率峰
 */
class TrackDebugView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var trackColor: Int = Color.GREEN
    var title: String = ""

    /** 原始 RGB 采样（未归一化） */
    var rgb: Triple<FloatArray, FloatArray, FloatArray>? = null

    /** 带通后的脉搏波形（CHROM 白色 / POS 橙色，叠加在 RGB 图上） */
    var pulse: FloatArray? = null
    var pulsePos: FloatArray? = null

    /** 频谱（归一化幅度），loHz + i*binHz 对应横坐标；CHROM 白 / POS 橙 */
    var spectrum: FloatArray? = null
    var spectrumPos: FloatArray? = null
    var spectrumLoHz: Float = 0.75f
    var spectrumBinHz: Float = 0.01f

    /** 算法锁定的峰值频率（CHROM 黄竖线 / POS 橙竖线） */
    var peakHz: Float = 0f
    var peakHzPos: Float = 0f

    private val path = Path()

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        isFakeBoldText = true
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        color = 0x99FFFFFF.toInt()
    }
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x33FFFFFF
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.WHITE
    }
    private val posPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = 0xFFFF9800.toInt() // 橙
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFFFFEB3B.toInt() // 黄
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 标题
        titlePaint.color = trackColor
        canvas.drawText(title, 8f, 28f, titlePaint)

        val chartTop = 36f
        val chartMid = h * 0.60f   // RGB 图与频谱图分界
        val chartBot = h - 18f     // 底部留给坐标标注

        // ===== 上半：RGB 颜色曲线 =====
        val rgb = this.rgb
        if (rgb != null && rgb.first.size > 4) {
            val half = (chartMid - chartTop) / 2f
            val midY = chartTop + half
            canvas.drawLine(0f, midY, w, midY, gridPaint)

            // 三通道共用刻度：至少 ±1%，最大 ±10%
            var maxDev = 0.01f
            for (ch in listOf(rgb.first, rgb.second, rgb.third)) {
                val mean = ch.average().toFloat()
                if (mean <= 1e-6f) continue
                for (v in ch) {
                    val d = abs((v - mean) / mean)
                    if (d > maxDev) maxDev = d.coerceAtMost(0.10f)
                }
            }
            axisPaint.color = 0x99FFFFFF.toInt()
            canvas.drawText("±%.1f%%".format(maxDev * 100), w - 90f, chartTop + 22f, axisPaint)

            drawChannel(canvas, rgb.first, 0xFFF44336.toInt(), midY, half, maxDev, w)
            drawChannel(canvas, rgb.second, 0xFF4CAF50.toInt(), midY, half, maxDev, w)
            drawChannel(canvas, rgb.third, 0xFF2196F3.toInt(), midY, half, maxDev, w)

            // 叠加提取的脉搏波（CHROM 白 / POS 橙），按自身幅度归一
            drawPulse(canvas, pulse, pulsePaint, midY, half, w)
            drawPulse(canvas, pulsePos, posPaint, midY, half, w)
        }

        // ===== 下半：频谱 =====
        canvas.drawLine(0f, chartMid, w, chartMid, gridPaint)
        val lo = 0.75f
        val hi = 3.0f
        val span = chartBot - chartMid - 6f
        drawSpectrum(canvas, spectrum, linePaint.apply { color = Color.WHITE }, lo, hi, span, chartMid, chartBot, w)
        drawSpectrum(canvas, spectrumPos, posPaint, lo, hi, span, chartMid, chartBot, w)
        if (spectrum != null || spectrumPos != null) {
            // 参考竖线：1Hz=60 / 2Hz=120 / 3Hz=180 BPM
            for (hz in listOf(1f, 2f, 3f)) {
                val x = (hz - lo) / (hi - lo) * w
                canvas.drawLine(x, chartMid, x, chartBot, gridPaint)
                canvas.drawText("${(hz * 60).toInt()}", x - 14f, h - 2f, axisPaint)
            }
            // 锁定峰：CHROM 黄 / POS 橙
            if (peakHz in lo..hi) {
                val x = (peakHz - lo) / (hi - lo) * w
                canvas.drawLine(x, chartMid, x, chartBot, peakPaint)
                canvas.drawText(
                    "C:%.0f".format(peakHz * 60),
                    (x - 30f).coerceIn(0f, w - 80f),
                    chartMid + 20f,
                    titlePaint.apply { color = 0xFFFFEB3B.toInt() }
                )
            }
            if (peakHzPos in lo..hi) {
                val x = (peakHzPos - lo) / (hi - lo) * w
                canvas.drawLine(x, chartMid, x, chartBot, posPaint)
                canvas.drawText(
                    "P:%.0f".format(peakHzPos * 60),
                    (x - 30f).coerceIn(0f, w - 80f),
                    chartMid + 44f,
                    titlePaint.apply { color = 0xFFFF9800.toInt() }
                )
            }
        }
    }

    private fun drawPulse(
        canvas: Canvas,
        data: FloatArray?,
        paint: Paint,
        midY: Float,
        half: Float,
        w: Float,
    ) {
        val p = data ?: return
        if (p.size <= 4) return
        var mx = 1e-6f
        for (v in p) mx = maxOf(mx, abs(v))
        path.reset()
        for (i in p.indices) {
            val x = i.toFloat() / (p.size - 1) * w
            val y = midY - p[i] / mx * half * 0.9f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawSpectrum(
        canvas: Canvas,
        data: FloatArray?,
        paint: Paint,
        lo: Float,
        hi: Float,
        span: Float,
        chartMid: Float,
        chartBot: Float,
        w: Float,
    ) {
        val sp = data ?: return
        if (sp.size <= 2) return
        path.reset()
        for (i in sp.indices) {
            val hz = spectrumLoHz + i * spectrumBinHz
            if (hz < lo || hz > hi) continue
            val x = (hz - lo) / (hi - lo) * w
            val y = chartBot - sp[i].coerceIn(0f, 1f) * span
            if (path.isEmpty) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawChannel(
        canvas: Canvas,
        data: FloatArray,
        color: Int,
        midY: Float,
        half: Float,
        maxDev: Float,
        w: Float,
    ) {
        val mean = data.average().toFloat()
        if (mean <= 1e-6f) return
        path.reset()
        for (i in data.indices) {
            val dev = ((data[i] - mean) / mean).coerceIn(-maxDev, maxDev)
            val x = i.toFloat() / (data.size - 1) * w
            val y = midY - dev / maxDev * half * 0.9f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        linePaint.color = color
        canvas.drawPath(path, linePaint)
    }
}
