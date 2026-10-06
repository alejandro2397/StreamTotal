package com.alejandro.streamtotal

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class FloatingControlService : Service() {
    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var statusText: TextView? = null
    private var bitrateText: TextView? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenStreamService.ACTION_STATUS) return
            val status = intent.getStringExtra(ScreenStreamService.EXTRA_STATUS).orEmpty()
            statusText?.text = status.ifBlank { "StreamTotal Gaming" }
            if (intent.hasExtra(ScreenStreamService.EXTRA_BITRATE)) {
                val value = intent.getLongExtra(ScreenStreamService.EXTRA_BITRATE, 0L)
                bitrateText?.text = if (value > 0L) {
                    String.format(java.util.Locale.US, "%.1f Mbps", value / 1_000_000.0)
                } else "Conectando…"
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        showOverlay()
        val filter = IntentFilter(ScreenStreamService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        statusText = TextView(this).apply {
            text = "Conectando…"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        bitrateText = TextView(this).apply {
            text = "Conectando…"
            setTextColor(Color.LTGRAY)
            textSize = 11f
        }
        val stopButton = Button(this).apply {
            text = "DETENER"
            textSize = 11f
            setTextColor(Color.WHITE)
            setOnClickListener {
                startService(Intent(this@FloatingControlService, ScreenStreamService::class.java).apply {
                    action = ScreenStreamService.ACTION_STOP
                })
            }
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 10, 18, 8)
            setBackgroundColor(Color.argb(235, 28, 23, 38))
            addView(statusText, LinearLayout.LayoutParams(-2, -2))
            addView(bitrateText, LinearLayout.LayoutParams(-2, -2))
            addView(stopButton, LinearLayout.LayoutParams(-1, 42))
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = 90
        }

        try {
            windowManager?.addView(root, params)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        try { root?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        root = null
        windowManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
