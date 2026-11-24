package com.tvceviri.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(60, 60, 60, 60)
        }

        val title = TextView(this).apply {
            text = "PS5 / TV Anlık Altyazı Çeviri"
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
            text = "ÇEVİRİYİ BAŞLAT (OYUN ÜSTÜ)"
            textSize = 18f
            isFocusable = true
            requestFocus()
            setOnClickListener { checkPermissionsAndStart() }
        }

        val btnStop = Button(this).apply {
            text = "ÇEVİRİYİ DURDUR"
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

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Lütfen 'Diğer uygulamaların üzerinde göster' iznini açın", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }

        val serviceIntent = Intent(this, SubtitleOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        statusText.text = "Durum: Altyazı katmanı aktif! HDMI girişine geçebilirsiniz."
        moveTaskToBack(true)
    }
}
