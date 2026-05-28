package com.openclaw.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream

/**
 * Handles screen capture via MediaProjection API.
 * Captures a single frame and returns it as Base64 JPEG.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCapture"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1002

        // These are set from MainActivity via intent extras
        var mediaProjectionIntent: Intent? = null
        var mediaProjectionResultCode: Int = 0
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var isCapturing = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayDensity: Int = 0
    private var displayWidth: Int = 0
    private var displayHeight: Int = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "ScreenCaptureService created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startCapture()
            "STOP" -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        if (isCapturing) return

        val projectionIntent = mediaProjectionIntent ?: run {
            Log.e(TAG, "No MediaProjection intent available")
            return
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(
            mediaProjectionResultCode,
            projectionIntent
        )

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection creation failed")
            return
        }

        // Get display metrics
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels
        displayDensity = metrics.densityDpi

        // Create ImageReader
        imageReader = ImageReader.newInstance(
            displayWidth,
            displayHeight,
            PixelFormat.RGBA_8888,
            2
        )

        // Create VirtualDisplay
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            displayWidth,
            displayHeight,
            displayDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        isCapturing = true

        // Register with CommandProcessor
        CommandProcessor.getInstance(this).setScreenCaptureService(this)

        // Start foreground notification
        startForeground(NOTIFICATION_ID, buildNotification())

        Log.d(TAG, "Screen capture started: ${displayWidth}x$displayHeight")
    }

    private fun stopCapture() {
        if (!isCapturing) return

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        isCapturing = false

        CommandProcessor.getInstance(this).setScreenCaptureService(null)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.d(TAG, "Screen capture stopped")
    }

    /**
     * Capture a single screenshot as Base64 JPEG.
     * Returns JSON with base64 image data.
     */
    fun captureScreenshot(): String {
        if (!isCapturing || imageReader == null) {
            return org.json.JSONObject().apply {
                put("error", "Screen capture not active")
            }.toString()
        }

        return try {
            val reader = imageReader ?: return org.json.JSONObject().apply {
                put("error", "ImageReader is null")
            }.toString()

            // Acquire latest image
            val image = reader.acquireLatestImage()
            if (image == null) {
                return org.json.JSONObject().apply {
                    put("error", "No image available")
                }.toString()
            }

            // Convert to Bitmap
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * displayWidth

            val bitmap = Bitmap.createBitmap(
                displayWidth + rowPadding / pixelStride,
                displayHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            // Crop to actual width
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, displayWidth, displayHeight)

            // Convert to JPEG Base64
            val outputStream = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            cropped.recycle()
            bitmap.recycle()

            org.json.JSONObject().apply {
                put("success", true)
                put("format", "jpeg")
                put("width", displayWidth)
                put("height", displayHeight)
                put("data", base64)
                put("size", outputStream.size())
            }.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed", e)
            org.json.JSONObject().apply {
                put("error", e.message)
            }.toString()
        }
    }

    fun isCapturing(): Boolean = isCapturing

    // --- Notification ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "OpenClaw screen capture service" }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenClaw Node")
            .setContentText("Screen capture active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
