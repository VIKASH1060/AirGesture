package com.example.airgesture

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r[Manifest.permission.CAMERA] == true) startGestures()
            else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            textSize = 15f
            text = "Gestures\n" +
                "• 1 finger: move cursor\n" +
                "• 2 fingers together: tap\n" +
                "• 2 fingers apart + move: swipe\n" +
                "• Fist (hold): pause\n" +
                "• Open palm (hold): play\n" +
                "• Palm sweep left / right: previous / next\n" +
                "• Four fingers (thumb tucked), move up / down: volume\n"
        })
        fun btn(label: String, onClick: () -> Unit) =
            root.addView(Button(this).apply { text = label; setOnClickListener { onClick() } })

        btn("1. Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btn("2. Start gesture control") {
            if (GestureControlService.instance == null) {
                Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_LONG).show()
            } else {
                val perms = mutableListOf(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                permLauncher.launch(perms.toTypedArray())
            }
        }
        btn("Stop") { stopService(Intent(this, AirGestureCameraService::class.java)) }
        setContentView(root)
    }

    // must be started while this activity is visible (Android 14 camera FGS rule)
    private fun startGestures() {
        ContextCompat.startForegroundService(this, Intent(this, AirGestureCameraService::class.java))
    }
}
