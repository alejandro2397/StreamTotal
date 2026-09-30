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
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    )
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
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("StreamTotal", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(10.dp))
        Text("Permite cámara y micrófono para preparar tu transmisión.")
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest, colors = ButtonDefaults.buttonColors(containerColor = StreamPurple), modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("PERMITIR ACCESO") }
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
    var gamingServer by remember { mutableStateOf(prefs.getString("gamingServer", "") ?: "") }
    var gamingKey by remember { mutableStateOf(prefs.getString("gamingKey", "") ?: "") }
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
        val gamingUrl = buildRtmpEndpoint(gamingServer, gamingKey)
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null && gamingUrl.isNotBlank()) {
            val intent = Intent(context, ScreenStreamService::class.java).apply {
                action = ScreenStreamService.ACTION_START
                putExtra(ScreenStreamService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenStreamService.EXTRA_DATA, result.data)
                putExtra(ScreenStreamService.EXTRA_URL, gamingUrl)
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
                }
            }
        }
        val filter = IntentFilter(ScreenStreamService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

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
            listOf("EN VIVO", "🌐 REDES", "🎮 GAMING", "ESCENAS", "AJUSTES").forEach { item ->
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
            "🌐 REDES" -> SocialNetworksPanel(
                destination1 = destination1,
                destination2 = destination2,
                socialSlot = socialSlot,
                selectedNetwork = socialNetwork,
                onSlot = { socialSlot = it },
                onNetwork = { network ->
                    socialNetwork = network
                    prefs.edit().putString("socialNetwork$socialSlot", network).apply()
                    val preset = socialPresets.first { it.name == network }
                    if (socialSlot == 1) destination1 = preset.server else destination2 = preset.server
                },
                onServer = { value ->
                    if (socialSlot == 1) destination1 = value else destination2 = value
                    saveSettings()
                },
                onKey = { value ->
                    val server = if (socialSlot == 1) destination1 else destination2
                    val full = if (value.isBlank()) server else if (server.endsWith("/")) server + value.trim() else "$server/\${value.trim()}"
                    if (socialSlot == 1) destination1 = full else destination2 = full
                    prefs.edit().putString("streamKey$socialSlot", value).apply()
                },
                onApply = {
                    saveSettings()
                    tab = "EN VIVO"
                }
            )
            "🎮 GAMING" -> GamingPanel(
                running = gamingRunning,
                status = gamingStatus,
                bitrate = gamingBitrate,
                elapsed = gamingElapsed,
                scene = gamingScene,
                onScene = { gamingScene = it },
                server = gamingServer,
                onServer = { gamingServer = it; prefs.edit().putString("gamingServer", it).apply() },
                streamKey = gamingKey,
                onStreamKey = { gamingKey = it; prefs.edit().putString("gamingKey", it).apply() },
                quality = gamingQuality,
                audioEnabled = gamingAudio,
                onQuality = { gamingQuality = it; prefs.edit().putString("gamingQuality", it).apply() },
                onAudio = { gamingAudio = it; prefs.edit().putBoolean("gamingAudio", it).apply() },
                micEnabled = gamingMic,
                onMic = { gamingMic = it; prefs.edit().putBoolean("gamingMic", it).apply() },
                lowLatency = gamingLowLatency,
                onLowLatency = { gamingLowLatency = it; prefs.edit().putBoolean("gamingLowLatency", it).apply() },
                autoOpenGame = gamingAutoOpen,
                onAutoOpenGame = { gamingAutoOpen = it; prefs.edit().putBoolean("gamingAutoOpen", it).apply() },
                chatOverlay = gamingChatOverlay,
                onChatOverlay = { gamingChatOverlay = it; prefs.edit().putBoolean("gamingChatOverlay", it).apply() },
                autoReconnect = gamingAutoReconnect,
                onAutoReconnect = { gamingAutoReconnect = it; prefs.edit().putBoolean("gamingAutoReconnect", it).apply() },
                showStats = gamingShowStats,
                onShowStats = { gamingShowStats = it; prefs.edit().putBoolean("gamingShowStats", it).apply() },
                autoScene = gamingAutoScene,
                onAutoScene = { gamingAutoScene = it; prefs.edit().putBoolean("gamingAutoScene", it).apply() },
                performanceMode = gamingPerformance,
                onPerformanceMode = { gamingPerformance = it; prefs.edit().putBoolean("gamingPerformance", it).apply() },
                preset = gamingPreset,
                onPreset = { gamingPreset = it; prefs.edit().putString("gamingPreset", it).apply() },
                multiDestinations = multiDestinations,
                onMultiDestinations = { multiDestinations = it; prefs.edit().putBoolean("multiDestinations", it).apply() },
                destinationCount = destinationCount,
                onDestinationCount = { destinationCount = it.coerceIn(1, 4); prefs.edit().putInt("destinationCount", destinationCount).apply() },
                onStart = { gamingProjectionLauncher.launch((context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()) },
                onStop = {
                    context.startService(Intent(context, ScreenStreamService::class.java).setAction(ScreenStreamService.ACTION_STOP))
                    gamingRunning = false
                    gamingStartedAt = 0L
                    gamingElapsed = 0L
                    gamingStatus = "Gaming detenido"
                    gamingBitrate = "—"
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
private fun SocialNetworksPanel(
    destination1: String,
    destination2: String,
    socialSlot: Int,
    selectedNetwork: String,
    onSlot: (Int) -> Unit,
    onNetwork: (String) -> Unit,
    onServer: (String) -> Unit,
    onKey: (String) -> Unit,
    onApply: () -> Unit
) {
    val currentUrl = if (socialSlot == 1) destination1 else destination2
    val preset = socialPresets.firstOrNull { it.name == selectedNetwork } ?: socialPresets.last()
    var streamKey by remember(selectedNetwork, socialSlot) { mutableStateOf("") }

    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Text("Redes sociales", style = MaterialTheme.typography.titleLarge)
        Text("Configura hasta 2 destinos RTMP y transmite a dos plataformas a la vez.", color = Color.Gray)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(socialSlot == 1, { onSlot(1) }, label = { Text("Destino 1") })
            FilterChip(socialSlot == 2, { onSlot(2) }, label = { Text("Destino 2") })
        }
        Spacer(Modifier.height(12.dp))
        Text("Plataforma", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            socialPresets.forEach { item ->
                FilterChip(selectedNetwork == item.name, { onNetwork(item.name) }, label = { Text(item.name) })
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(preset.hint, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = currentUrl,
            onValueChange = onServer,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Servidor RTMP") }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = streamKey,
            onValueChange = { streamKey = it; onKey(it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Clave de transmisión") }
        )
        Spacer(Modifier.height(12.dp))
        Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), color = Color(0xFFEDE9F4)) {
            Column(Modifier.padding(14.dp)) {
                Text("🔒 Seguridad", style = MaterialTheme.typography.titleMedium)
                Text("La clave se guarda localmente en el teléfono y no se muestra en pantalla.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onApply, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = StreamPurple)) {
            Text("✓ GUARDAR DESTINO")
        }
        Spacer(Modifier.height(8.dp))
        Text("StreamTotal usa RTMP/RTMPS. Cada plataforma debe proporcionarte su servidor y clave.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (isStreaming) StreamDanger else StreamPurple)
        ) {
            Text(if (isStreaming) "⏹ DETENER TRANSMISIÓN" else "🔴 INICIAR TRANSMISIÓN")
        }
        Spacer(Modifier.height(8.dp))
        Text("Puedes enviar la misma cámara a dos destinos RTMP.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

@Composable
private fun GamingPanel(
    running: Boolean,
    status: String,
    bitrate: String,
    elapsed: Long,
    scene: String,
    onScene: (String) -> Unit,
    server: String,
    onServer: (String) -> Unit,
    streamKey: String,
    onStreamKey: (String) -> Unit,
    quality: String,
    audioEnabled: Boolean,
    micEnabled: Boolean,
    onMic: (Boolean) -> Unit,
    lowLatency: Boolean,
    onLowLatency: (Boolean) -> Unit,
    autoOpenGame: Boolean,
    onAutoOpenGame: (Boolean) -> Unit,
    chatOverlay: Boolean,
    onChatOverlay: (Boolean) -> Unit,
    autoReconnect: Boolean,
    onAutoReconnect: (Boolean) -> Unit,
    showStats: Boolean,
    onShowStats: (Boolean) -> Unit,
    autoScene: Boolean,
    onAutoScene: (Boolean) -> Unit,
    performanceMode: Boolean,
    onPerformanceMode: (Boolean) -> Unit,
    preset: String,
    onPreset: (String) -> Unit,
    multiDestinations: Boolean,
    onMultiDestinations: (Boolean) -> Unit,
    destinationCount: Int,
    onDestinationCount: (Int) -> Unit,
    onQuality: (String) -> Unit,
    onAudio: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val targetBitrate = when (quality) {
        "480p" -> "2.0 Mbps"
        "1080p" -> "5.5 Mbps"
        else -> "3.5 Mbps"
    }

    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF171321)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("🎮 GAMING STUDIO", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (running) "● $status" else "Free Fire · listo para jugar",
                            color = if (running) Color(0xFFFF5A6F) else Color(0xFFD0CBD8)
                        )
                    }
                    Text(formatTime(elapsed), color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(14.dp))
                if (showStats) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GamingStatCard("FPS", "30", "objetivo", Modifier.weight(1f))
                        GamingStatCard("BITRATE", bitrate, if (bitrate == "—") "objetivo $targetBitrate" else "actual", Modifier.weight(1f))
                        GamingStatCard("CALIDAD", quality, targetBitrate, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = if (running) Color(0xFF183B2E) else Color(0xFF27222F)
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (running) "● EN VIVO" else "○ LISTO", color = if (running) StreamSuccess else Color(0xFFBEB7C8), style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(12.dp))
                            Text("Latencia " + if (lowLatency) "baja" else "normal", color = Color.White, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.weight(1f))
                            Text(if (autoReconnect) "↻ Auto-reconexión" else "— Reconexión manual", color = Color(0xFFD0CBD8), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text("Escena rápida", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Inicio", "Gameplay", "Pausa", "Final").forEach { item ->
                FilterChip(
                    selected = scene == item,
                    onClick = { onScene(item) },
                    label = { Text(if (scene == item) "● $item" else item) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = server,
            onValueChange = onServer,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Servidor RTMP") },
            placeholder = { Text("rtmps://servidor:443/app") },
            enabled = !running
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = streamKey,
            onValueChange = onStreamKey,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Clave de transmisión") },
            placeholder = { Text("sk_...") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            enabled = !running
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "StreamTotal unirá automáticamente servidor + clave al iniciar Gaming.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        Spacer(Modifier.height(10.dp))
        Text("Preset de transmisión", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Free Fire", "YouTube", "Facebook", "TikTok").forEach { p ->
                FilterChip(selected = preset == p, onClick = { onPreset(p) }, label = { Text(p) })
            }
        }
        Spacer(Modifier.height(10.dp))
        Spacer(Modifier.height(10.dp))
        Text("Centro Multistream", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { if (!running) onMultiDestinations(!multiDestinations) }, label = { Text(if (multiDestinations) "🌐 Multistream ON" else "🌐 Un destino") })
            if (multiDestinations) {
                (1..4).forEach { n ->
                    FilterChip(selected = destinationCount == n, onClick = { if (!running) onDestinationCount(n) }, label = { Text("$n destinos") })
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Calidad gaming", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("480p", "720p", "1080p").forEach { q ->
                FilterChip(selected = quality == q, onClick = { if (!running) onQuality(q) }, label = { Text(q) })
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("Controles rápidos", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { if (!running) onMic(!micEnabled) }, label = { Text(if (micEnabled) "🎙 Mic ON" else "🔇 Mic OFF") })
            AssistChip(onClick = { if (!running) onAudio(!audioEnabled) }, label = { Text(if (audioEnabled) "🔊 Juego ON" else "🔇 Juego OFF") })
            AssistChip(onClick = { onLowLatency(!lowLatency) }, label = { Text(if (lowLatency) "⚡ Baja latencia" else "🐢 Estable") })
        }
        Spacer(Modifier.height(10.dp))
        Text("Experiencia profesional", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { onAutoOpenGame(!autoOpenGame) }, label = { Text(if (autoOpenGame) "🎮 Abrir juego" else "🎮 Manual") })
            AssistChip(onClick = { onChatOverlay(!chatOverlay) }, label = { Text(if (chatOverlay) "💬 Chat ON" else "💬 Chat OFF") })
            AssistChip(onClick = { onAutoReconnect(!autoReconnect) }, label = { Text(if (autoReconnect) "🔄 Reconexión ON" else "🔄 Reconexión OFF") })
            AssistChip(onClick = { onShowStats(!showStats) }, label = { Text(if (showStats) "📊 Stats ON" else "📊 Stats OFF") })
            AssistChip(onClick = { onAutoScene(!autoScene) }, label = { Text(if (autoScene) "🎬 Auto escenas" else "🎬 Manual escenas") })
            AssistChip(onClick = { onPerformanceMode(!performanceMode) }, label = { Text(if (performanceMode) "🚀 Rendimiento" else "🔋 Equilibrado") })
        }
        Spacer(Modifier.height(10.dp))
        Text("Audio", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { if (!running) onAudio(!audioEnabled) },
                label = { Text(if (audioEnabled) "🎮 Audio del juego" else "🔇 Juego silenciado") }
            )
            AssistChip(onClick = {}, enabled = false, label = { Text("📱 Pantalla completa") })
        }

        Spacer(Modifier.height(12.dp))
        Text("Acciones rápidas", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { if (!running) onMic(!micEnabled) }, label = { Text(if (micEnabled) "🎙 Mic" else "🔇 Mic") }, modifier = Modifier.weight(1f))
            AssistChip(onClick = { if (!running) onAudio(!audioEnabled) }, label = { Text(if (audioEnabled) "🔊 Juego" else "🔇 Juego") }, modifier = Modifier.weight(1f))
            AssistChip(onClick = { if (running) onScene("Pausa") }, label = { Text("⏸ Pausa") }, modifier = Modifier.weight(1f))
            AssistChip(onClick = { onScene("Gameplay") }, label = { Text("🎮 Juego") }, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onScene("Pausa") },
                enabled = running,
                modifier = Modifier.weight(1f)
            ) { Text("⏸ Pausa") }
            OutlinedButton(
                onClick = { onScene("Gameplay") },
                enabled = running,
                modifier = Modifier.weight(1f)
            ) { Text("🎮 Gameplay") }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = if (running) onStop else onStart,
            enabled = running || (server.isNotBlank() && streamKey.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (running) StreamDanger else StreamPurple)
        ) {
            Text(if (running) "⏹ DETENER GAMING" else "🔴 INICIAR GAMING")
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Inicia la captura, acepta el permiso de Android y luego abre Free Fire. StreamTotal continuará en segundo plano.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

@Composable
private fun GamingStatCard(label: String, value: String, detail: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = Color(0xFF2A2533)) {
        Column(Modifier.padding(10.dp)) {
            Text(label, color = Color(0xFFBEB7C8), style = MaterialTheme.typography.labelSmall)
            Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = Color(0xFFAAA2B3), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ScenesPanel(title: String, onTitle: (String) -> Unit, quality: String, onQuality: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Text("Escenas", style = MaterialTheme.typography.titleLarge)
        Text("Prepara el flujo del directo con Inicio, Gameplay, Pausa y Final.", color = Color.Gray)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("🎬 Inicio", "🎮 Gameplay", "⏸ Pausa", "🏁 Final").forEach { scene ->
                OutlinedButton(onClick = {
                    onTitle(
                        when (scene) {
                            "🎬 Inicio" -> "Bienvenidos al directo"
                            "🎮 Gameplay" -> "Gameplay en vivo"
                            "⏸ Pausa" -> "Volvemos enseguida"
                            else -> "Gracias por ver"
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


@Composable
private fun AccountConnectRow(
    icon: String,
    name: String,
    connected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (connected) Color(0xFFE8F7EF) else Color.White,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(if (connected) "Cuenta conectada" else "No conectada", style = MaterialTheme.typography.bodySmall, color = if (connected) StreamSuccess else Color.Gray)
            }
            OutlinedButton(
                onClick = onClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (connected) StreamSuccess else StreamPurple
                )
            ) {
                Text(if (connected) "✓ Conectada" else "Conectar")
            }
        }
    }
}

private fun buildRtmpEndpoint(server: String, key: String): String {
    val cleanServer = server.trim().trimEnd('/')
    val cleanKey = key.trim().trim('/')
    if (cleanServer.isBlank() || cleanKey.isBlank()) return ""
    return "$cleanServer/$cleanKey"
}

private fun formatTime(seconds: Long): String =
    String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)

private val StreamPurple = Color(0xFF6C3BFF)
private val StreamPurpleDark = Color(0xFF4D25C7)
private val StreamCyan = Color(0xFF00A8D6)
private val StreamDanger = Color(0xFFE63B5B)
private val StreamSuccess = Color(0xFF19A974)
private val StreamBackground = Color(0xFFF5F3F9)

@Composable
private fun StreamTotalTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = StreamPurple,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE9E1FF),
        onPrimaryContainer = StreamPurpleDark,
        secondary = StreamCyan,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDDF7FF),
        onSecondaryContainer = Color(0xFF004E63),
        tertiary = StreamSuccess,
        error = StreamDanger,
        background = StreamBackground,
        surface = Color.White
    )
    MaterialTheme(colorScheme = colors, content = content)
}