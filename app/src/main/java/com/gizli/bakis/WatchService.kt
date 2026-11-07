package com.gizli.bakis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
        const val KEY_TEXT = "text"
        const val KEY_SENS = "sens"
        const val KEY_MIN = "min"
        const val KEY_DARK = "dark"
        const val IMG_NAME = "not.jpg"

        @Volatile var running = false
        @Volatile var liveFaces = -1
        @Volatile var lastFrameAt = 0L

        // Saniyede ~10 kare işlenir. Bu sayılar ardışık kare sayısıdır.
        private const val FRAMES_TO_SHOW = 2
        private const val FRAMES_TO_HIDE = 4
        private const val MIN_FRAME_GAP_MS = 90L
    }

    private lateinit var detector: FaceDetector
    private lateinit var executor: ExecutorService
    private lateinit var pm: PowerManager
    private val handler = Handler(Looper.getMainLooper())

    private var hit = 0
    private var miss = 0
    private var lastCount = -1
    private var lastInfo = ""
    private var lastProcessed = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            hit = 0
            miss = 0
            Overlay.hide(true)
        }
    }

    // Kamera başka uygulama tarafından alınırsa ya da donarsa kendini yeniden başlatır
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (pm.isInteractive &&
                SystemClock.elapsedRealtime() - lastFrameAt > 5000
            ) {
                liveFaces = -1
                startCamera()
            }
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        liveFaces = -1
        lastFrameAt = SystemClock.elapsedRealtime()
        pm = getSystemService(POWER_SERVICE) as PowerManager
        executor = Executors.newSingleThreadExecutor()

        val sens = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_SENS, 12)
        detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(sens / 100f)
                .build()
        )

        ContextCompat.registerReceiver(
            this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        startAsForeground()
        startCamera()
        handler.postDelayed(watchdog, 4000)
    }

    private fun need() =
        getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_MIN, 2).coerceIn(1, 5)

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, "watch")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Perde çalışıyor")
            .setContentText(text)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                0, "Durdur",
                PendingIntent.getService(
                    this, 1, Intent(this, WatchService::class.java).setAction("STOP"),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("watch", "Perde", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, 1, buildNotification("Eşik: ${need()} yüz"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
    }

    private fun showInfo(text: String) {
        if (text == lastInfo) return
        lastInfo = text
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
    }

    @Suppress("DEPRECATION")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 640))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { analyze(it) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                showInfo("Kamera açılamadı: " + (e.message ?: "bilinmeyen hata"))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        lastFrameAt = now
        val media = proxy.image
        if (media == null || now - lastProcessed < MIN_FRAME_GAP_MS || !pm.isInteractive) {
            proxy.close()
            return
        }
        lastProcessed = now
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { onFaces(it.size) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun onFaces(count: Int) {
        liveFaces = count
        val need = need()
        if (count != lastCount) {
            lastCount = count
            showInfo("Görülen yüz: $count  •  Eşik: $need")
        }

        if (!pm.isInteractive) {
            hit = 0; miss = 0
            if (!Overlay.preview) Overlay.hide(true)
            return
        }
        if (Overlay.preview) { // önizleme sırasında karışma
            hit = 0; miss = 0
            return
        }

        if (count >= need) { hit++; miss = 0 } else { miss++; hit = 0 }

        if (!Overlay.isShowing && hit >= FRAMES_TO_SHOW) {
            Overlay.show(this, false) { showInfo(it) }
        } else if (Overlay.isShowing && miss >= FRAMES_TO_HIDE) {
            Overlay.hide()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == "STOP") stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        liveFaces = -1
        handler.removeCallbacks(watchdog)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        Overlay.hide(true)
        detector.close()
        executor.shutdown()
        super.onDestroy()
    }
}
