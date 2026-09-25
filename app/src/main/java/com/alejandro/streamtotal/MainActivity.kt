package com.alejandro.streamtotal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

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
                if (permissionsGranted) CameraStudio()
                else PermissionScreen {
                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
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
        Button(onClick = onRequest, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C4DFF))) {
            Text("PERMITIR ACCESO")
        }
    }
}

@Composable
private fun CameraStudio() {
    val context = LocalContext.current
    var lensFacing by mutableStateOf(CameraSelector.LENS_FACING_BACK)
    var microphoneEnabled by mutableStateOf(true)

    Column(Modifier.fillMaxSize().background(Color(0xFFF7F7FA))) {
        Box(
            Modifier.fillMaxWidth().height(430.dp)
                .background(Color.Black, RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } },
                update = { view -> bindCamera(view, context, lensFacing) }
            )
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("StreamTotal", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(if (microphoneEnabled) "🎙️ Mic ON" else "🔇 Mic OFF", color = Color.White)
            }
        }

        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Text("Estudio de transmisión", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(Modifier.weight(1f), onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                        CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                }) { Text("🔄 Cámara") }
                Button(Modifier.weight(1f), onClick = { microphoneEnabled = !microphoneEnabled }) {
                    Text(if (microphoneEnabled) "🎙️ Mic" else "🔇 Mic")
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Calidad: 720p")
            Text("RTMP: próximo paso", color = Color.Gray)
            Spacer(Modifier.weight(1f))
            Button(
                Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp),
                onClick = { },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C4DFF))
            ) { Text("🔴 INICIAR TRANSMISIÓN") }
        }
    }
}

private fun bindCamera(previewView: PreviewView, context: android.content.Context, lensFacing: Int) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        val provider = future.get()
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.unbindAll()
            provider.bindToLifecycle(context as ComponentActivity, selector, preview)
        } catch (_: Exception) { }
    }, ContextCompat.getMainExecutor(context))
}

@Composable
private fun StreamTotalTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
