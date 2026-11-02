package com.eyescroll.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
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
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EyeTrackingService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "com.eyescroll.app.STOP"
        private const val CHANNEL = "eyescroll"
        private const val TAG = "EyeScroll"
        private const val HOLD_MS = 350L
        private const val COOLDOWN_MS = 1200L
    }

    private var landmarker: FaceLandmarker? = null
    private lateinit var executor: ExecutorService

    // gesture state (only touched on the analysis thread)
    private var calActive = false
    private var calSumD = 0f
    private var calSumU = 0f
    private var calN = 0
    private var downSince = 0L
    private var upSince = 0L
    private var lastFire = 0L
    private var armed = true

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, 1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
        if (!EyeState.running) {
            try {
                createLandmarker()
                startCamera()
                EyeState.running = true
                EyeState.error = null
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                EyeState.error = "Could not start: ${e.message}"
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Eye tracking", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, EyeTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("EyeScroll is watching your eyes")
            .setContentText("Tap to open, or press Stop")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    private fun createLandmarker() {
        val base = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .build()
        landmarker = FaceLandmarker.createFromOptions(this, opts)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor) { analyze(it) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "camera failed", e)
                EyeState.error = "Camera failed: ${e.message}"
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val lm = landmarker ?: return
            val bmp = proxy.toBitmap()
            val matrix = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
            val rotated = android.graphics.Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height, matrix, true
            )
            val result = lm.detectForVideo(
                BitmapImageBuilder(rotated).build(), SystemClock.uptimeMillis()
            )
            handle(result)
        } catch (e: Exception) {
            Log.e(TAG, "analyze failed", e)
        } finally {
            proxy.close()
        }
    }

    private fun handle(result: FaceLandmarkerResult) {
        val shapes = result.faceBlendshapes().orElse(null)?.firstOrNull()
        if (shapes == null) {
            EyeState.faceFound = false
            downSince = 0; upSince = 0; armed = true
            return
        }
        EyeState.faceFound = true
        val map = HashMap<String, Float>()
        for (c in shapes) map[c.categoryName()] = c.score()
        val down = ((map["eyeLookDownLeft"] ?: 0f) + (map["eyeLookDownRight"] ?: 0f)) / 2f
        val up = ((map["eyeLookUpLeft"] ?: 0f) + (map["eyeLookUpRight"] ?: 0f)) / 2f
        EyeState.down = down
        EyeState.up = up
        process(down, up, SystemClock.uptimeMillis())
    }

    private fun process(down: Float, up: Float, now: Long) {
        // Calibration: average the "neutral" look for a moment
        if (now < EyeState.calibrateUntil) {
            if (!calActive) { calActive = true; calSumD = 0f; calSumU = 0f; calN = 0 }
            calSumD += down; calSumU += up; calN++
            EyeState.lastAction = "Calibrating..."
            return
        }
        if (calActive) {
            calActive = false
            if (calN > 0) {
                EyeState.baseDown = calSumD / calN
                EyeState.baseUp = calSumU / calN
            }
            EyeState.lastAction = "Calibrated"
        }

        val thr = Prefs.threshold(this)
        val dDown = down - EyeState.baseDown
        val dUp = up - EyeState.baseUp
        when {
            dDown > thr && dDown >= dUp -> {
                upSince = 0
                if (downSince == 0L) downSince = now
                tryFire(true, downSince, now)
            }
            dUp > thr -> {
                downSince = 0
                if (upSince == 0L) upSince = now
                tryFire(false, upSince, now)
            }
            else -> {
                downSince = 0; upSince = 0; armed = true
            }
        }
    }

    private fun tryFire(next: Boolean, since: Long, now: Long) {
        if (!armed || now - since < HOLD_MS || now - lastFire < COOLDOWN_MS) return
        armed = false
        lastFire = now
        val svc = ScrollAccessibilityService.instance
        EyeState.lastAction = svc?.scroll(next) ?: "Accessibility service is OFF"
    }

    override fun onDestroy() {
        EyeState.running = false
        EyeState.faceFound = false
        try { landmarker?.close() } catch (_: Exception) {}
        landmarker = null
        executor.shutdown()
        super.onDestroy()
    }
}
