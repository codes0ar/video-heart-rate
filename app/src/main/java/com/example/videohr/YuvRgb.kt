package com.example.videohr

import android.graphics.RectF
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 相机缓冲（未旋转）与 ML Kit 返回的正立坐标系之间的转换 */
object CoordinateMap {

    /** 正立坐标 → 缓冲坐标（rotationDegrees 为图像需顺时针旋转的角度） */
    fun uprightToBuffer(r: RectF, rotation: Int, bufW: Int, bufH: Int): RectF {
        val (l, t) = point(r.left, r.top, rotation, bufW, bufH)
        val (rr, b) = point(r.right, r.bottom, rotation, bufW, bufH)
        return RectF(minOf(l, rr), minOf(t, b), maxOf(l, rr), maxOf(t, b))
    }

    private fun point(x: Float, y: Float, rotation: Int, bufW: Int, bufH: Int): Pair<Float, Float> {
        return when (rotation) {
            90 -> Pair(y, bufH - 1f - x)
            180 -> Pair(bufW - 1f - x, bufH - 1f - y)
            270 -> Pair(bufW - 1f - y, x)
            else -> Pair(x, y)
        }
    }
}

/** 从 YUV_420_888 帧的指定区域取 mean RGB（BT.601；线性变换，均值互换等价） */
object YuvRgb {

    fun meanRgb(image: ImageProxy, roi: RectF): FloatArray? {
        val w = image.width
        val h = image.height
        val l = roi.left.roundToInt().coerceIn(0, w - 2)
        val t = roi.top.roundToInt().coerceIn(0, h - 2)
        val r = roi.right.roundToInt().coerceIn(l + 1, w - 1)
        val b = roi.bottom.roundToInt().coerceIn(t + 1, h - 1)
        if (r <= l || b <= t) return null

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val area = (r - l).toLong() * (b - t)
        val step = max(1, sqrt(area / 3000.0).toInt())

        var sumY = 0.0
        var sumU = 0.0
        var sumV = 0.0
        var count = 0
        var yy = t
        while (yy < b) {
            val yRow = yy * yPlane.rowStride
            val uvRow = (yy / 2)
            var xx = l
            while (xx < r) {
                val yv = yBuf.get(yRow + xx * yPlane.pixelStride).toInt() and 0xFF
                val uv = uBuf.get(uvRow * uPlane.rowStride + (xx / 2) * uPlane.pixelStride).toInt() and 0xFF
                val vv = vBuf.get(uvRow * vPlane.rowStride + (xx / 2) * vPlane.pixelStride).toInt() and 0xFF
                sumY += yv; sumU += uv; sumV += vv
                count++
                xx += step
            }
            yy += step
        }
        if (count == 0) return null

        val ym = sumY / count
        val um = sumU / count - 128.0
        val vm = sumV / count - 128.0
        return floatArrayOf(
            (ym + 1.402 * vm).toFloat(),
            (ym - 0.344136 * um - 0.714136 * vm).toFloat(),
            (ym + 1.772 * um).toFloat()
        )
    }

    /**
     * mean RGB + 屏幕纹理度量（第 4 分量）。
     * 屏幕显示的图像带有像素网格，ROI 中心 64×64 内 Y 通道的 mean|Laplacian| 显著高于真实皮肤。
     */
    fun meanRgbAndTexture(image: ImageProxy, roi: RectF): FloatArray? {
        val base = meanRgb(image, roi) ?: return null

        val w = image.width
        val h = image.height
        var l = roi.left.roundToInt().coerceIn(1, w - 3)
        var t = roi.top.roundToInt().coerceIn(1, h - 3)
        var r = roi.right.roundToInt().coerceIn(l + 2, w - 2)
        var b = roi.bottom.roundToInt().coerceIn(t + 2, h - 2)
        // 限制在中心 64×64，避免过大 ROI 的开销
        if (r - l > 64) { val c = (l + r) / 2; l = c - 32; r = c + 32 }
        if (b - t > 64) { val c = (t + b) / 2; t = c - 32; b = c + 32 }

        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val stride = yPlane.rowStride
        val pxStride = yPlane.pixelStride

        var sum = 0.0
        var cnt = 0
        var yy = t + 1
        while (yy < b - 1) {
            val row = yy * stride
            val rowUp = (yy - 1) * stride
            val rowDn = (yy + 1) * stride
            var xx = l + 1
            while (xx < r - 1) {
                val c = yBuf.get(row + xx * pxStride).toInt() and 0xFF
                val lap = 4 * c -
                    (yBuf.get(row + (xx - 1) * pxStride).toInt() and 0xFF) -
                    (yBuf.get(row + (xx + 1) * pxStride).toInt() and 0xFF) -
                    (yBuf.get(rowUp + xx * pxStride).toInt() and 0xFF) -
                    (yBuf.get(rowDn + xx * pxStride).toInt() and 0xFF)
                sum += kotlin.math.abs(lap)
                cnt++
                xx++
            }
            yy++
        }
        val tex = if (cnt > 0) (sum / cnt).toFloat() else 0f
        return floatArrayOf(base[0], base[1], base[2], tex)
    }
}
