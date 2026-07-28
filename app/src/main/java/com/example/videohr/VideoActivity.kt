package com.example.videohr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 相册视频模式：逐帧解码 → 人脸检测 → 每人提取颜色信号 → 离线估算心率。
 * 结果按人列出 BPM、置信度与脉搏波形。
 */
class VideoActivity : AppCompatActivity() {

    private data class Summary(
        val id: Int,
        val bpm: Double,
        val snr: Double,
        val frames: Int,
        val waveform: FloatArray?,
    )

    private lateinit var pickBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var resultsContainer: LinearLayout

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.1f)
                .build()
        )
    }

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { process(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)
        pickBtn = findViewById(R.id.pickBtn)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        resultsContainer = findViewById(R.id.resultsContainer)
        pickBtn.setOnClickListener { pickVideo.launch("video/*") }
    }

    private fun process(uri: Uri) {
        pickBtn.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        resultsContainer.removeAllViews()
        statusText.text = "开始分析…"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { runAnalysis(uri) }
            progressBar.visibility = View.GONE
            pickBtn.isEnabled = true
            renderResults(result)
        }
    }

    private suspend fun runAnalysis(uri: Uri): List<Summary> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { statusText.text = "无法读取视频：${e.message}" }
            retriever.release()
            return emptyList()
        }

        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        if (durationMs <= 0) {
            retriever.release()
            return emptyList()
        }
        val fpsMeta = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
        val fps = if (fpsMeta != null && fpsMeta in 10f..120f) fpsMeta else 30f
        val stepUs = (1_000_000f / fps).toLong()

        val tracker = FacePulseTracker()
        var tUs = 0L
        var frame = 0
        while (tUs < durationMs * 1000) {
            val bmp = try {
                retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (e: Exception) {
                null
            } ?: break

            val nowMs = tUs / 1000
            try {
                val faces = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)))
                val boxes = faces.map { RectF(it.boundingBox) }
                val tracks = tracker.update(boxes, nowMs) { roi -> meanRgb(bmp, roi) }
                val interval = fps.toInt().coerceAtLeast(15)
                for (tr in tracks) {
                    if (tr.sinceCompute >= interval) tracker.recompute(tr)
                }
            } catch (_: Exception) {
                // 单帧检测失败，跳过
            }
            bmp.recycle()

            frame++
            if (frame % 5 == 0) {
                val pct = (100 * tUs / (durationMs * 1000)).toInt().coerceIn(0, 100)
                withContext(Dispatchers.Main) {
                    progressBar.progress = pct
                    statusText.text = "分析中… $pct%（第 $frame 帧，已跟踪 ${tracker.tracks.size} 人）"
                }
            }
            tUs += stepUs
        }
        retriever.release()

        tracker.tracks.forEach { tracker.recompute(it) }
        return tracker.tracks
            .filter { it.durationMs >= 5_000L }
            .sortedBy { it.id }
            .map { Summary(it.id, it.bpm, it.snr, it.ts.size, it.waveform) }
    }

    private fun meanRgb(bmp: Bitmap, roi: RectF): FloatArray? {
        val l = roi.left.roundToInt().coerceIn(0, bmp.width - 2)
        val t = roi.top.roundToInt().coerceIn(0, bmp.height - 2)
        val w = (roi.right.roundToInt() - l).coerceIn(1, bmp.width - l)
        val h = (roi.bottom.roundToInt() - t).coerceIn(1, bmp.height - t)
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        return try {
            bmp.getPixels(pixels, 0, w, l, t, w, h)
            var r = 0L; var g = 0L; var b = 0L
            for (p in pixels) {
                r += Color.red(p); g += Color.green(p); b += Color.blue(p)
            }
            val n = pixels.size.toFloat()
            floatArrayOf(r / n, g / n, b / n)
        } catch (e: Exception) {
            null
        }
    }

    private fun renderResults(results: List<Summary>) {
        if (results.isEmpty()) {
            statusText.text = "未检测到持续可见的人脸（需正脸持续出现 5 秒以上）"
            return
        }
        statusText.text = "分析完成，检测到 ${results.size} 人（仅供参考，非医疗诊断）"
        for (s in results) {
            val color = OverlayView.colorFor(s.id)
            val tv = TextView(this).apply {
                text = if (s.bpm.isNaN()) {
                    "人物 ${s.id}：信号不足，无法估算"
                } else {
                    "人物 ${s.id}：${s.bpm.roundToInt()} BPM（${MainActivity.confidence(s.snr)}，${s.frames} 帧）"
                }
                textSize = 18f
                setTextColor(color)
                setPadding(0, 24, 0, 8)
            }
            resultsContainer.addView(tv)

            s.waveform?.let { wave ->
                val wv = WaveformView(this).apply {
                    lineColor = color
                    data = wave
                }
                resultsContainer.addView(
                    wv,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (120 * resources.displayMetrics.density).toInt()
                    )
                )
            }
        }
    }
}
