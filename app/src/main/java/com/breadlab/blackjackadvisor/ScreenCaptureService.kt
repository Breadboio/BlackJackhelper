package com.breadlab.blackjackadvisor

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_CARDS_DETECTED = "com.breadlab.blackjackadvisor.CARDS_DETECTED"
        const val EXTRA_PLAYER_CARDS = "player_cards"
        const val EXTRA_DEALER_CARD = "dealer_card"
        const val EXTRA_FRAME_COUNT = "frame_count"
        const val EXTRA_RAW_DEALER = "raw_dealer"
        const val EXTRA_RAW_PLAYER = "raw_player"
        const val EXTRA_BALANCE = "balance"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "bja_capture"
        private const val NOTIFICATION_ID = 2
        private const val CAPTURE_INTERVAL_MS = 1500L

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val cardDetector = CardDetector(this)
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var frameCount = 0

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                captureAndAnalyze()
                handler.postDelayed(this, CAPTURE_INTERVAL_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return START_NOT_STICKY

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = try {
            projectionManager.getMediaProjection(resultCode, resultData).also { mp ->
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { isRunning = false; handler.removeCallbacks(captureRunnable) }
                }, null)
            }
        } catch (e: SecurityException) {
            stopSelf(); return START_NOT_STICKY
        }

        setupVirtualDisplay()
        isRunning = true
        handler.postDelayed(captureRunnable, 500)
        return START_STICKY
    }

    private fun setupVirtualDisplay() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "BlackjackCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun captureAndAnalyze() {
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val rawBitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(plane.buffer)

            // Crop away the row padding from the surface buffer. We used to also
            // downscale to half-res for speed, but that lost too much detail on small
            // card pips (Stake's "9" and "4" went undetected). Full-res OCR is slower
            // but still well under the 1.5s capture interval, and accuracy matters more.
            val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
            rawBitmap.recycle()

            cardDetector.detectCards(cropped) { detected ->
                cropped.recycle()
                frameCount++
                broadcastDetectedCards(detected ?: DetectedCards(emptyList(), 0))
            }
        } catch (e: Exception) {
            // ignore transient capture errors
        } finally {
            image.close()
        }
    }

    private fun broadcastDetectedCards(detected: DetectedCards) {
        val intent = Intent(ACTION_CARDS_DETECTED).apply {
            putIntegerArrayListExtra(EXTRA_PLAYER_CARDS, ArrayList(detected.playerCards))
            putExtra(EXTRA_DEALER_CARD, detected.dealerCard)
            putExtra(EXTRA_FRAME_COUNT, frameCount)
            putStringArrayListExtra(EXTRA_RAW_DEALER, ArrayList(detected.rawDealerZone))
            putStringArrayListExtra(EXTRA_RAW_PLAYER, ArrayList(detected.rawPlayerZone))
            // -1.0 sentinel = no balance detected this frame.
            putExtra(EXTRA_BALANCE, detected.balance ?: -1.0)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Card Scanner", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Scanning screen for blackjack cards" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Blackjack Advisor")
            .setContentText("Scanning for cards…")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(captureRunnable)
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        cardDetector.close()
    }
}
