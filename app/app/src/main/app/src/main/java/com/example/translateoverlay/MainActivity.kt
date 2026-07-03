package com.example.translateoverlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkPermissionsAndUpdateUi()
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, OverlayTranslateService::class.java).apply {
                    action = OverlayTranslateService.ACTION_START
                    putExtra(OverlayTranslateService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(OverlayTranslateService.EXTRA_RESULT_DATA, result.data)
                }
                startForegroundService(serviceIntent)
                Toast.makeText(this, "تم تفعيل الفقاعة العائمة، اسحبها فوق أي تطبيق واضغط عليها للترجمة", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "تم رفض إذن التقاط الشاشة", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val grantOverlayBtn = findViewById<Button>(R.id.btnGrantOverlay)
        val startBtn = findViewById<Button>(R.id.btnStart)

        grantOverlayBtn.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }

        startBtn.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "لازم تمنح إذن العرض فوق التطبيقات أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        checkPermissionsAndUpdateUi()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndUpdateUi()
    }

    private fun checkPermissionsAndUpdateUi() {
        statusText.text = if (Settings.canDrawOverlays(this)) {
            "✅ إذن النافذة العائمة ممنوح، اضغط 'تشغيل الفقاعة'"
        } else {
            "⚠️ لازم تمنح إذن 'العرض فوق التطبيقات الأخرى' أولاً"
        }
    }
}
