package com.rushi.spacedesk.host

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rushi.spacedesk.host.capture.ScreenCaptureService
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                startForegroundService(ScreenCaptureService.startIntent(this, result.resultCode, data))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                HostScreen(
                    onStart = {
                        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        projectionLauncher.launch(mpm.createScreenCaptureIntent())
                    },
                    onStop = { startService(ScreenCaptureService.stopIntent(this)) },
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
            }
        }
    }
}

@Composable
private fun HostScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val isSharing by HostState.isSharing.collectAsState()
    val client by HostState.connectedClient.collectAsState()
    val inputAllowed by HostState.inputAllowed.collectAsState()
    val streamInfo by HostState.streamInfo.collectAsState()
    val accessibilityOn by HostState.accessibilityRunning.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("SpaceDesk Host", style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (isSharing) "● Sharing" else "○ Not sharing", style = MaterialTheme.typography.titleMedium)
                Text("This device's IP: ${localIpAddresses() ?: "unknown"}")
                Text("Client: ${client ?: "none"}")
                streamInfo?.let { Text(it) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = inputAllowed, onCheckedChange = { HostState.inputAllowed.value = it })
            Spacer(Modifier.width(12.dp))
            Text("Allow remote input")
        }

        if (!accessibilityOn) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Remote input needs the SpaceDesk accessibility service.")
                    OutlinedButton(onClick = onOpenAccessibilitySettings) {
                        Text("Open Accessibility Settings")
                    }
                }
            }
        }

        if (isSharing) {
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop sharing") }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start sharing") }
        }
    }
}

/** All IPv4 addresses (Wi-Fi, USB tethering rndis, …), comma-separated. */
private fun localIpAddresses(): String? =
    NetworkInterface.getNetworkInterfaces().toList().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress }
        .mapNotNull { it.hostAddress }
        .joinToString(", ")
        .ifEmpty { null }
