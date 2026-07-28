package com.example.videohr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Bundle
import android.widget.Button
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
            else "P${t.id} ${t.bpm.roundToInt()} BPM"
            OverlayView.Item(mapped, label, OverlayView.colorFor(t.id))
        }
        overlay.setItems(items)

        statusText.text = when {
            tracks.isEmpty() -> "未检测到人脸"
            tracks.all { it.bpm.isNaN() } -> "检测到 ${tracks.size} 张人脸，信号采集中（约需 8 秒）…"
            else -> tracks.joinToString("   ") { t ->
                if (t.bpm.isNaN()) "P${t.id}: 测量中" else "P${t.id}: ${t.bpm.roundToInt()} BPM（${confidence(t.snr)}）"
            }
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
        fun confidence(snr: Double): String = when {
            snr > 0.5 -> "信号强"
            snr > 0.25 -> "信号中"
            else -> "信号弱"
        }
    }
}
