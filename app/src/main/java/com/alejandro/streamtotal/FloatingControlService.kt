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
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        statusText = TextView(this).apply {
            text = "🔴 EN VIVO"
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

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(6))
            setBackgroundColor(Color.argb(235, 28, 23, 38))
            addView(statusText, LinearLayout.LayoutParams(-2, -2))
            addView(bitrateText, LinearLayout.LayoutParams(-2, -2))
            addView(stopButton, LinearLayout.LayoutParams(dp(120), dp(40)))
            visibility = android.view.View.GONE
        }

        bubble = TextView(this).apply {
            text = "🔴"
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(235, 120, 30, 45))
            setOnClickListener {
                panel?.visibility =
                    if (panel?.visibility == android.view.View.VISIBLE)
                        android.view.View.GONE
                    else
                        android.view.View.VISIBLE
            }
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(Color.TRANSPARENT)
            addView(panel, LinearLayout.LayoutParams(dp(150), -2))
            addView(bubble, LinearLayout.LayoutParams(dp(54), dp(54)))
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
            x = dp(6)
            y = dp(140)
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
        panel = null
        bubble = null
        windowManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
