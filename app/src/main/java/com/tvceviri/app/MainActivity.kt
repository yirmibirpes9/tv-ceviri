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

class MainActivity : Activity() {

    private val SCREEN_CAPTURE_CODE = 1001
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "TV Canlı Çeviri"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            text = "Hazır. Başlatmak için aşağıdaki butona basın."
            textSize = 18f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 36)
        }

        val startBtn = Button(this).apply {
            text = "ÇEVİRİYİ BAŞLAT"
            textSize = 22f
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { checkAndStart() }
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(startBtn)
        setContentView(layout)
        startBtn.requestFocus()
    }

    private fun checkAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            statusText.text = "Lütfen TV Ayarlar > Uygulamalar > Özel erişim > Diğer uygulamaların üzerinde göster iznini açın."
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Ayarlar > Uygulamalar > Özel erişim menüsünden izin verin.", Toast.LENGTH_LONG).show()
            }
            return
        }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), SCREEN_CAPTURE_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_CODE && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, SubtitleOverlayService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            statusText.text = "Çeviri aktif! Şimdi PS5 veya video ekranına geçebilirsiniz."
        }
    }
}
