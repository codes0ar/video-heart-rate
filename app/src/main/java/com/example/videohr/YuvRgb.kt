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
}
