package com.example.airgesture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.Executors

/** Foreground service: front camera -> MediaPipe (offline) -> classifier -> GestureControlService. */
class AirGestureCameraService : LifecycleService() {

    private var landmarker: HandLandmarker? = null
    private var provider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val classifier = AdvancedGestureClassifier()

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        landmarker = HandLandmarker.createFromOptions(
            this,
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.6f)
                .setMinHandPresenceConfidence(0.6f)
                .setMinTrackingConfidence(0.6f)
                .build()
        )
        startCamera()
    }

    private fun startAsForeground() {
        val channelId = "air_gesture"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Air Gesture", NotificationManager.IMPORTANCE_LOW)
        )
        val n = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Air Gesture running")
            .setContentText("Camera is tracking your hand (offline)")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }

    @Suppress("DEPRECATION")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = future.get()
            provider = p
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { process(it) }
            p.unbindAll()
            p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun process(proxy: ImageProxy) {
        try {
            val lm = landmarker ?: return
            val src = proxy.toBitmap()
            // rotate upright, then mirror so "your right" = +x
            val m = Matrix().apply {
                postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                postScale(-1f, 1f)
            }
            val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            val now = SystemClock.uptimeMillis()
            val result = lm.detectForVideo(BitmapImageBuilder(bmp).build(), now)
            val pts = result.landmarks().firstOrNull()?.map { P(it.x(), it.y()) }
            classifier.update(pts, now)?.let { GestureControlService.instance?.handle(it) }
        } catch (e: Exception) {
            Log.e("AirGesture", "frame failed", e)
        } finally {
            proxy.close()
        }
    }

    override fun onDestroy() {
        provider?.unbindAll()
        landmarker?.close()
        executor.shutdown()
        super.onDestroy()
    }
}
