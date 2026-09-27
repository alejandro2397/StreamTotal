package com.alejandro.streamtotal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream

class ScreenStreamService : Service(), ConnectChecker {
    companion object {
        const val ACTION_START = "com.alejandro.streamtotal.START_SCREEN"
        const val ACTION_STOP = "com.alejandro.streamtotal.STOP_SCREEN"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        const val EXTRA_URL = "rtmp_url"
        const val EXTRA_INTERNAL_AUDIO = "internal_audio"
        const val EXTRA_QUALITY = "quality"
        const val CHANNEL_ID = "streamtotal_gaming"
        const val NOTIFICATION_ID = 4107
    }
    private var stream: GenericStream? = null
    private var projection: android.media.projection.MediaProjection? = null

    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startScreenStream(intent)
            ACTION_STOP -> stopScreenStream()
        }
        return START_NOT_STICKY
    }

    private fun startScreenStream(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val quality = intent.getStringExtra(EXTRA_QUALITY) ?: "720p"
        if (resultCode == -1 || data == null || url.isBlank()) {
            stopWithMessage("Falta autorización o destino RTMP")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID, notification("Preparando transmisión de gaming…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else startForeground(NOTIFICATION_ID, notification("Preparando transmisión de gaming…"))

            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection?.stop()
            projection = manager.getMediaProjection(resultCode, data)
            val screen = ScreenSource(applicationContext, projection!!)
            stream?.release()
            stream = GenericStream(applicationContext, this, NoVideoSource(), MicrophoneSource()).apply {
                getGlInterface().setForceRender(true, 30)
                getGlInterface().setCameraOrientation(0)
            }
            val video = when (quality) {
                "480p" -> intArrayOf(854, 480, 2_000_000)
                "1080p" -> intArrayOf(1920, 1080, 5_500_000)
                else -> intArrayOf(1280, 720, 3_500_000)
            }
            val prepared = stream!!.prepareVideo(video[0], video[1], 30, video[2]) &&
                stream!!.prepareAudio(44_100, true, 128_000, false, true)
            if (!prepared) { stopWithMessage("No se pudo preparar 720p/30"); return }
            stream!!.changeVideoSource(screen)
            if (intent.getBooleanExtra(EXTRA_INTERNAL_AUDIO, true) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) stream!!.changeAudioSource(MixAudioSource(projection!!))
            stream!!.startStream(url)
            updateNotification("🔴 StreamTotal Gaming · EN VIVO")
        } catch (e: Exception) {
            stopWithMessage("No se pudo iniciar la captura de pantalla")
        }
    }

    private fun stopScreenStream() {
        stream?.stopStream(); stream?.release(); stream = null
        projection?.stop(); projection = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun stopWithMessage(message: String) {
        updateNotification(message); stopScreenStream()
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("StreamTotal Gaming").setContentText(text)
        .setOngoing(true).setSilent(true).build()

    private fun updateNotification(text: String) =
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(text))

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "StreamTotal Gaming", NotificationManager.IMPORTANCE_LOW)
            )
    }

    override fun onDestroy() {
        stream?.stopStream(); stream?.release(); projection?.stop()
        stream = null; projection = null; super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onConnectionStarted(url: String) = updateNotification("Conectando gaming…")
    override fun onConnectionSuccess() = updateNotification("🔴 StreamTotal Gaming · EN VIVO")
    override fun onNewBitrate(bitrate: Long) = updateNotification("🔴 Gaming · " + String.format(java.util.Locale.US, "%.1f", bitrate / 1_000_000.0) + " Mbps")
    override fun onConnectionFailed(reason: String) = updateNotification("Error de conexión")
    override fun onDisconnect() = updateNotification("Transmisión desconectada")
    override fun onAuthError() = updateNotification("Error de autenticación RTMP")
    override fun onAuthSuccess() = updateNotification("Autenticación RTMP correcta")
}
