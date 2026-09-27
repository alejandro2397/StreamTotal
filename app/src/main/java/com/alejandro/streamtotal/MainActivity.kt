package com.alejandro.streamtotal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.view.SurfaceHolder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pedro.common.ConnectChecker
import com.pedro.library.multiple.MultiCamera2
import com.pedro.library.multiple.MultiType
import com.pedro.library.view.OpenGlView
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
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
                if (permissionsGranted) StudioScreen()
                else PermissionScreen {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("StreamTotal", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(10.dp))
        Text("Permite cámara y micrófono para preparar tu transmisión.")
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest) { Text("PERMITIR ACCESO") }
    }
}

private data class QualityPreset(
    val name: String,
    val width: Int,
    val height: Int,
    val bitrate: Int
)

private val presets = listOf(
    QualityPreset("480p", 854, 480, 1_500_000),
    QualityPreset("720p", 1280, 720, 3_000_000),
    QualityPreset("1080p", 1920, 1080, 5_000_000)
)

@Composable
private fun StudioScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("streamtotal", Context.MODE_PRIVATE) }

    var destination1 by remember { mutableStateOf(prefs.getString("rtmp1", "") ?: "") }
    var destination2 by remember { mutableStateOf(prefs.getString("rtmp2", "") ?: "") }
    var title by remember { mutableStateOf(prefs.getString("title", "Mi transmisión") ?: "Mi transmisión") }
    var quality by remember { mutableStateOf(prefs.getString("quality", "720p") ?: "720p") }
    var tab by remember { mutableStateOf("EN VIVO") }
    var status by remember { mutableStateOf("Listo para transmitir") }
    var isStreaming by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var bitrateText by remember { mutableStateOf("—") }
    var gamingUrl by remember { mutableStateOf(prefs.getString("gamingUrl", "") ?: "") }
    var gamingRunning by remember { mutableStateOf(false) }
    val gamingProjectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null && gamingUrl.isNotBlank()) {
            val intent = Intent(context, ScreenStreamService::class.java).apply {
                action = ScreenStreamService.ACTION_START
                putExtra(ScreenStreamService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenStreamService.EXTRA_DATA, result.data)
                putExtra(ScreenStreamService.EXTRA_URL, gamingUrl.trim())
                putExtra(ScreenStreamService.EXTRA_INTERNAL_AUDIO, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
            gamingRunning = true
        }
    }
    var camera: MultiCamera2? by remember { mutableStateOf(null) }

    LaunchedEffect(isStreaming) {
        while (isStreaming) {
            elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000L
            delay(1000)
        }
    }

    fun saveSettings() {
        prefs.edit()
            .putString("rtmp1", destination1)
            .putString("rtmp2", destination2)
            .putString("title", title)
            .putString("quality", quality)
            .apply()
    }

    val checker1 = remember {
        object : ConnectChecker {
            override fun onConnectionStarted(url: String) { status = "Conectando destino 1…" }
            override fun onConnectionSuccess() {
                status = "🔴 EN VIVO"
                isStreaming = true
                if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
            }
            override fun onConnectionFailed(reason: String) { status = "Destino 1: error de conexión"; isStreaming = false }
            override fun onNewBitrate(bitrate: Long) {
                bitrateText = String.format(Locale.US, "%.1f Mbps", bitrate / 1_000_000.0)
            }
            override fun onDisconnect() {
                if (destination2.isBlank()) {
                    status = "Desconectado"
                    isStreaming = false
                }
            }
            override fun onAuthError() { status = "Destino 1: error de autenticación"; isStreaming = false }
            override fun onAuthSuccess() {}
        }
    }

    val checker2 = remember {
        object : ConnectChecker {
            override fun onConnectionStarted(url: String) { status = "Conectando destino 2…" }
            override fun onConnectionSuccess() { status = "🔴 EN VIVO · 2 destinos"; isStreaming = true }
            override fun onConnectionFailed(reason: String) { status = "Destino 2: no conectado" }
            override fun onNewBitrate(bitrate: Long) {}
            override fun onDisconnect() {}
            override fun onAuthError() { status = "Destino 2: error de autenticación" }
            override fun onAuthSuccess() {}
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            camera?.stopStream(MultiType.RTMP, 0)
            camera?.stopStream(MultiType.RTMP, 1)
            if (camera?.isRecording == true) camera?.stopRecord()
            camera?.stopPreview()
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFFF6F5FA))) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF171321)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("StreamTotal", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(
                    if (isStreaming) "● EN VIVO" else "Centro de transmisión",
                    color = if (isStreaming) Color(0xFFFF4D67) else Color.LightGray
                )
            }
            Text(if (isStreaming) formatTime(elapsed) else "00:00", color = Color.White)
        }

        Box(
            Modifier.fillMaxWidth().height(280.dp)
                .background(Color.Black, RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    OpenGlView(ctx).also { view ->
                        val multi = MultiCamera2(
                            view,
                            arrayOf(checker1, checker2),
                            null, null, null
                        )
                        camera = multi
                        view.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                val selected = presets.first { it.name == quality }
                                multi.prepareVideo(selected.width, selected.height, selected.bitrate)
                                multi.prepareAudio()
                                multi.startPreview()
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                multi.stopStream(MultiType.RTMP, 0)
                                multi.stopStream(MultiType.RTMP, 1)
                                multi.stopPreview()
                            }
                        })
                    }
                }
            )
            SurfaceOverlay(title, status, bitrateText, Modifier.align(Alignment.TopStart))
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("EN VIVO", "🎮 GAMING", "ESCENAS", "AJUSTES").forEach { item ->
                FilterChip(tab == item, { tab = item }, label = { Text(item) })
            }
        }

        when (tab) {
            "EN VIVO" -> LivePanel(
                destination1, { destination1 = it },
                destination2, { destination2 = it },
                title, { title = it },
                isStreaming, isRecording, muted,
                onStart = {
                    saveSettings()
                    val url1 = destination1.trim()
                    val url2 = destination2.trim()
                    val multi = camera
                    if (url1.isEmpty() || multi == null) {
                        status = "Introduce el destino RTMP principal"
                        return@LivePanel
                    }
                    val selected = presets.first { it.name == quality }
                    val ready = multi.prepareVideo(selected.width, selected.height, selected.bitrate) &&
                        multi.prepareAudio()
                    if (!ready) {
                        status = "No se pudo preparar el encoder"
                        return@LivePanel
                    }
                    startedAt = SystemClock.elapsedRealtime()
                    elapsed = 0
                    status = "Conectando…"
                    multi.startStream(MultiType.RTMP, 0, url1)
                    if (url2.isNotEmpty()) multi.startStream(MultiType.RTMP, 1, url2)
                },
                onStop = {
                    camera?.stopStream(MultiType.RTMP, 0)
                    if (destination2.isNotBlank()) camera?.stopStream(MultiType.RTMP, 1)
                    if (camera?.isRecording == true) camera?.stopRecord()
                    isStreaming = false
                    isRecording = false
                    startedAt = 0L
                    status = "Transmisión detenida"
                },
                onMute = {
                    if (muted) camera?.enableAudio() else camera?.disableAudio()
                    muted = !muted
                },
                onSwitch = { camera?.switchCamera() },
                onRecord = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val dir = context.getExternalFilesDir("Recordings")
                        val file = File(dir, "StreamTotal_\${System.currentTimeMillis()}.mp4")
                        dir?.mkdirs()
                        if (camera?.isRecording == true) {
                            camera?.stopRecord()
                            isRecording = false
                        } else {
                            camera?.startRecord(file.absolutePath)
                            isRecording = true
                        }
                    } else {
                        status = "La grabación requiere Android 8 o superior"
                    }
                }
            )
            "🎮 GAMING" -> GamingPanel(
                url = gamingUrl,
                onUrl = { gamingUrl = it; prefs.edit().putString("gamingUrl", it).apply() },
                running = gamingRunning,
                onStart = { gamingProjectionLauncher.launch((context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()) },
                onStop = {
                    context.startService(Intent(context, ScreenStreamService::class.java).setAction(ScreenStreamService.ACTION_STOP))
                    gamingRunning = false
                }
            )
            "ESCENAS" -> ScenesPanel(title, { title = it }, quality, { quality = it; saveSettings() })
            "AJUSTES" -> SettingsPanel(
                quality,
                onQuality = { quality = it; saveSettings() },
                bitrate = bitrateText,
                onBitrateDown = { camera?.setVideoBitrateOnFly(1_500_000) },
                onBitrateUp = { camera?.setVideoBitrateOnFly(5_000_000) }
            )
        }
    }
}

@Composable
private fun SurfaceOverlay(title: String, status: String, bitrate: String, modifier: Modifier) {
    Column(modifier.padding(14.dp)) {
        Surface(color = Color.Black.copy(alpha = .55f), shape = RoundedCornerShape(12.dp)) {
            Text(title.ifBlank { "Mi transmisión" }, color = Color.White, modifier = Modifier.padding(12.dp, 7.dp))
        }
        Spacer(Modifier.height(8.dp))
        Surface(color = Color(0xFF9C1B35), shape = RoundedCornerShape(20.dp)) {
            Text(status, color = Color.White, modifier = Modifier.padding(12.dp, 6.dp))
        }
        if (bitrate != "—") Text(bitrate, color = Color.White, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun LivePanel(
    destination1: String, onDestination1: (String) -> Unit,
    destination2: String, onDestination2: (String) -> Unit,
    title: String, onTitle: (String) -> Unit,
    isStreaming: Boolean, isRecording: Boolean, muted: Boolean,
    onStart: () -> Unit, onStop: () -> Unit, onMute: () -> Unit,
    onSwitch: () -> Unit, onRecord: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        OutlinedTextField(destination1, onDestination1, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Destino RTMP principal") })
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(destination2, onDestination2, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Segundo destino (opcional)") })
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(title, onTitle, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Título") })
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSwitch, enabled = !isStreaming, modifier = Modifier.weight(1f)) { Text("↔ Cámara") }
            OutlinedButton(onClick = onMute, modifier = Modifier.weight(1f)) { Text(if (muted) "🎙 Activar" else "🔇 Silenciar") }
            OutlinedButton(onClick = onRecord, enabled = isStreaming, modifier = Modifier.weight(1f)) {
                Text(if (isRecording) "⏹ Rec" else "⏺ Rec")
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = if (isStreaming) onStop else onStart,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text(if (isStreaming) "⏹ DETENER TRANSMISIÓN" else "🔴 INICIAR TRANSMISIÓN")
        }
        Spacer(Modifier.height(8.dp))
        Text("Puedes enviar la misma cámara a dos destinos RTMP.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

@Composable
private fun ScenesPanel(title: String, onTitle: (String) -> Unit, quality: String, onQuality: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Text("Escenas", style = MaterialTheme.typography.titleLarge)
        Text("Perfiles rápidos para preparar la transmisión.")
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("🎥 Cámara", "🎤 Entrevista", "🎮 Gaming").forEach { scene ->
                OutlinedButton(onClick = {
                    onTitle(
                        when (scene) {
                            "🎤 Entrevista" -> "Entrevista en vivo"
                            "🎮 Gaming" -> "Gaming en vivo"
                            else -> "Mi transmisión"
                        }
                    )
                }) { Text(scene) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Calidad", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
                FilterChip(quality == preset.name, { onQuality(preset.name) }, label = { Text(preset.name) })
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Título actual: $title")
        Spacer(Modifier.height(8.dp))
        Text("Los overlays gráficos codificados en el vídeo serán la siguiente capa.", color = Color.Gray)
    }
}

@Composable
private fun SettingsPanel(
    quality: String,
    onQuality: (String) -> Unit,
    bitrate: String,
    onBitrateDown: () -> Unit,
    onBitrateUp: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Text("Ajustes de emisión", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text("Calidad")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
                FilterChip(quality == preset.name, { onQuality(preset.name) }, label = { Text(preset.name) })
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Bitrate actual: $bitrate")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = onBitrateDown) { Text("1.5 Mbps") }
            OutlinedButton(onClick = onBitrateUp) { Text("5 Mbps") }
        }
        Spacer(Modifier.height(12.dp))
        Text("El bitrate puede ajustarse durante la transmisión.", color = Color.Gray)
    }
}

private fun formatTime(seconds: Long): String =
    String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)

@Composable
private fun StreamTotalTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
