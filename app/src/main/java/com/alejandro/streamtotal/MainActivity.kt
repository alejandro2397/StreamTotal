package com.alejandro.streamtotal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView

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
                if (permissionsGranted) RtmpStudio()
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
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C4DFF))
        ) { Text("PERMITIR ACCESO") }
    }
}

@Composable
private fun RtmpStudio() {
    val context = LocalContext.current
    var rtmpUrl by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Vista previa lista") }
    var isStreaming by remember { mutableStateOf(false) }
    var rtmpCamera by remember { mutableStateOf<RtmpCamera2?>(null) }

    val checker = remember {
        object : ConnectChecker {
            override fun onConnectionStarted(url: String) {
                status = "Conectando..."
            }

            override fun onConnectionSuccess() {
                status = "🔴 EN VIVO"
                isStreaming = true
            }

            override fun onConnectionFailed(reason: String) {
                status = "Error de conexión"
                isStreaming = false
            }

            override fun onNewBitrate(bitrate: Long) {
                if (isStreaming) status = "🔴 EN VIVO · ${bitrate / 1000} kbps"
            }

            override fun onDisconnect() {
                status = "Desconectado"
                isStreaming = false
            }

            override fun onAuthError() {
                status = "Error de autenticación"
                isStreaming = false
            }

            override fun onAuthSuccess() {
                status = "Autenticación correcta"
            }
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

    Column(Modifier.fillMaxSize().background(Color(0xFFF7F7FA))) {
        Box(
            Modifier.fillMaxWidth().height(400.dp)
                .background(Color.Black, RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    OpenGlView(ctx).also { view ->
                        val camera = RtmpCamera2(view, checker)
                        rtmpCamera = camera
                        view.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                camera.startPreview()
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) = Unit

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                if (camera.isStreaming) camera.stopStream()
                                camera.stopPreview()
                            }
                        })
                    }
                }
            )

            Text(
                text = status,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
            )
        }

        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Transmisión RTMP", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = rtmpUrl,
                onValueChange = { rtmpUrl = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("URL RTMP + Stream Key") },
                placeholder = { Text("rtmps://...") },
                enabled = !isStreaming
            )

            Spacer(Modifier.height(10.dp))
            Text(
                "Ejemplo: rtmps://servidor/live/tu_clave",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            Spacer(Modifier.height(14.dp))

            if (!isStreaming) {
                Button(
                    onClick = {
                        val camera = rtmpCamera ?: return@Button
                        val url = rtmpUrl.trim()
                        if (url.isEmpty()) {
                            status = "Introduce una URL RTMP"
                            return@Button
                        }

                        val videoReady = camera.prepareVideo()
                        val audioReady = camera.prepareAudio()

                        if (videoReady && audioReady) {
                            status = "Conectando..."
                            camera.startStream(url)
                        } else {
                            status = "Este dispositivo no pudo preparar el encoder"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C4DFF))
                ) {
                    Text("🔴 INICIAR TRANSMISIÓN")
                }
            } else {
                Button(
                    onClick = {
                        rtmpCamera?.stopStream()
                        status = "Transmisión detenida"
                        isStreaming = false
                    },
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("⏹ DETENER TRANSMISIÓN")
                }
            }
        }
    }
}

@Composable
private fun StreamTotalTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
