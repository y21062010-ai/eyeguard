package com.example.eyeguard

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val REQ_CAPTURE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val title = TextView(this).apply {
            text = "EyeGuard"
            textSize = 28f
        }

        val info = TextView(this).apply {
            text = "1) اسمح بالعرض فوق التطبيقات\n2) اضغط تشغيل واسمح بتسجيل الشاشة"
            textSize = 16f
            setPadding(0, 32, 0, 32)
        }

        val overlayBtn = Button(this).apply {
            text = "1) السماح بالعرض فوق التطبيقات"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        val startBtn = Button(this).apply {
            text = "2) تشغيل الحماية"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(
                        this@MainActivity,
                        "اسمح بالعرض فوق التطبيقات أولاً",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1
                    )
                }
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
            }
        }

        val stopBtn = Button(this).apply {
            text = "إيقاف الحماية"
            setOnClickListener {
                stopService(Intent(this@MainActivity, GuardService::class.java))
            }
        }

        layout.addView(title)
        layout.addView(info)
        layout.addView(overlayBtn)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        setContentView(layout)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, GuardService::class.java).apply {
                putExtra("code", resultCode)
                putExtra("data", data)
            }
            startForegroundService(svc)
        }
    }
}
