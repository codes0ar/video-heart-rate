package com.example.videohr

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 多人脸跟踪 + 每人一条颜色信号缓冲。
 * 帧间按人脸框中心距离匹配；每张人脸维护最多 windowMs 毫秒的 RGB 采样，
 * 由外部定时调用 recompute() 更新心率、紧张指数与活体判断。
 */
class FacePulseTracker(
    private val maxTracks: Int = 4,
    private val windowMs: Long = 12_000L,
    private val staleMs: Long = 1_200L,
) {

    /** 一帧中一张人脸的检测信息；eyeOpen 为双眼睁开概率均值（无该能力时 null） */
    data class FaceInfo(val box: RectF, val eyeOpen: Float? = null)

    inner class Track(val id: Int) {
        var box = RectF()
        var lastSeen = 0L
        val ts = ArrayList<Long>()
        val r = ArrayList<Float>()
        val g = ArrayList<Float>()
        val b = ArrayList<Float>()
        var bpm = Double.NaN        // CHROM（EMA 平滑后）
        var posBpm = Double.NaN     // POS（EMA 平滑后）
        var snr = 0.0
        var waveform: FloatArray? = null
        var lastResult: SignalProcessor.PulseResult? = null
        var lastBrightness = Float.NaN
        var sinceCompute = 0
        val bpmHistory = ArrayList<Pair<Long, Double>>()    // (时间戳, CHROM bpm)
        val posHistory = ArrayList<Pair<Long, Double>>()    // (时间戳, POS bpm)
        var stressScore = Double.NaN    // 紧张指数 0..100
        var stressLevel = ""
        var rmssdMs = Double.NaN

        // ---- 活体判断（真人 / 照片 / 视频回放）----
        var liveness = ""               // 判断结果标题
        var livenessDebug = ""          // 判断依据（debug 显示）
        var texEma = Double.NaN         // 屏幕纹理 EMA（mean|Laplacian|）
        var blinkCount = 0              // 观察到的眨眼次数
        var eyeClosed = false
        val motionHist = ArrayList<FloatArray>() // [t, cx, cy, w] 最近 10 秒

        val durationMs: Long
            get() = if (ts.size < 2) 0L else ts.last() - ts.first()
    }

    val tracks = ArrayList<Track>()
    private var nextId = 1

    /**
     * @param faces 当前帧检测到的人脸（框 + 可选睁眼概率）
     * @param nowMs 当前帧时间戳（毫秒）
     * @param sampler 给定 ROI（同一坐标系）返回 [r,g,b] 或 [r,g,b,纹理]，取不到返回 null
     */
    fun update(faces: List<FaceInfo>, nowMs: Long, sampler: (RectF) -> FloatArray?): List<Track> {
        val used = HashSet<Track>()
        for (f in faces) {
            val best = tracks.filter { it !in used }.minByOrNull { centerDist(it.box, f.box) }
            val threshold = max(f.box.width(), f.box.height()) * 0.6f +
                (best?.let { max(it.box.width(), it.box.height()) } ?: 0f) * 0.4f
            val t = when {
                best != null && centerDist(best.box, f.box) < threshold -> best
                tracks.size < maxTracks -> Track(nextId++).also { tracks.add(it) }
                else -> null
            } ?: continue

            used.add(t)
            if (t.box.width() > 0f) {
                // 轻微平滑，抑制检测框抖动
                t.box.set(
                    lerp(t.box.left, f.box.left, 0.5f), lerp(t.box.top, f.box.top, 0.5f),
                    lerp(t.box.right, f.box.right, 0.5f), lerp(t.box.bottom, f.box.bottom, 0.5f)
                )
            } else {
                t.box.set(f.box)
            }
            t.lastSeen = nowMs

            // 眨眼检测（闭眼→重新睁开记一次）
            f.eyeOpen?.let { p ->
                if (!t.eyeClosed && p < 0.3f) {
                    t.eyeClosed = true
                } else if (t.eyeClosed && p > 0.7f) {
                    t.eyeClosed = false
                    t.blinkCount++
                }
            }

            // 微运动历史（10 秒窗口）
            t.motionHist.add(floatArrayOf(nowMs.toFloat(), t.box.centerX(), t.box.centerY(), t.box.width()))
            while (t.motionHist.size > 2 && t.motionHist.first()[0] < nowMs - 10_000L) {
                t.motionHist.removeAt(0)
            }

            val rgb = sampler(innerRoi(t.box))
            if (rgb != null) {
                t.ts.add(nowMs); t.r.add(rgb[0]); t.g.add(rgb[1]); t.b.add(rgb[2])
                t.lastBrightness = (rgb[0] + rgb[1] + rgb[2]) / 3f
                if (rgb.size >= 4) {
                    val tex = rgb[3].toDouble()
                    t.texEma = if (t.texEma.isNaN()) tex else 0.9 * t.texEma + 0.1 * tex
                }
                while (t.ts.size > 2 && t.ts.first() < nowMs - windowMs) {
                    t.ts.removeAt(0); t.r.removeAt(0); t.g.removeAt(0); t.b.removeAt(0)
                }
                t.sinceCompute++
            }
        }
        tracks.removeAll { it !in used && nowMs - it.lastSeen > staleMs }
        return tracks
    }

    fun recompute(t: Track) {
        t.sinceCompute = 0
        if (t.durationMs < 5_000L || t.ts.size < 40) return
        val res = SignalProcessor.compute(t.r, t.g, t.b, t.ts) ?: return
        t.bpm = ema(t.bpm, res.chrom.bpm)
        t.posBpm = ema(t.posBpm, res.pos.bpm)
        t.snr = res.chrom.snr
        t.waveform = res.chrom.waveform
        t.lastResult = res
        // BPM 历史（画"脑门曲线"用，保留最近 25 秒）
        val now = t.ts.last()
        t.bpmHistory.add(now to t.bpm)
        t.posHistory.add(now to t.posBpm)
        while (t.bpmHistory.size > 2 && t.bpmHistory.first().first < now - 25_000L) {
            t.bpmHistory.removeAt(0)
        }
        while (t.posHistory.size > 2 && t.posHistory.first().first < now - 25_000L) {
            t.posHistory.removeAt(0)
        }

        // 紧张指数：基线取历史 BPM 的 15 分位（会话内个人静息估计）
        if (t.bpmHistory.size >= 3) {
            val sorted = t.bpmHistory.map { it.second }.sorted()
            val baseline = sorted[(sorted.size * 0.15).toInt().coerceIn(0, sorted.size - 1)]
            val stress = StressEstimator.estimate(t.bpm, baseline, res.chrom.waveform, res.fps)
            t.stressScore = if (t.stressScore.isNaN()) stress.score
            else 0.5 * t.stressScore + 0.5 * stress.score
            t.stressLevel = StressEstimator.levelOf(t.stressScore)
            t.rmssdMs = stress.rmssdMs ?: Double.NaN
        }

        updateLiveness(t)
    }

    /**
     * 活体判断：
     *  - 有脉搏（SNR 达标 + 绿通道主导）且屏幕纹理高 → 疑似视频回放
     *  - 有脉搏且纹理正常 → 真人
     *  - 无脉搏 + 无眨眼 + 画面几乎静止 → 这是照片吗？
     *  - 无脉搏 + 无眨眼 → 疑似照片
     */
    private fun updateLiveness(t: Track) {
        val res = t.lastResult ?: return
        if (t.durationMs < 10_000L) {
            t.liveness = "识别中…"
            return
        }
        val c = res.chrom
        val pulseOk = c.snr > 0.30 &&
            res.channelPulse[0] < 0.85f && res.channelPulse[2] < 0.95f
        val texHigh = !t.texEma.isNaN() && t.texEma > TEXTURE_HIGH
        val mot = motionStd(t)
        val veryStatic = mot != null && mot < 0.004

        t.liveness = when {
            pulseOk && texHigh -> "疑似视频回放"
            pulseOk -> "真人"
            t.blinkCount == 0 && veryStatic -> "这是照片吗？"
            t.blinkCount == 0 -> "疑似照片"
            else -> "真人？" // 有眨眼但脉搏信号弱：多半是真人在运动/光线差
        }
        t.livenessDebug =
            "pulse=$pulseOk tex=%.1f(%s) blink=${t.blinkCount} mot=%s".format(
                t.texEma,
                if (texHigh) "高" else "低",
                if (mot == null) "--" else "%.4f".format(mot)
            )
    }

    /** 人脸框中心相对微运动（标准差 / 脸宽），真人通常 0.4%~3%，静止屏幕照片 ≈0 */
    private fun motionStd(t: Track): Double? {
        val h = t.motionHist
        if (h.size < 10) return null
        fun stdOf(sel: (FloatArray) -> Float): Double {
            var mean = 0.0
            for (e in h) mean += sel(e)
            mean /= h.size
            var acc = 0.0
            for (e in h) { val d = sel(e) - mean; acc += d * d }
            return sqrt(acc / h.size)
        }
        return (stdOf { it[1] / it[3] } + stdOf { it[2] / it[3] }) / 2.0
    }

    private fun ema(prev: Double, new: Double): Double =
        if (prev.isNaN() || abs(prev - new) > 12.0) new else 0.65 * prev + 0.35 * new

    private fun centerDist(a: RectF, b: RectF): Float {
        val dx = a.centerX() - b.centerX()
        val dy = a.centerY() - b.centerY()
        return sqrt(dx * dx + dy * dy)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        /** 屏幕纹理阈值（mean|Laplacian|，Y 0-255 尺度），超过则怀疑屏幕翻拍/回放 */
        const val TEXTURE_HIGH = 18.0

        /** 面部中心 ROI：避开边缘、头发与嘴部，取额头+双颊上部 */
        fun innerRoi(f: RectF): RectF {
            val w = f.width()
            val h = f.height()
            return RectF(
                f.left + 0.22f * w,
                f.top + 0.08f * h,
                f.right - 0.22f * w,
                f.top + 0.62f * h
            )
        }
    }
}
