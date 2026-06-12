package com.rushi.spacedesk.client.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.rushi.spacedesk.shared.Protocol

/** Discovers SpaceDesk hosts on the LAN via NSD (mDNS). */
class NsdDiscovery(
    context: Context,
    private val onHostFound: (name: String, host: String, port: Int) -> Unit,
    private val onHostLost: (name: String) -> Unit,
) {
    companion object {
        private const val TAG = "NsdDiscovery"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, error: Int) {
                Log.e(TAG, "discovery failed: $error")
            }

            override fun onStopDiscoveryFailed(serviceType: String, error: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(
                    info,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, error: Int) {}
                        override fun onServiceResolved(i: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val host = i.host?.hostAddress ?: return
                            onHostFound(i.serviceName, host, i.port)
                        }
                    },
                )
            }

            override fun onServiceLost(info: NsdServiceInfo) = onHostLost(info.serviceName)
        }
        discoveryListener = listener
        nsdManager.discoverServices(Protocol.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        discoveryListener = null
    }
}
