package com.breadlab.blackjackadvisor

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 1001
        private const val REQUEST_SCREEN_CAPTURE = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun setupUI() {
        findViewById<Button>(R.id.btn_grant_permission).setOnClickListener {
            requestOverlayPermission()
        }

        findViewById<Button>(R.id.btn_enable_auto_detect).setOnClickListener {
            requestScreenCapture()
        }

        findViewById<Button>(R.id.btn_start_overlay).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                FloatingOverlayService.start(this)
                updateOverlayRunningState(true)
                moveTaskToBack(true)
            } else {
                requestOverlayPermission()
            }
        }

        findViewById<Button>(R.id.btn_stop_overlay).setOnClickListener {
            FloatingOverlayService.stop(this)
            ScreenCaptureService.stop(this)
            updateOverlayRunningState(false)
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
    }

    private fun updatePermissionStatus() {
        val hasPermission = Settings.canDrawOverlays(this)
        val statusText = findViewById<TextView>(R.id.tv_permission_status)
        val grantBtn = findViewById<Button>(R.id.btn_grant_permission)
        val startBtn = findViewById<Button>(R.id.btn_start_overlay)
        val autoDetectSection = findViewById<View>(R.id.auto_detect_section)

        if (hasPermission) {
            statusText.text = "✅ Overlay permission granted"
            statusText.setTextColor(getColor(android.R.color.holo_green_dark))
            grantBtn.visibility = View.GONE
            startBtn.isEnabled = true
            autoDetectSection.visibility = View.VISIBLE
        } else {
            statusText.text = "⚠️ Overlay permission required"
            statusText.setTextColor(getColor(android.R.color.holo_orange_dark))
            grantBtn.visibility = View.VISIBLE
            startBtn.isEnabled = false
            autoDetectSection.visibility = View.GONE
        }
    }

    private fun updateScreenCaptureStatus(granted: Boolean) {
        val statusText = findViewById<TextView>(R.id.tv_auto_detect_status)
        val enableBtn = findViewById<Button>(R.id.btn_enable_auto_detect)

        if (granted) {
            statusText.text = "✅ Auto-detect running — switch to your game"
            statusText.setTextColor(getColor(android.R.color.holo_green_dark))
            enableBtn.text = "Re-grant Capture Permission"
        } else {
            statusText.text = "Screen capture not granted — tap to enable"
            statusText.setTextColor(getColor(android.R.color.darker_gray))
        }
    }

    private fun updateOverlayRunningState(running: Boolean) {
        val startBtn = findViewById<Button>(R.id.btn_start_overlay)
        val stopBtn = findViewById<Button>(R.id.btn_stop_overlay)
        val statusLabel = findViewById<TextView>(R.id.tv_overlay_status)

        startBtn.visibility = if (running) View.GONE else View.VISIBLE
        stopBtn.visibility = if (running) View.VISIBLE else View.GONE
        statusLabel.text = if (running) "🃏 Overlay is active — switch to your game!" else "Overlay stopped"
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> updatePermissionStatus()
            REQUEST_SCREEN_CAPTURE -> {
                val granted = resultCode == Activity.RESULT_OK && data != null
                if (granted) {
                    // Android 14: consent is single-use — consume it NOW, never cache.
                    ScreenCaptureService.start(this, resultCode, data!!)
                }
                updateScreenCaptureStatus(granted)
            }
        }
    }
}
