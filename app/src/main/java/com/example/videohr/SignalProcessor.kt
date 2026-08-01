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
 * rPPG 信号处理，同时输出两种算法的结果用于对比：
 *  - CHROM: de Haan & Jeanne 2013，Xs=3R-2G, Ys=1.5R+G-1.5B, S=Xs-αYs
 *  - POS:   Wang et al. 2017，投影到肤色正交平面 P1=G-B, P2=-2R+G+B, S=P1+αP2
 * 后续管线一致：去趋势 → Hann 窗 FFT → 频带(0.75~3Hz)峰值 → SNR。
 */
object SignalProcessor {

    data class AlgoResult(
        val bpm: Double,
        val snr: Double,
        val waveform: FloatArray, // 带通后的脉搏波形（用于显示）
        val freqHz: Double,       // 锁定峰值频率
        val topPeaks: List<Pair<Double, Double>>, // 频带内前 3 个谱峰：(Hz, 相对幅度)
        val spectrum: FloatArray, // 频带内归一化谱幅度（峰值=1）
        val spectrumLoHz: Double, // spectrum[0] 对应频率
        val spectrumBinHz: Double,// spectrum 相邻点频率间隔
    )

    data class PulseResult(
        val chrom: AlgoResult,
        val pos: AlgoResult,
        val fps: Double,          // 实际采样率
        val samples: Int,         // 参与计算的样本数
        val green: FloatArray,    // 去趋势归一化绿通道（判断原始信号质量）
        val channelPulse: FloatArray, // R/G/B 通道频带内相对脉搏峰强度（G=1）
    )

    private const val MIN_HZ = 0.75 // 45 BPM
    private const val MAX_HZ = 3.0  // 180 BPM

    fun compute(r: List<Float>, g: List<Float>, b: List<Float>, ts: List<Long>): PulseResult? {
        val n0 = ts.size
        if (n0 < 48) return null
        val durMs = (ts.last() - ts.first()).toDouble()
        if (durMs < 4000) return null
        val fps = (n0 - 1) * 1000.0 / durMs
        if (fps < 5.0) return null

        // 按真实时间戳线性插值重采样为均匀序列，消除帧间隔抖动
        val n = n0
        val step = durMs / (n - 1)
        val rs = resample(r, ts, step) ?: return null
        val gs = resample(g, ts, step) ?: return null
        val bs = resample(b, ts, step) ?: return null

        // CHROM 投影
        val xs = DoubleArray(n) { i -> 3.0 * rs[i] - 2.0 * gs[i] }
        val ys = DoubleArray(n) { i -> 1.5 * rs[i] + gs[i] - 1.5 * bs[i] }
        val sy = std(ys)
        if (sy < 1e-9) return null
        val sChrom = DoubleArray(n) { i -> xs[i] - (std(xs) / sy) * ys[i] }

        // POS 投影
        val p1 = DoubleArray(n) { i -> gs[i] - bs[i] }
        val p2 = DoubleArray(n) { i -> -2.0 * rs[i] + gs[i] + bs[i] }
        val sp2 = std(p2)
        if (sp2 < 1e-9) return null
        val sPos = DoubleArray(n) { i -> p1[i] + (std(p1) / sp2) * p2[i] }

        // 公共 FFT 尺寸与频带
        var m = 1
        while (m < n * 2) m = m shl 1
        m = min(m, 8192)
        val binHz = fps / m
        val lo = max(1, (MIN_HZ / binHz).toInt())
        val hi = min(m / 2, (MAX_HZ / binHz).roundToInt() + 1)
        if (hi <= lo) return null

        val chrom = analyze(sChrom, fps, m, lo, hi) ?: return null
        val pos = analyze(sPos, fps, m, lo, hi) ?: return null

        val greenDetrend = detrend(gs)
        val green = FloatArray(n) { i -> greenDetrend[i].toFloat() }

        // 各通道在频带内的相对脉搏峰强度（以 G 为 1，验证哪个通道携带脉搏）
        val chG = bandPeak(greenDetrend, m, lo, hi)
        val channelPulse = if (chG > 1e-12) {
            floatArrayOf(
                (bandPeak(detrend(rs), m, lo, hi) / chG).toFloat(),
                1f,
                (bandPeak(detrend(bs), m, lo, hi) / chG).toFloat()
            )
        } else {
            floatArrayOf(0f, 1f, 0f)
        }

        return PulseResult(chrom, pos, fps, n, green, channelPulse)
    }

    /** 去趋势 → Hann FFT → 频带峰值/SNR/波形/频谱 */
    private fun analyze(sRaw: DoubleArray, fps: Double, m: Int, lo: Int, hi: Int): AlgoResult? {
        val n = sRaw.size
        val s = detrend(sRaw)

        val re = DoubleArray(m)
        val im = DoubleArray(m)
        for (i in 0 until n) {
            val w = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
            re[i] = s[i] * w
        }
        fft(re, im)

        val binHz = fps / m
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

        // 前 3 个局部极大峰（判断是否锁错峰：谐波/运动低频）
        val peaks = ArrayList<Pair<Int, Double>>()
        for (i in (lo + 1) until hi) {
            if (mags[i] >= mags[i - 1] && mags[i] >= mags[i + 1]) {
                peaks.add(i to mags[i])
            }
        }
        val topPeaks = peaks.sortedByDescending { it.second }.take(3)
            .sortedBy { it.first }
            .map { (i, mag) -> (i * binHz) to sqrt(mag / peakMag) }

        // 频带掩码逆 FFT 得到带通脉搏波形（仅供显示）
        val wre = DoubleArray(m)
        val wim = DoubleArray(m)
        for (i in lo..hi) {
            wre[i] = re[i]; wim[i] = im[i]
            wre[m - i] = re[m - i]; wim[m - i] = im[m - i]
        }
        ifft(wre, wim)
        val wave = FloatArray(n) { i -> wre[i].toFloat() }
        val spectrum = FloatArray(hi - lo + 1) { i -> sqrt(mags[lo + i] / peakMag).toFloat() }

        return AlgoResult(freq * 60.0, snr, wave, freq, topPeaks, spectrum, lo * binHz, binHz)
    }

    /** 加 Hann 窗 FFT 后频带内最大谱幅度 */
    private fun bandPeak(x: DoubleArray, m: Int, lo: Int, hi: Int): Double {
        val n = x.size
        val re = DoubleArray(m)
        val im = DoubleArray(m)
        for (i in 0 until n) {
            val w = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
            re[i] = x[i] * w
        }
        fft(re, im)
        var mx = 0.0
        for (i in lo..hi) {
            val p = re[i] * re[i] + im[i] * im[i]
            if (p > mx) mx = p
        }
        return sqrt(mx)
    }

    /** 按时间戳线性插值为均匀采样；通道均值≈0 视为无效返回 null */
    private fun resample(x: List<Float>, ts: List<Long>, stepMs: Double): DoubleArray? {
        val n = ts.size
        var mean = 0.0
        for (v in x) mean += v
        mean /= n
        if (mean < 1e-6) return null
        val t0 = ts.first()
        val out = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            val t = i * stepMs
            while (j < n - 2 && (ts[j + 1] - t0).toDouble() < t) j++
            val ta = (ts[j] - t0).toDouble()
            val tb = (ts[j + 1] - t0).toDouble()
            val w = if (tb > ta) ((t - ta) / (tb - ta)).coerceIn(0.0, 1.0) else 0.0
            val v = x[j] * (1.0 - w) + x[j + 1] * w
            out[i] = v / mean
        }
        return out
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
