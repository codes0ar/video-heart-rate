package com.example.videohr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 实时模式：CameraX 预览 + ImageAnalysis 逐帧人脸检测，
 * 提取每脸 ROI 颜色信号，滑动窗口估计心率，支持多脸同时显示。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView
    private lateinit var debugText: TextView
    private lateinit var waveContainer: LinearLayout
    private lateinit var torchBtn: Button

    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var torchOn = false

    private val tracker = FacePulseTracker()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.15f)
                .build()
        )
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "需要相机权限才能测量心率", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlayView)
        statusText = findViewById(R.id.statusText)
        debugText = findViewById(R.id.debugText)
        waveContainer = findViewById(R.id.waveContainer)
        torchBtn = findViewById(R.id.torchBtn)
        previewView.keepScreenOn = true

        torchBtn.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            torchBtn.text = if (torchOn) "补光灯·开" else "补光灯"
        }
        findViewById<Button>(R.id.switchBtn).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }
        findViewById<Button>(R.id.videoBtn).setOnClickListener {
            startActivity(Intent(this, VideoActivity::class.java))
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ---- 三指双击：切换调试信息显隐 ----

    private var threeFingerDownAt = 0L
    private var lastThreeFingerTapAt = 0L

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 3) threeFingerDownAt = ev.eventTime
            }
            MotionEvent.ACTION_UP -> {
                if (threeFingerDownAt > 0) {
                    val dur = ev.eventTime - threeFingerDownAt
                    threeFingerDownAt = 0
                    if (dur < 400) {
                        val now = ev.eventTime
                        if (now - lastThreeFingerTapAt < 700) {
                            lastThreeFingerTapAt = 0
                            toggleDebug()
                        } else {
                            lastThreeFingerTapAt = now
                        }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun toggleDebug() {
        debugVisible = !debugVisible
        if (!debugVisible) {
            debugText.visibility = View.GONE
            waveContainer.visibility = View.GONE
        }
        Toast.makeText(
            this,
            if (debugVisible) "调试信息：显示" else "调试信息：隐藏",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }

            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this,
                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                preview,
                analysis
            )
            camera?.cameraControl?.enableTorch(torchOn)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val nowMs = proxy.imageInfo.timestamp / 1_000_000
        val bufW = proxy.width
        val bufH = proxy.height

        detector.process(InputImage.fromMediaImage(mediaImage, rotation))
            .addOnSuccessListener(analysisExecutor) { faces ->
                try {
                    val boxes = faces.map { RectF(it.boundingBox) }
                    val tracks = tracker.update(boxes, nowMs) { roiUpright ->
                        val roiBuf = CoordinateMap.uprightToBuffer(roiUpright, rotation, bufW, bufH)
                        YuvRgb.meanRgb(proxy, roiBuf)
                    }
                    for (t in tracks) {
                        if (t.sinceCompute >= 30) tracker.recompute(t)
                    }
                    runOnUiThread { render(tracks, rotation, bufW, bufH) }
                } finally {
                    proxy.close()
                }
            }
            .addOnFailureListener(analysisExecutor) {
                proxy.close()
            }
    }

    private fun render(
        tracks: List<FacePulseTracker.Track>,
        rotation: Int,
        bufW: Int,
        bufH: Int,
    ) {
        val items = tracks.map { t ->
            val mapped = mapToView(t.box, rotation, bufW, bufH)
            val label = if (t.bpm.isNaN()) "P${t.id} 测量中…"
            else {
                val stress = if (t.stressLevel.isNotEmpty()) " ${t.stressLevel}" else ""
                "P${t.id} C:${t.bpm.roundToInt()} P:${t.posBpm.roundToInt()}$stress"
            }
            if (t.bpmHistory.size >= 2) {
                val c = FloatArray(t.bpmHistory.size) { t.bpmHistory[it].second.toFloat() }
                val p = FloatArray(t.posHistory.size) { t.posHistory[it].second.toFloat() }
                var mn = Float.MAX_VALUE
                var mx = -Float.MAX_VALUE
                for (v in c) { if (v < mn) mn = v; if (v > mx) mx = v }
                for (v in p) { if (v < mn) mn = v; if (v > mx) mx = v }
                mn -= 5f; mx += 5f
                if (mx - mn < 20f) { val mid = (mx + mn) / 2f; mn = mid - 10f; mx = mid + 10f }
                OverlayView.Item(mapped, label, OverlayView.colorFor(t.id), c, p, mn, mx)
            } else {
                OverlayView.Item(mapped, label, OverlayView.colorFor(t.id))
            }
        }
        overlay.setItems(items)

        statusText.text = when {
            tracks.isEmpty() -> "未检测到人脸"
            tracks.all { it.bpm.isNaN() } -> "检测到 ${tracks.size} 张人脸，信号采集中（约需 8 秒）…"
            else -> tracks.joinToString("   ") { t ->
                if (t.bpm.isNaN()) "P${t.id}: 测量中"
                else {
                    val stress = if (t.stressScore.isNaN()) ""
                    else " ${t.stressLevel}(${t.stressScore.roundToInt()})"
                    "P${t.id}: CHROM ${t.bpm.roundToInt()} / POS ${t.posBpm.roundToInt()}$stress"
                }
            }
        }

        renderDebug(tracks)
    }

    // ---- 调试信息：帮助判断心率是否误判（默认隐藏，三指双击切换）----

    private val waveViews = HashMap<Int, TrackDebugView>()
    private var debugVisible = false

    private fun renderDebug(tracks: List<FacePulseTracker.Track>) {
        if (!debugVisible || tracks.isEmpty()) {
            debugText.visibility = View.GONE
            waveContainer.visibility = View.GONE
            if (!debugVisible) return
            waveContainer.removeAllViews()
            waveViews.clear()
            return
        }
        debugText.visibility = View.VISIBLE
        waveContainer.visibility = View.VISIBLE
        val sb = StringBuilder()
        sb.append("v").append(BuildConfig.VERSION_NAME).append('\n')
        for (t in tracks) {
            val bright = t.lastBrightness
            val brightNote = when {
                bright.isNaN() -> ""
                bright > 235f -> " 过曝!"
                bright < 45f -> " 太暗!"
                else -> ""
            }
            val res = t.lastResult
            if (res == null) {
                sb.append("P${t.id} 采样${t.ts.size}帧 亮=${bright.roundToInt()}$brightNote\n")
            } else {
                val c = res.chrom
                val p = res.pos
                val peaks = c.topPeaks.joinToString(" ") { (f, m) -> "%.2f(%.2f)".format(f, m) }
                sb.append(
                    "P${t.id} C:%.1fHz=%dBPM SNR=%.2f P:%.1fHz=%dBPM SNR=%.2f | fps=%.1f n=%d 亮=%d%s | 峰Hz(C): %s | 通道R%.2f G1 B%.2f | 压力=%.0f(%s) RMSSD=%s\n".format(
                        c.freqHz, c.bpm.roundToInt(), c.snr,
                        p.freqHz, p.bpm.roundToInt(), p.snr,
                        res.fps, res.samples, bright.roundToInt(), brightNote, peaks,
                        res.channelPulse[0], res.channelPulse[2],
                        t.stressScore, t.stressLevel,
                        if (t.rmssdMs.isNaN()) "--" else "%.0fms".format(t.rmssdMs)
                    )
                )
                Log.d(
                    TAG,
                    "P${t.id} chrom=%.1f(%.3fHz,snr=%.2f) pos=%.1f(%.3fHz,snr=%.2f) fps=%.2f n=%d bright=%.0f chR=%.2f chB=%.2f peaks=$peaks".format(
                        c.bpm, c.freqHz, c.snr, p.bpm, p.freqHz, p.snr,
                        res.fps, res.samples, bright,
                        res.channelPulse[0], res.channelPulse[2]
                    )
                )
            }
        }
        debugText.text = sb.toString().trimEnd()

        // 每脸一块调试图表：RGB 颜色曲线 + 提取脉搏波 + 频谱与锁定峰
        val ids = tracks.map { it.id }.toSet()
        val stale = waveViews.keys.filter { it !in ids }
        stale.forEach { id -> waveViews.remove(id)?.let { waveContainer.removeView(it) } }
        for (t in tracks) {
            val dv = waveViews.getOrPut(t.id) {
                TrackDebugView(this).also {
                    waveContainer.addView(
                        it,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (170 * resources.displayMetrics.density).toInt()
                        )
                    )
                }
            }
            val res = t.lastResult
            dv.trackColor = OverlayView.colorFor(t.id)
            dv.title = if (t.bpm.isNaN()) "P${t.id} 采样中… ${t.ts.size}帧"
            else "P${t.id} C:${t.bpm.roundToInt()}BPM P:${t.posBpm.roundToInt()}BPM SNR:%.2f/%.2f".format(
                res?.chrom?.snr ?: 0.0, res?.pos?.snr ?: 0.0
            )
            dv.rgb = Triple(
                FloatArray(t.r.size) { t.r[it] },
                FloatArray(t.g.size) { t.g[it] },
                FloatArray(t.b.size) { t.b[it] },
            )
            dv.pulse = res?.chrom?.waveform
            dv.pulsePos = res?.pos?.waveform
            dv.spectrum = res?.chrom?.spectrum
            dv.spectrumPos = res?.pos?.spectrum
            dv.spectrumLoHz = (res?.chrom?.spectrumLoHz ?: 0.75).toFloat()
            dv.spectrumBinHz = (res?.chrom?.spectrumBinHz ?: 0.01).toFloat()
            dv.peakHz = (res?.chrom?.freqHz ?: 0.0).toFloat()
            dv.peakHzPos = (res?.pos?.freqHz ?: 0.0).toFloat()
            dv.invalidate()
        }
    }

    /** 正立图像坐标 → PreviewView 视图坐标（FILL_CENTER + 前置镜像） */
    private fun mapToView(box: RectF, rotation: Int, bufW: Int, bufH: Int): RectF {
        val pw = previewView.width
        val ph = previewView.height
        if (pw == 0 || ph == 0) return RectF(box)
        val uw = if (rotation == 90 || rotation == 270) bufH else bufW
        val uh = if (rotation == 90 || rotation == 270) bufW else bufH
        val scale = max(pw / uw.toFloat(), ph / uh.toFloat())
        val dx = (pw - uw * scale) / 2f
        val dy = (ph - uh * scale) / 2f
        var l = box.left * scale + dx
        var r = box.right * scale + dx
        val t = box.top * scale + dy
        val b = box.bottom * scale + dy
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            val nl = pw - r
            r = pw - l
            l = nl
        }
        return RectF(l, t, r, b)
    }

    companion object {
        private const val TAG = "Rppg"

        fun confidence(snr: Double): String = when {
            snr > 0.5 -> "信号强"
            snr > 0.25 -> "信号中"
            else -> "信号弱"
        }
    }
}
