package com.rushi.spacedesk.host.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.rushi.spacedesk.shared.Protocol

/** Advertises the host's control server on the LAN via NSD (mDNS). */
class NsdAdvertiser(context: Context) {

    companion object {
        private const val TAG = "NsdAdvertiser"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "${Protocol.NSD_SERVICE_NAME_PREFIX}-${Build.MODEL}"
            serviceType = Protocol.NSD_SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {
                Log.i(TAG, "Registered ${i.serviceName}")
            }

            override fun onRegistrationFailed(i: NsdServiceInfo, error: Int) {
                Log.e(TAG, "Registration failed: $error")
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, error: Int) {}
        }
        listener = l
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun unregister() {
        listener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        listener = null
    }
}
