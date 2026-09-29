package com.alejandro.streamtotal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
        const val ACTION_STATUS = "com.alejandro.streamtotal.GAMING_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_BITRATE = "bitrate"
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
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startScreenStream(intent)
            ACTION_STOP -> stopScreenStream()
        }
        return START_NOT_STICKY
    }

    private fun startScreenStream(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        val url = intent.getStringExtra(EXTRA_URL).orEmpty().trim()
        val quality = intent.getStringExtra(EXTRA_QUALITY) ?: "720p"

        if (resultCode == -1 || data == null) {
            stopWithMessage("Android no autorizó la captura de pantalla")
            return
        }
        if (!url.startsWith("rtmp://", true) && !url.startsWith("rtmps://", true)) {
            stopWithMessage("Destino inválido: usa una URL RTMP o RTMPS")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification("Preparando captura y conexión…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification("Preparando captura y conexión…"))
            }

            acquireWakeLock()

            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection?.stop()
            projection = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("Android no entregó la proyección de pantalla")

            val screen = ScreenSource(applicationContext, projection!!)
            stream?.stopStream()
            stream?.release()

            stream = GenericStream(
                applicationContext,
                this,
                NoVideoSource(),
                MicrophoneSource()
            ).apply {
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

            if (!prepared) {
                stopWithMessage("No se pudo preparar el codificador " + quality + "/30")
                return
            }

            stream!!.changeVideoSource(screen)

            if (intent.getBooleanExtra(EXTRA_INTERNAL_AUDIO, true) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                stream!!.changeAudioSource(MixAudioSource(projection!!))
            }

            broadcastStatus("Captura lista · conectando RTMP…")
            updateNotification("Conectando al servidor RTMP…")
            stream!!.startStream(url)
        } catch (e: Exception) {
            stopWithMessage("Error al iniciar: " + (e.message ?: "captura no disponible"))
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "StreamTotal:LiveStreaming"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun stopScreenStream() {
        try { stream?.stopStream() } catch (_: Exception) {}
        try { stream?.release() } catch (_: Exception) {}
        stream = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopWithMessage(message: String) {
        broadcastStatus(message)
        stopScreenStream()
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("StreamTotal Gaming")
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun broadcastStatus(text: String, bitrate: Long? = null) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS, text)
            if (bitrate != null) putExtra(EXTRA_BITRATE, bitrate)
        })
    }

    private fun updateNotification(text: String) {
        broadcastStatus(text)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "StreamTotal Gaming",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }
    }

    override fun onDestroy() {
        try { stream?.stopStream() } catch (_: Exception) {}
        try { stream?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        stream = null
        projection = null
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConnectionStarted(url: String) {
        updateNotification("Conectando al servidor…")
    }

    override fun onConnectionSuccess() {
        updateNotification("🔴 StreamTotal Gaming · EN VIVO")
    }

    override fun onNewBitrate(bitrate: Long) {
        broadcastStatus("🔴 StreamTotal Gaming · EN VIVO", bitrate)
        updateNotification(
            "🔴 Gaming · " +
                String.format(java.util.Locale.US, "%.1f", bitrate / 1_000_000.0) +
                " Mbps"
        )
    }

    override fun onConnectionFailed(reason: String) {
        stopWithMessage("RTMP rechazado: " + reason.ifBlank { "revisa servidor y clave" })
    }

    override fun onDisconnect() {
        stopWithMessage("Transmisión desconectada")
    }

    override fun onAuthError() {
        stopWithMessage("Clave RTMP incorrecta o no autorizada")
    }

    override fun onAuthSuccess() {
        updateNotification("Autenticación RTMP correcta · conectando…")
    }
}
