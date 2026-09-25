package com.alejandro.streamtotal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionsGranted =
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionsGranted =
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        setContent {
            StreamTotalTheme {
                StreamTotalHome(
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.CAMERA,
                                Manifest.permission.RECORD_AUDIO
                            )
                        )
                    }
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun StreamTotalHome(
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit
) {
    val purple = Color(0xFF6C4DFF)
    val background = Color(0xFFF7F7FA)

    Surface(modifier = Modifier.fillMaxSize(), color = background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(purple)
                    .padding(top = 28.dp, start = 22.dp, end = 22.dp, bottom = 24.dp)
            ) {
                Column {
                    Text(
                        text = "StreamTotal",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Tu transmisión. Todas tus plataformas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = .88f)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Preparar transmisión", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (permissionsGranted)
                                "Cámara y micrófono listos."
                            else
                                "Permite cámara y micrófono para comenzar.",
                            color = Color.DarkGray
                        )
                        Spacer(Modifier.height(16.dp))
                        if (!permissionsGranted) {
                            Button(
                                onClick = onRequestPermissions,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = purple)
                            ) {
                                Text("PERMITIR CÁMARA Y MICRÓFONO")
                            }
                        }
                    }
                }

                Text("Plataformas", style = MaterialTheme.typography.titleMedium)
                PlatformRow("YouTube", "Próximamente")
                PlatformRow("Twitch", "Próximamente")
                PlatformRow("Facebook", "Próximamente")

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = { },
                    enabled = permissionsGranted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = purple,
                        disabledContainerColor = Color.LightGray
                    )
                ) {
                    Text("INICIAR TRANSMISIÓN")
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PlatformRow(name: String, status: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = {}) { Text(status) }
        }
    }
}

@androidx.compose.runtime.Composable
private fun StreamTotalTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    MaterialTheme(content = content)
}
