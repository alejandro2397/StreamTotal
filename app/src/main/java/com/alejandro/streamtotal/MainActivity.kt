package com.alejandro.streamtotal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.content.Intent
import android.net.Uri
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.view.SurfaceHolder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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
                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                }
            }
        }
    }
}

private fun securePreferences(context: Context): android.content.SharedPreferences =
    EncryptedSharedPreferences.create(
        context,
        "streamtotal_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("StreamTotal", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(10.dp))
        Text("Permite cámara y micrófono para preparar tu transmisión.")
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest, colors = ButtonDefaults.buttonColors(containerColor = StreamPurple), modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("PERMITIR ACCESO") }
    }
}

private data class QualityPreset(val name: String, val width: Int, val height: Int, val bitrate: Int)
private val presets = listOf(
    QualityPreset("480p", 854, 480, 1_500_000),
    QualityPreset("720p", 1280, 720, 3_000_000),
    QualityPreset("1080p", 1920, 1080, 5_000_000)
)
private data class SocialPreset(val name: String, val server: String, val hint: String)
private val socialPresets = listOf(
    SocialPreset("YouTube", "rtmp://a.rtmp.youtube.com/live2/", "Clave de emisión de YouTube"),
    SocialPreset("Facebook", "rtmps://live-api-s.facebook.com:443/rtmp/", "Clave de transmisión de Facebook"),
    SocialPreset("TikTok", "", "Servidor y clave proporcionados por TikTok"),
    SocialPreset("Twitch", "rtmp://live.twitch.tv/app/", "Clave de transmisión de Twitch"),
    SocialPreset("Kick", "", "Servidor y clave de transmisión de Kick"),
    SocialPreset("Instagram", "", "Disponible según la cuenta y las funciones de Live habilitadas"),
    SocialPreset("X / Twitter", "", "Servidor RTMP y clave de transmisión de X"),
    SocialPreset("LinkedIn", "", "Servidor y clave de LinkedIn Live, si están habilitados"),
    SocialPreset("Rumble", "", "Servidor RTMP + clave de Rumble"),
    SocialPreset("Trovo", "", "Servidor RTMP + clave de Trovo"),
    SocialPreset("DLive", "", "Servidor RTMP + clave de DLive"),
    SocialPreset("VK", "", "Servidor RTMP + clave de VK Live"),
    SocialPreset("Custom RTMP", "", "Cualquier servidor RTMP compatible")
)

@Composable
private fun StudioScreen() {
    val context = LocalContext.current
    val prefs = remember { securePreferences(context) }
    var destination1 by remember { mutableStateOf(prefs.getString("rtmp1", "") ?: "") }
    var destination2 by remember { mutableStateOf(prefs.getString("rtmp2", "") ?: "") }
    var title by remember { mutableStateOf(prefs.getString("title", "Mi transmisión") ?: "Mi transmisión") }
    var quality by remember { mutableStateOf(prefs.getString("quality", "720p") ?: "720p") }
    var tab by remember { mutableStateOf("EN VIVO") }
    var socialSlot by remember { mutableIntStateOf(1) }
    var socialNetwork by remember { mutableStateOf(prefs.getString("socialNetwork1", "YouTube") ?: "YouTube") }
    var status by remember { mutableStateOf("Listo para transmitir") }
    var isStreaming by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var bitrateText by remember { mutableStateOf("—") }
    var gamingUrl by remember { mutableStateOf(prefs.getString("gamingUrl", "") ?: "") }
    var gamingRunning by remember { mutableStateOf(false) }
    var gamingStatus by remember { mutableStateOf("Listo para gaming") }
    var gamingBitrate by remember { mutableStateOf("—") }
    var gamingElapsed by remember { mutableLongStateOf(0L) }
    var gamingStartedAt by remember { mutableLongStateOf(0L) }
    var gamingScene by remember { mutableStateOf("Gameplay") }
    var gamingQuality by remember { mutableStateOf(prefs.getString("gamingQuality", "720p") ?: "720p") }
    var gamingAudio by remember { mutableStateOf(prefs.getBoolean("gamingAudio", true)) }
    var gamingMic by remember { mutableStateOf(prefs.getBoolean("gamingMic", true)) }
    var gamingLowLatency by remember { mutableStateOf(prefs.getBoolean("gamingLowLatency", true)) }
    var gamingAutoOpen by remember { mutableStateOf(prefs.getBoolean("gamingAutoOpen", true)) }
    var gamingChatOverlay by remember { mutableStateOf(prefs.getBoolean("gamingChatOverlay", false)) }
    var gamingAutoReconnect by remember { mutableStateOf(prefs.getBoolean("gamingAutoReconnect", true)) }
    var gamingShowStats by remember { mutableStateOf(prefs.getBoolean("gamingShowStats", true)) }
    var gamingAutoScene by remember { mutableStateOf(prefs.getBoolean("gamingAutoScene", false)) }
    var gamingPerformance by remember { mutableStateOf(prefs.getBoolean("gamingPerformance", true)) }
    var gamingPreset by remember { mutableStateOf(prefs.getString("gamingPreset", "Free Fire") ?: "Free Fire") }
    var multiDestinations by remember { mutableStateOf(prefs.getBoolean("multiDestinations", false)) }
    var destinationCount by remember { mutableIntStateOf(prefs.getInt("destinationCount", 1)) }
    var connectedEmail by remember { mutableStateOf(prefs.getBoolean("connectedEmail", false)) }
    var connectedFacebook by remember { mutableStateOf(prefs.getBoolean("connectedFacebook", false)) }
    var connectedTikTok by remember { mutableStateOf(prefs.getBoolean("connectedTikTok", false)) }
    var connectedYouTube by remember { mutableStateOf(prefs.getBoolean("connectedYouTube", false)) }
    var connectedTwitch by remember { mutableStateOf(prefs.getBoolean("connectedTwitch", false)) }
    var connectedKick by remember { mutableStateOf(prefs.getBoolean("connectedKick", false)) }
    val gamingProjectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null && gamingUrl.isNotBlank()) {
            val intent = Intent(context, ScreenStreamService::class.java).apply {
                action = ScreenStreamService.ACTION_START
                putExtra(ScreenStreamService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenStreamService.EXTRA_DATA, result.data)
                putExtra(ScreenStreamService.EXTRA_URL, gamingUrl.trim())
                putExtra(ScreenStreamService.EXTRA_INTERNAL_AUDIO, gamingAudio)
                putExtra(ScreenStreamService.EXTRA_QUALITY, gamingQuality)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
            gamingRunning = true
            gamingStartedAt = SystemClock.elapsedRealtime()
            gamingElapsed = 0L
            gamingStatus = "Conectando gaming…"
            gamingBitrate = "—"
        }
    }
    var camera: MultiCamera2? by remember { mutableStateOf(null) }
    LaunchedEffect(gamingRunning) {
        while (gamingRunning) {
            gamingElapsed = (SystemClock.elapsedRealtime() - gamingStartedAt) / 1000L
            delay(1000)
        }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ScreenStreamService.ACTION_STATUS) {
                    gamingStatus = intent.getStringExtra(ScreenStreamService.EXTRA_STATUS) ?: gamingStatus
                    if (intent.hasExtra(ScreenStreamService.EXTRA_BITRATE)) {
                        val value = intent.getLongExtra(ScreenStreamService.EXTRA_BITRATE, 0L)
                        gamingBitrate = if (value > 0L) String.format(Locale.US, "%.1f Mbps", value / 1_000_000.0) else "—"
                    }
                    if (gamingStatus.contains("desconect", true) || gamingStatus.contains("falló", true) || gamingStatus.contains("incorrecta", true) || gamingStatus.contains("error", true)) {
                        gamingRunning = false
                    }
                }
            }
        }
        val filter = IntentFilter(ScreenStreamService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") context.registerReceiver(receiver, filter) }
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }
    }
    LaunchedEffect(isStreaming) {
        while (isStreaming) { elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000L; delay(1000) }
    }
    fun saveSettings() {
        prefs.edit().putString("rtmp1", destination1).putString("rtmp2", destination2).putString("title", title).putString("quality", quality).apply()
    }
    val checker1 = remember { object : ConnectChecker {
        override fun onConnectionStarted(url: String) { status = "Conectando destino 1…" }
        override fun onConnectionSuccess() { status = "🔴 EN VIVO"; isStreaming = true; if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime() }
        override fun onConnectionFailed(reason: String) { status = "Destino 1: error de conexión"; isStreaming = false }
        override fun onNewBitrate(bitrate: Long) { bitrateText = String.format(Locale.US, "%.1f Mbps", bitrate / 1_000_000.0) }
        override fun onDisconnect() { if (destination2.isBlank()) { status = "Desconectado"; isStreaming = false } }
        override fun onAuthError() { status = "Destino 1: error de autenticación"; isStreaming = false }
        override fun onAuthSuccess() {}
    } }
    val checker2 = remember { object : ConnectChecker {
        override fun onConnectionStarted(url: String) { status = "Conectando destino 2…" }
        override fun onConnectionSuccess() { status = "🔴 EN VIVO · 2 destinos"; isStreaming = true }
        override fun onConnectionFailed(reason: String) { status = "Destino 2: no conectado" }
        override fun onNewBitrate(bitrate: Long) {}
        override fun onDisconnect() {}
        override fun onAuthError() { status = "Destino 2: error de autenticación" }
        override fun onAuthSuccess() {}
    } }
    DisposableEffect(Unit) {
        onDispose {
            camera?.stopStream(MultiType.RTMP, 0)
            camera?.stopStream(MultiType.RTMP, 1)
            if (camera?.isRecording == true) camera?.stopRecord()
            camera?.stopPreview()
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFFF6F5FA))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF171321)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("StreamTotal", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(if (isStreaming) "● EN VIVO" else "Centro de transmisión", color = if (isStreaming) Color(0xFFFF4D67) else Color.LightGray)
            }
            Text(if (isStreaming) formatTime(elapsed) else "00:00", color = Color.White)
        }
        Box(Modifier.fillMaxWidth().height(280.dp).background(Color.Black, RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                OpenGlView(ctx).also { view ->
                    val multi = MultiCamera2(view, arrayOf(checker1, checker2), null, null, null)
                    camera = multi
                    view.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { val selected = presets.first { it.name == quality }; multi.prepareVideo(selected.width, selected.height, selected.bitrate); multi.prepareAudio(); multi.startPreview() }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) { multi.stopStream(MultiType.RTMP, 0); multi.stopStream(MultiType.RTMP, 1); multi.stopPreview() }
                    })
                }
            })
            SurfaceOverlay(title, status, bitrateText, Modifier.align(Alignment.TopStart))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("EN VIVO", "🌐 REDES", "🎮 GAMING", "ESCENAS", "AJUSTES").forEach { item -> FilterChip(tab == item, { tab = item }, label = { Text(item) }) }
        }
        when (tab) {
            "EN VIVO" -> LivePanel(destination1, { destination1 = it }, destination2, { destination2 = it }, title, { title = it }, isStreaming, isRecording, muted,
                onStart = { saveSettings(); val url1 = destination1.trim(); val url2 = destination2.trim(); val multi = camera; if (url1.isEmpty() || multi == null) { status = "Introduce el destino RTMP principal"; return@LivePanel }; val selected = presets.first { it.name == quality }; val ready = multi.prepareVideo(selected.width, selected.height, selected.bitrate) && multi.prepareAudio(); if (!ready) { status = "No se pudo preparar el encoder"; return@LivePanel }; startedAt = SystemClock.elapsedRealtime(); elapsed = 0; status = "Conectando…"; multi.startStream(MultiType.RTMP, 0, url1); if (url2.isNotEmpty()) multi.startStream(MultiType.RTMP, 1, url2) },
                onStop = { camera?.stopStream(MultiType.RTMP, 0); if (destination2.isNotBlank()) camera?.stopStream(MultiType.RTMP, 1); if (camera?.isRecording == true) camera?.stopRecord(); isStreaming = false; isRecording = false; startedAt = 0L; status = "Transmisión detenida" },
                onMute = { if (muted) camera?.enableAudio() else camera?.disableAudio(); muted = !muted }, onSwitch = { camera?.switchCamera() },
                onRecord = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val dir = context.getExternalFilesDir("Recordings"); val file = File(dir, "StreamTotal_${System.currentTimeMillis()}.mp4"); dir?.mkdirs(); if (camera?.isRecording == true) { camera?.stopRecord(); isRecording = false } else { camera?.startRecord(file.absolutePath); isRecording = true } } else status = "La grabación requiere Android 8 o superior" })
            "🌐 REDES" -> SocialNetworksPanel(destination1, destination2, socialSlot, socialNetwork, { socialSlot = it }, { network -> socialNetwork = network; prefs.edit().putString("socialNetwork$socialSlot", network).apply(); val preset = socialPresets.first { it.name == network }; if (preset.server.isNotBlank()) { if (socialSlot == 1) destination1 = preset.server else destination2 = preset.server } }, { network -> if (socialSlot == 1) destination1 = network else destination2 = network })
            "🎮 GAMING" -> GamingPanel(gamingRunning, gamingStatus, gamingBitrate, gamingElapsed, gamingScene, { gamingScene = it }, gamingUrl, { gamingUrl = it; prefs.edit().putString("gamingUrl", it).apply() }, gamingQuality, gamingAudio, gamingMic, { gamingMic = it; prefs.edit().putBoolean("gamingMic", it).apply() }, gamingLowLatency, { gamingLowLatency = it; prefs.edit().putBoolean("gamingLowLatency", it).apply() }, gamingAutoOpen, { gamingAutoOpen = it; prefs.edit().putBoolean("gamingAutoOpen", it).apply() }, gamingChatOverlay, { gamingChatOverlay = it; prefs.edit().putBoolean("gamingChatOverlay", it).apply() }, gamingAutoReconnect, { gamingAutoReconnect = it; prefs.edit().putBoolean("gamingAutoReconnect", it).apply() }, gamingShowStats, { gamingShowStats = it; prefs.edit().putBoolean("gamingShowStats", it).apply() }, gamingAutoScene, { gamingAutoScene = it; prefs.edit().putBoolean("gamingAutoScene", it).apply() }, gamingPerformance, { gamingPerformance = it; prefs.edit().putBoolean("gamingPerformance", it).apply() }, gamingPreset, { gamingPreset = it; prefs.edit().putString("gamingPreset", it).apply() }, multiDestinations, { multiDestinations = it; prefs.edit().putBoolean("multiDestinations", it).apply() }, destinationCount, { destinationCount = it; prefs.edit().putInt("destinationCount", it).apply() }, { gamingQuality = it; prefs.edit().putString("gamingQuality", it).apply() }, { gamingAudio = it; prefs.edit().putBoolean("gamingAudio", it).apply() }, { gamingRunning = false; gamingStatus = "Listo para gaming"; context.startService(Intent(context, ScreenStreamService::class.java).apply { action = ScreenStreamService.ACTION_STOP }) }, { if (gamingRunning) context.startService(Intent(context, ScreenStreamService::class.java).apply { action = ScreenStreamService.ACTION_STOP }) else { if (gamingUrl.isBlank()) gamingStatus = "Introduce un destino RTMP"; else { val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager; gamingProjectionLauncher.launch(mgr.createScreenCaptureIntent()) } } })
            "ESCENAS" -> ScenesPanel(title, { title = it }, quality, { quality = it; prefs.edit().putString("quality", it).apply() })
            "AJUSTES" -> SettingsPanel(quality, { quality = it; prefs.edit().putString("quality", it).apply() }, bitrateText, {}, {})
        }
    }
}

private fun formatTime(seconds: Long): String = String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
private val StreamPurple = Color(0xFF6C3BFF)
private val StreamPurpleDark = Color(0xFF4D25C7)
private val StreamCyan = Color(0xFF00A8D6)
private val StreamDanger = Color(0xFFE63B5B)
private val StreamSuccess = Color(0xFF19A974)
private val StreamBackground = Color(0xFFF5F3F9)

@Composable private fun StreamTotalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = StreamPurple, onPrimary = Color.White, primaryContainer = Color(0xFFE9E1FF), onPrimaryContainer = StreamPurpleDark, secondary = StreamCyan, onSecondary = Color.White, secondaryContainer = Color(0xFFDDF7FF), onSecondaryContainer = Color(0xFF004E63), tertiary = StreamSuccess, error = StreamDanger, background = StreamBackground, surface = Color.White), content = content)
}
