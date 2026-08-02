package com.example.videohr

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 紧张指数估计（娱乐向，非医疗/测谎依据）。
 *
 * 方法：个人基线相对法（参考 Samsung 压力测量的个性化思路），
 * 综合两个生理分量：
 *  - 心率相对基线偏移 ΔHR（占 60%）：紧张/兴奋时心率上升
 *  - 心率变异性 RMSSD（占 40%）：紧张时交感兴奋、HRV 下降
 *    （HRV 时域标准见 1996 ESC/NASPE Task Force；分级参照 Garmin 0-100 四档）
 *
 * 分档：0-25 心平气和 / 26-50 稍有慌张 / 51-75 明显紧张 / 76-100 极度紧张
 */
object StressEstimator {

    data class Stress(
        val score: Double,      // 0..100
        val level: String,      // 分档名称
        val deltaBpm: Double,   // 相对基线心率偏移
        val rmssdMs: Double?,   // HRV 指标，取不到为 null
    )

    fun estimate(bpm: Double, baselineBpm: Double, waveform: FloatArray?, fps: Double): Stress {
        val delta = (bpm - baselineBpm).coerceAtLeast(0.0)
        val rmssd = waveform?.let { rmssdMs(it, fps) }

        val hrScore = (delta / 20.0).coerceIn(0.0, 1.0) // Δ20 BPM 记满
        val score = if (rmssd != null) {
            val hrvScore = ((50.0 - rmssd) / 35.0).coerceIn(0.0, 1.0) // ≤15ms 记满，≥50ms 记 0
            hrScore * 60.0 + hrvScore * 40.0
        } else {
            hrScore * 100.0 // 无 HRV 数据时仅用心率分量
        }.coerceIn(0.0, 100.0)

        return Stress(score, levelOf(score), delta, rmssd)
    }

    fun levelOf(score: Double): String = when {
        score <= 25.0 -> "心平气和"
        score <= 50.0 -> "稍有慌张"
        score <= 75.0 -> "明显紧张"
        else -> "极度紧张"
    }

    /** 从带通脉搏波检测峰间期（IBI）序列，求 RMSSD（毫秒）；数据不足返回 null */
    fun rmssdMs(w: FloatArray, fps: Double): Double? {
        if (w.size < 16 || fps < 5.0) return null
        var mx = 1e-6f
        for (v in w) mx = maxOf(mx, abs(v))
        val thr = mx * 0.35f
        val minGap = (fps * 0.4).toInt().coerceAtLeast(1) // 上限 150 BPM

        // 局部极大值（带最小间距与幅度阈值）
        val peaks = ArrayList<Int>()
        var i = 1
        while (i < w.size - 1) {
            if (w[i] >= w[i - 1] && w[i] >= w[i + 1] && w[i] > thr) {
                if (peaks.isEmpty() || i - peaks.last() >= minGap) {
                    peaks.add(i)
                    i += minGap
                    continue
                }
            }
            i++
        }
        if (peaks.size < 4) return null

        val ibis = peaks.zipWithNext { a, b -> (b - a) * 1000.0 / fps }
            .filter { it in 350.0..1500.0 } // 40~170 BPM 合理间期
        if (ibis.size < 3) return null

        var acc = 0.0
        var cnt = 0
        for (k in 1 until ibis.size) {
            val d = ibis[k] - ibis[k - 1]
            acc += d * d
            cnt++
        }
        if (cnt == 0) return null
        return sqrt(acc / cnt)
    }
}
