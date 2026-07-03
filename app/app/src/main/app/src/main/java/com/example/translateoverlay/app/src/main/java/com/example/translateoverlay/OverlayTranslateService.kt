package com.example.translateoverlay

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class OverlayTranslateService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val NOTIFICATION_CHANNEL_ID = "overlay_translate_channel"
        private const val NOTIFICATION_ID = 1
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var resultView: View? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
    )

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        translator.downloadModelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            startForeground(NOTIFICATION_ID, buildNotification())

            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity_RESULT_CANCELED)
            val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)

            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            if (resultData != null) {
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                setupVirtualDisplay()
            }
            showBubble()
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "مترجم الشاشة", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("مترجم الشاشة شغّال")
            .setContentText("اضغط على الفقاعة العائمة لترجمة أي شيء على الشاشة")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun captureScreenBitmap(): Bitmap? {
        val image: Image = imageReader?.acquireLatestImage() ?: return null
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()
        return Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
    }

    private fun showBubble() {
        if (bubbleView != null) return

        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.overlay_bubble, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = screenWidth - 150
        params.y = screenHeight / 2

        windowManager.addView(bubbleView, params)

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(bubbleView, params)
                    moved = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onBubbleClicked()
                    true
                }
                else -> false
            }
        }
    }

    private fun onBubbleClicked() {
        bubbleView?.postDelayed({
            val bitmap = captureScreenBitmap() ?: return@postDelayed
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val extracted = visionText.text
                    if (extracted.isBlank()) {
                        showResult("لم يتم العثور على نص في الشاشة")
                        return@addOnSuccessListener
                    }
                    translator.translate(extracted)
                        .addOnSuccessListener { translated -> showResult(translated) }
                        .addOnFailureListener { showResult("تعذّرت الترجمة: ${it.message}") }
                }
                .addOnFailureListener { showResult("تعذّرت قراءة النص: ${it.message}") }
        }, 100)
    }

    private fun showResult(text: String) {
        removeResultView()
        val inflater = LayoutInflater.from(this)
        resultView = inflater.inflate(R.layout.overlay_result, null)
        resultView?.findViewById<TextView>(R.id.resultText)?.text = text
        resultView?.findViewById<ImageView>(R.id.closeBtn)?.setOnClickListener {
            removeResultView()
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM
        windowManager.addView(resultView, params)
    }

    private fun removeResultView() {
        resultView?.let {
            windowManager.removeView(it)
            resultView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { windowManager.removeView(it) }
        removeResultView()
        virtualDisplay?.release()
        mediaProjection?.stop()
        recognizer.close()
        translator.close()
    }
}

private const val Activity_RESULT_CANCELED = 0
