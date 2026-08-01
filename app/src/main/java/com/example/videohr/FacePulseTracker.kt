package com.example.videohr

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 多人脸跟踪 + 每人一条颜色信号缓冲。
 * 帧间按人脸框中心距离匹配；每张人脸维护最多 windowMs 毫秒的 RGB 采样，
 * 由外部定时调用 recompute() 更新心率。
 */
class FacePulseTracker(
    private val maxTracks: Int = 4,
    private val windowMs: Long = 12_000L,
    private val staleMs: Long = 1_200L,
) {

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

        val durationMs: Long
            get() = if (ts.size < 2) 0L else ts.last() - ts.first()
    }

    val tracks = ArrayList<Track>()
    private var nextId = 1

    /**
     * @param faces 当前帧检测到的人脸框（任意一致坐标系）
     * @param nowMs 当前帧时间戳（毫秒）
     * @param sampler 给定 ROI（同一坐标系）返回该区域 mean RGB，取不到返回 null
     */
    fun update(faces: List<RectF>, nowMs: Long, sampler: (RectF) -> FloatArray?): List<Track> {
        val used = HashSet<Track>()
        for (f in faces) {
            val best = tracks.filter { it !in used }.minByOrNull { centerDist(it.box, f) }
            val threshold = max(f.width(), f.height()) * 0.6f +
                (best?.let { max(it.box.width(), it.box.height()) } ?: 0f) * 0.4f
            val t = when {
                best != null && centerDist(best.box, f) < threshold -> best
                tracks.size < maxTracks -> Track(nextId++).also { tracks.add(it) }
                else -> null
            } ?: continue

            used.add(t)
            if (t.box.width() > 0f) {
                // 轻微平滑，抑制检测框抖动
                t.box.set(
                    lerp(t.box.left, f.left, 0.5f), lerp(t.box.top, f.top, 0.5f),
                    lerp(t.box.right, f.right, 0.5f), lerp(t.box.bottom, f.bottom, 0.5f)
                )
            } else {
                t.box.set(f)
            }
            t.lastSeen = nowMs

            val rgb = sampler(innerRoi(t.box))
            if (rgb != null) {
                t.ts.add(nowMs); t.r.add(rgb[0]); t.g.add(rgb[1]); t.b.add(rgb[2])
                t.lastBrightness = (rgb[0] + rgb[1] + rgb[2]) / 3f
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
