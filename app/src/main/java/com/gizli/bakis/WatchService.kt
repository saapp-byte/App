package com.gizli.bakis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WatchService : LifecycleService() {

    companion object {
        const val PREFS = "ayar"
        const val KEY_NOTES = "notes"
        const val KEY_REELS = "reels"
        const val KEY_SENS = "sens"
        @Volatile var running = false

        // Kaç ardışık karede karar verilsin (düşük = daha hızlı, yüksek = daha kararlı)
        private const val FRAMES_TO_SHOW = 2
        private const val FRAMES_TO_RETURN = 5
    }

    private lateinit var detector: FaceDetector
    private lateinit var executor: ExecutorService
    private lateinit var pm: PowerManager

    private var showingNotes = false
    private var multiCount = 0
    private var singleCount = 0

    override fun onCreate() {
        super.onCreate()
        running = true
        pm = getSystemService(POWER_SERVICE) as PowerManager
        executor = Executors.newSingleThreadExecutor()

        val sens = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_SENS, 12)
        detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(sens / 100f)
                .build()
        )

        startAsForeground()
        startCamera()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("watch", "Gizli Bakış", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WatchService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "watch")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Gizli Bakış çalışıyor")
            .setContentText("Ön kamera yüz sayısını izliyor")
            .setContentIntent(open)
            .addAction(0, "Durdur", stop)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
    }

    @Suppress("DEPRECATION")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { analyze(it) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { onFaces(it.size) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun onFaces(count: Int) {
        if (!pm.isInteractive) { // ekran kapalıysa hiçbir şey yapma
            multiCount = 0; singleCount = 0
            return
        }
        if (count >= 2) { multiCount++; singleCount = 0 }
        else { singleCount++; multiCount = 0 }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!showingNotes && multiCount >= FRAMES_TO_SHOW) {
            showingNotes = true
            launch(prefs.getString(KEY_NOTES, null))
        } else if (showingNotes && singleCount >= FRAMES_TO_RETURN) {
            showingNotes = false
            launch(prefs.getString(KEY_REELS, null))
        }
    }

    private fun launch(pkg: String?) {
        if (pkg == null) return
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == "STOP") stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        detector.close()
        executor.shutdown()
        super.onDestroy()
    }
}
