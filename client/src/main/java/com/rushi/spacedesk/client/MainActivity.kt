package com.rushi.spacedesk.client

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rushi.spacedesk.client.net.NsdDiscovery
import com.rushi.spacedesk.shared.Protocol

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DiscoveryScreen { host, port, spaceWalker ->
                    val target = if (spaceWalker) SpaceWalkerActivity::class.java
                                 else PlayerActivity::class.java
                    startActivity(
                        Intent(this, target)
                            .putExtra(PlayerActivity.EXTRA_HOST, host)
                            .putExtra(PlayerActivity.EXTRA_PORT, port),
                    )
                }
            }
        }
    }
}

private data class DiscoveredHost(val name: String, val host: String, val port: Int)

@Composable
private fun DiscoveryScreen(onConnect: (host: String, port: Int, spaceWalker: Boolean) -> Unit) {
    val context = LocalContext.current
    val hosts = remember { mutableStateMapOf<String, DiscoveredHost>() }
    var manualIp by remember { mutableStateOf("") }
    var spaceWalker by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val discovery = NsdDiscovery(
            context,
            onHostFound = { name, host, port -> hosts[name] = DiscoveredHost(name, host, port) },
            onHostLost = { name -> hosts.remove(name) },
        )
        discovery.start()
        onDispose { discovery.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("SpaceDesk Client", style = MaterialTheme.typography.headlineMedium)

        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Switch(
                checked = spaceWalker,
                onCheckedChange = { spaceWalker = it },
            )
            Column {
                Text("SpaceWalker mode", style = MaterialTheme.typography.titleMedium)
                Text("Up to 3 screens floating in 3D (for XR glasses)",
                     style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("Hosts on your network", style = MaterialTheme.typography.titleMedium)

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(hosts.values.toList()) { h ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConnect(h.host, h.port, spaceWalker) },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(h.name, style = MaterialTheme.typography.titleMedium)
                        Text("${h.host}:${h.port}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (hosts.isEmpty()) {
                items(listOf("searching")) {
                    Text("Searching… make sure the host app is sharing on the same Wi-Fi.")
                }
            }
        }

        Text("Or connect by IP", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = manualIp,
                onValueChange = { manualIp = it },
                modifier = Modifier.weight(1f),
                label = { Text("Host IP") },
                singleLine = true,
            )
            Button(
                onClick = { if (manualIp.isNotBlank()) onConnect(manualIp.trim(), Protocol.DEFAULT_CONTROL_PORT, spaceWalker) },
            ) { Text("Connect") }
        }
    }
}
