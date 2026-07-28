package com.example.videohr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * rPPG 信号处理：CHROM 色度法去运动伪差 + 加窗 FFT 频谱峰值估计心率。
 * 参考: de Haan & Jeanne, "Robust Pulse Rate From Chrominance-Based rPPG" (2013)
 */
object SignalProcessor {

    data class PulseResult(
        val bpm: Double,
        val snr: Double,          // 峰值附近能量 / 频带总能量，0~1，越大越可信
        val waveform: FloatArray, // 带通滤波后的脉搏波形（用于显示）
    )

    private const val MIN_HZ = 0.75 // 45 BPM
    private const val MAX_HZ = 3.0  // 180 BPM

    fun compute(r: List<Float>, g: List<Float>, b: List<Float>, fps: Double): PulseResult? {
        val n = r.size
        if (n < 48 || fps < 5.0) return null

        val rn = norm(r) ?: return null
        val gn = norm(g) ?: return null
        val bn = norm(b) ?: return null

        // CHROM：投影到与肤色正交的平面，消除亮度（镜面反射）分量
        val xs = DoubleArray(n) { i -> 3.0 * rn[i] - 2.0 * gn[i] }
        val ys = DoubleArray(n) { i -> 1.5 * rn[i] + gn[i] - 1.5 * bn[i] }
        val sy = std(ys)
        if (sy < 1e-9) return null
        val alpha = std(xs) / sy
        val s = detrend(DoubleArray(n) { i -> xs[i] - alpha * ys[i] })

        // 零填充 FFT（Hann 窗）
        var m = 1
        while (m < n * 2) m = m shl 1
        m = min(m, 8192)
        val re = DoubleArray(m)
        val im = DoubleArray(m)
        for (i in 0 until n) {
            val w = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
            re[i] = s[i] * w
        }
        fft(re, im)

        val binHz = fps / m
        val lo = max(1, (MIN_HZ / binHz).toInt())
        val hi = min(m / 2, (MAX_HZ / binHz).roundToInt() + 1)
        if (hi <= lo) return null

        val mags = DoubleArray(m / 2 + 1) { i -> re[i] * re[i] + im[i] * im[i] }
        var peak = lo
        var peakMag = 0.0
        for (i in lo..hi) {
            if (mags[i] > peakMag) { peakMag = mags[i]; peak = i }
        }
        if (peakMag <= 0.0) return null

        // 抛物线插值提高频率分辨率
        var freq = peak * binHz
        if (peak > lo && peak < hi) {
            val y0 = sqrt(mags[peak - 1]); val y1 = sqrt(mags[peak]); val y2 = sqrt(mags[peak + 1])
            val d = (y2 - y0) / (2.0 * (2.0 * y1 - y0 - y2) + 1e-12)
            if (d.isFinite() && abs(d) < 1.0) freq = (peak + d) * binHz
        }

        // SNR：峰值 ±2 bin 能量占频带能量比例
        var near = 0.0
        var total = 0.0
        for (i in lo..hi) {
            total += mags[i]
            if (abs(i - peak) <= 2) near += mags[i]
        }
        val snr = if (total > 0.0) near / total else 0.0

        // 频带掩码逆 FFT 得到带通脉搏波形（仅供显示）
        val wre = DoubleArray(m)
        val wim = DoubleArray(m)
        for (i in lo..hi) {
            wre[i] = re[i]; wim[i] = im[i]
            wre[m - i] = re[m - i]; wim[m - i] = im[m - i]
        }
        ifft(wre, wim)
        val wave = FloatArray(n) { i -> wre[i].toFloat() }

        return PulseResult(freq * 60.0, snr, wave)
    }

    private fun norm(x: List<Float>): DoubleArray? {
        var mean = 0.0
        for (v in x) mean += v
        mean /= x.size
        if (mean < 1e-6) return null
        return DoubleArray(x.size) { i -> x[i] / mean }
    }

    private fun std(x: DoubleArray): Double {
        var mean = 0.0
        for (v in x) mean += v
        mean /= x.size
        var acc = 0.0
        for (v in x) { val d = v - mean; acc += d * d }
        return sqrt(acc / x.size)
    }

    /** 最小二乘线性去趋势 */
    private fun detrend(x: DoubleArray): DoubleArray {
        val n = x.size
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            sx += i; sy += x[i]; sxx += i.toDouble() * i; sxy += i * x[i]
        }
        val denom = n * sxx - sx * sx
        if (abs(denom) < 1e-9) {
            val mean = sy / n
            return DoubleArray(n) { i -> x[i] - mean }
        }
        val slope = (n * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / n
        return DoubleArray(n) { i -> x[i] - (slope * i + intercept) }
    }

    private fun ifft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        for (i in 0 until n) im[i] = -im[i]
        fft(re, im)
        for (i in 0 until n) { re[i] = re[i] / n; im[i] = -im[i] / n }
    }

    /** 迭代基-2 FFT，长度必须为 2 的幂 */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var curR = 1.0; var curI = 0.0
                for (k in 0 until len / 2) {
                    val aR = re[i + k]; val aI = im[i + k]
                    val bR = re[i + k + len / 2] * curR - im[i + k + len / 2] * curI
                    val bI = re[i + k + len / 2] * curI + im[i + k + len / 2] * curR
                    re[i + k] = aR + bR; im[i + k] = aI + bI
                    re[i + k + len / 2] = aR - bR; im[i + k + len / 2] = aI - bI
                    val tR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = tR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
