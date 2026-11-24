package com.tvceviri.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, SubtitleOverlayService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            statusText.text = "Durum: Ekran okuma (OCR) aktif! Oyuna geçebilirsiniz."
            moveTaskToBack(true)
        } else {
            statusText.text = "Durum: Ekran yakalama izni reddedildi."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(60, 60, 60, 60)
        }

        val title = TextView(this).apply {
            text = "PS5 / TV Canlı OCR Altyazı Çeviri"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            text = "Durum: Çeviri dil paketi kontrol ediliyor..."
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 40)
        }

        val btnStart = Button(this).apply {
            text = "EKRAN OKUMAYI BAŞLAT"
            textSize = 18f
            isFocusable = true
            requestFocus()
            setOnClickListener { checkPermissionsAndCapture() }
        }

        val btnStop = Button(this).apply {
            text = "DURDUR"
            textSize = 18f
            isFocusable = true
            setOnClickListener {
                stopService(Intent(this@MainActivity, SubtitleOverlayService::class.java))
                statusText.text = "Durum: Servis durduruldu."
            }
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(btnStart)
        layout.addView(btnStop)
        setContentView(layout)

        prepareTranslator()
    }

    private fun prepareTranslator() {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        val translator = Translation.getClient(options)
        val conditions = DownloadConditions.Builder().build()

        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                statusText.text = "Durum: İngilizce -> Türkçe modeli hazır!"
            }
            .addOnFailureListener { e ->
                statusText.text = "Model indirme hatası: ${e.localizedMessage}"
            }
    }

    private fun checkPermissionsAndCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Lütfen 'Diğer uygulamaların üzerinde göster' iznini açın", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            return
        }

        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }
}
