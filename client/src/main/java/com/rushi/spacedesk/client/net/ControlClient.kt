package com.rushi.spacedesk.client.net

import android.util.Log
import com.rushi.spacedesk.shared.ControlMessage
import com.rushi.spacedesk.shared.RemoteInputEvent
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP control connection to the host: handshake, stream request,
 * input forwarding, and incoming message dispatch.
 */
class ControlClient(
    private val host: String,
    private val port: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onHelloAck(ack: ControlMessage.HelloAck)
        fun onVideoConfig(config: ControlMessage.VideoConfig)
        fun onDisconnected(reason: String)
    }

    companion object {
        private const val TAG = "ControlClient"
        private const val CONNECT_TIMEOUT_MS = 5000
    }

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null

    @Volatile
    private var running = false

    /** Connects and starts the read loop. Call from a background thread. */
    fun connect(deviceName: String) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket = s
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
        running = true

        send(ControlMessage.Hello(deviceName = deviceName))

        Thread({ readLoop(s) }, "ControlClient-read").start()
    }

    private fun readLoop(s: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (running) {
                val line = reader.readLine() ?: break
                when (val msg = runCatching { ControlMessage.decode(line) }.getOrNull() ?: continue) {
                    is ControlMessage.HelloAck -> listener.onHelloAck(msg)
                    is ControlMessage.VideoConfig -> listener.onVideoConfig(msg)
                    is ControlMessage.Ping -> send(ControlMessage.Pong)
                    is ControlMessage.Bye -> {
                        listener.onDisconnected(msg.reason.ifEmpty { "host disconnected" })
                        return
                    }
                    else -> {}
                }
            }
            listener.onDisconnected("connection closed")
        } catch (e: Exception) {
            if (running) {
                Log.i(TAG, "read loop ended: ${e.message}")
                listener.onDisconnected(e.message ?: "connection error")
            }
        }
    }

    @Synchronized
    fun send(msg: ControlMessage) {
        try {
            writer?.apply {
                write(ControlMessage.encode(msg))
                write("\n")
                flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
        }
    }

    fun requestStream(udpPort: Int, maxWidth: Int, maxHeight: Int, fps: Int, bitrateBps: Int) =
        send(ControlMessage.StartStream(udpPort, maxWidth, maxHeight, fps, bitrateBps))

    fun sendInput(events: List<RemoteInputEvent>) {
        if (events.isNotEmpty()) send(ControlMessage.Input(events))
    }

    fun close() {
        running = false
        runCatching { send(ControlMessage.Bye()) }
        runCatching { socket?.close() }
        socket = null
        writer = null
    }
}
