package com.tvceviri.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class SubtitleOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var subtitleView: TextView
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private lateinit var translator: Translator

    private var screenWidth = 1920
    private var screenHeight = 1080
    private var screenDensity = 320

    private var lastProcessedTime = 0L
    private var isProcessing = false
    private var lastDetectedText = ""

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        setupDisplayMetrics()
        setupOverlay()
        setupTranslator()

        backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            startProjection(resultCode, data)
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "tv_ocr_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "TV Canlı OCR Çeviri", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val notification = Notification.Builder(this, channelId)
                .setContentTitle("TV Canlı OCR Çeviri Aktif")
                .setContentText("Altyazı bölgesi taranıyor...")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
        }
    }

    private fun setupDisplayMetrics() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun setupOverlay() {
        subtitleView = TextView(this).apply {
            text = ""
            textSize = 24f
            setTextColor(Color.YELLOW)
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(40, 16, 40, 16)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 45
        }

        windowManager.addView(subtitleView, params)
    }

    private fun setupTranslator() {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        translator = Translation.getClient(options)
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SubtitleCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            backgroundHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = System.currentTimeMillis()

            // Saniyede ~2.5 kare işleyerek TV işlemcisini yormadan akıcı takip sağlar
            if (isProcessing || now - lastProcessedTime < 400) {
                image.close()
                return@setOnImageAvailableListener
            }

            isProcessing = true
            lastProcessedTime = now

            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val fullBitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)
                image.close()

                // Görüntünün en alt %18'lik altyazı bölgesini kırp
                val cropHeight = (screenHeight * 0.18).toInt()
                val cropY = screenHeight - cropHeight
                val croppedBitmap = Bitmap.createBitmap(fullBitmap, 0, cropY, screenWidth, cropHeight)
                fullBitmap.recycle()

                processOcr(croppedBitmap)
            } catch (e: Exception) {
                image.close()
                isProcessing = false
            }
        }, backgroundHandler)
    }

    private fun processOcr(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val rawText = visionText.text.replace("\n", " ").trim()
                if (rawText.length < 2) {
                    lastDetectedText = ""
                    mainHandler.post { subtitleView.visibility = View.GONE }
                    bitmap.recycle()
                    isProcessing = false
                    return@addOnSuccessListener
                }

                // Aynı altyazı ekrandaysa tekrar çeviri yapma
                if (rawText.equals(lastDetectedText, ignoreCase = true)) {
                    bitmap.recycle()
                    isProcessing = false
                    return@addOnSuccessListener
                }

                lastDetectedText = rawText
                translateText(rawText, bitmap)
            }
            .addOnFailureListener {
                bitmap.recycle()
                isProcessing = false
            }
    }

    private fun translateText(englishText: String, bitmap: Bitmap) {
        translator.translate(englishText)
            .addOnSuccessListener { turkishText ->
                mainHandler.post {
                    subtitleView.text = turkishText
                    subtitleView.visibility = View.VISIBLE
                }
                bitmap.recycle()
                isProcessing = false
            }
            .addOnFailureListener {
                bitmap.recycle()
                isProcessing = false
            }
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        recognizer.close()
        translator.close()
        if (::backgroundThread.isInitialized) {
            backgroundThread.quitSafely()
        }
