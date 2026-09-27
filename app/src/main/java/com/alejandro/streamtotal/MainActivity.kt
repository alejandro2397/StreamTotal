package com.alejandro.streamtotal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.SurfaceHolder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import kotlinx.coroutines.delay

private val Purple = Color(0xFF6C4DFF)
private val PurpleDark = Color(0xFF4F36C8)
private val Background = Color(0xFFF6F5FA)
private val Card = Color.White

private data class Quality(val label: String, val width: Int, val height: Int, val fps: Int, val bitrate: Int)

private val qualities = listOf(
    Quality("720p · 30", 1280, 720, 30, 2500_000),
    Quality("720p · 60", 1280, 720, 60, 4000_000),
    Quality("1080p · 30", 1920, 1080, 30, 4500_000),
    Quality("480p · 30", 854, 480, 30, 1500_000)
)

class MainActivity : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsGranted = hasPermissions()
    }

    private fun hasPermissions() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsGranted = hasPermissions()
        setContent {
            StreamTotalTheme {
                if (permissionsGranted) RtmpStudio() else PermissionScreen {
                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("StreamTotal", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("Tu estudio móvil para transmitir en vivo.", color = Color.Gray)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest, colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                Text("PERMITIR CÁMARA Y MICRÓFONO")
            }
        }
    }
}

@Composable
private fun RtmpStudio() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("streamtotal", Context.MODE_PRIVATE) }
    var rtmpUrl by remember { mutableStateOf(prefs.getString("rtmp_url", "") ?: "") }
    var streamTitle by remember { mutableStateOf(prefs.getString("title", "Mi transmisión") ?: "Mi transmisión") }
    var status by remember { mutableStateOf("Listo para transmitir") }
    var isStreaming by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var qualityIndex by remember { mutableIntStateOf(prefs.getInt("quality", 0).coerceIn(0, qualities.lastIndex)) }
    var rtmpCamera by remember { mutableStateOf<RtmpCamera2?>(null) }
    var bitrate by remember { mutableLongStateOf(0L) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var showSettings by remember { mutableStateOf(false) }

    val quality = qualities[qualityIndex]

    LaunchedEffect(isStreaming, startedAt) {
        while (isStreaming) {
            elapsed = if (startedAt > 0) (SystemClock.elapsedRealtime() - startedAt) / 1000 else 0
            delay(1000)
        }
    }

    val checker = remember {
        object : ConnectChecker {
            override fun onConnectionStarted(url: String) { status = "Conectando con el servidor…" }
            override fun onConnectionSuccess() {
                status = "EN VIVO"
                isStreaming = true
                startedAt = SystemClock.elapsedRealtime()
            }
            override fun onConnectionFailed(reason: String) {
                status = "No se pudo conectar"
                isStreaming = false
                startedAt = 0
            }
            override fun onNewBitrate(value: Long) { bitrate = value }
            override fun onDisconnect() {
                status = "Transmisión desconectada"
                isStreaming = false
                startedAt = 0
            }
            override fun onAuthError() {
                status = "Error de autenticación / clave RTMP"
                isStreaming = false
                startedAt = 0
            }
            override fun onAuthSuccess() { status = "Autenticación correcta" }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            rtmpCamera?.let {
                if (it.isStreaming) it.stopStream()
                it.stopPreview()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Background)) {
        Box(Modifier.fillMaxWidth().height(330.dp).background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    OpenGlView(ctx).also { view ->
                        val camera = RtmpCamera2(view, checker)
                        rtmpCamera = camera
                        view.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                try {
                                    camera.prepareVideo(quality.width, quality.height, quality.fps, quality.bitrate, 2, 90)
                                    camera.prepareAudio()
                                    camera.startPreview()
                                } catch (_: Exception) {
                                    status = "No se pudo iniciar la cámara"
                                }
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                if (camera.isStreaming) camera.stopStream()
                                camera.stopPreview()
                            }
                        })
                    }
                }
            )
            Row(
                Modifier.align(Alignment.TopStart).padding(14.dp)
                    .background(Color.Black.copy(alpha = .58f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(9.dp).background(if (isStreaming) Color.Red else Color.LightGray, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(
                    if (isStreaming) "EN VIVO  " + formatTime(elapsed) else "VISTA PREVIA",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            if (isStreaming) {
                Text(
                    text = if (bitrate > 0) (bitrate / 1000).toString() + " kbps" else "Conectado",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                )
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("StreamTotal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(if (isStreaming) streamTitle else "Centro de control", color = Color.Gray)
            Spacer(Modifier.height(14.dp))

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SmallControl("🎙", if (isMuted) "Micrófono OFF" else "Micrófono") {
                    val camera = rtmpCamera ?: return@SmallControl
                    if (isMuted) camera.enableAudio() else camera.disableAudio()
                    isMuted = !isMuted
                }
                SmallControl("🔄", "Cámara") { rtmpCamera?.switchCamera() }
                SmallControl("⚙", "Ajustes") { showSettings = !showSettings }
            }

            Spacer(Modifier.height(14.dp))

            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Card)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Destino RTMP", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rtmpUrl,
                        onValueChange = { rtmpUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isStreaming,
                        label = { Text("URL RTMP / RTMPS") },
                        placeholder = { Text("rtmps://servidor/live/clave") }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = streamTitle,
                        onValueChange = { streamTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isStreaming,
                        label = { Text("Título de la transmisión") }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("El título se guarda en este teléfono; el servidor RTMP no lo recibe automáticamente.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }

            Spacer(Modifier.height(12.dp))

            if (showSettings) {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Card)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Calidad", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            qualities.forEachIndexed { index, q ->
                                FilterChip(
                                    selected = index == qualityIndex,
                                    onClick = {
                                        if (!isStreaming) {
                                            qualityIndex = index
                                            prefs.edit().putInt("quality", index).apply()
                                            status = "Calidad " + q.label + " seleccionada; reinicia la vista previa para aplicarla."
                                        }
                                    },
                                    label = { Text(q.label) }
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Bitrate inicial: " + (quality.bitrate / 1_000_000f) + " Mbps", style = MaterialTheme.typography.bodyMedium)
                        Text("FPS: " + quality.fps + " · Resolución: " + quality.width + "×" + quality.height, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Text("Estado", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(status, color = if (isStreaming) Color(0xFFB00020) else Color.Gray)
            Spacer(Modifier.height(14.dp))

            Button(
                onClick = {
                    val camera = rtmpCamera ?: return@Button
                    if (!isStreaming) {
                        val url = rtmpUrl.trim()
                        if (url.isEmpty()) {
                            status = "Primero introduce tu URL RTMP/RTMPS."
                            return@Button
                        }
                        prefs.edit().putString("rtmp_url", url).putString("title", streamTitle).apply()
                        try {
                            if (camera.isAudioMuted) camera.enableAudio()
                            camera.setVideoBitrateOnFly(quality.bitrate)
                            status = "Conectando…"
                            camera.startStream(url)
                        } catch (e: Exception) {
                            status = "Error al iniciar la transmisión"
                        }
                    } else {
                        camera.stopStream()
                        status = "Transmisión detenida"
                        isStreaming = false
                        startedAt = 0
                    }
                },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (isStreaming) Color(0xFF333333) else Purple)
            ) {
                Text(if (isStreaming) "⏹  DETENER TRANSMISIÓN" else "🔴  INICIAR TRANSMISIÓN", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Consejo: usa RTMPS cuando tu plataforma lo permita. StreamTotal no almacena tu clave en servidores; solo la guarda localmente para facilitar el siguiente directo.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SmallControl(icon: String, label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(icon)
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

private fun formatTime(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

@Composable
private fun StreamTotalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Purple,
            secondary = PurpleDark,
            background = Background,
            surface = Card
        ),
        content = content
    )
}
