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
import java.util.concurrent.LinkedBlockingQueue

/**
 * TCP control connection to the host: handshake, stream request,
 * input forwarding, and incoming message dispatch.
 *
 * All writes go through a dedicated sender thread, so [send] is safe to call
 * from any thread — including the UI thread (Android forbids network I/O there).
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
    private val outQueue = LinkedBlockingQueue<ControlMessage>(256)
    private var sendThread: Thread? = null

    @Volatile
    private var running = false

    /** Connects and starts the read/send loops. Call from a background thread. */
    fun connect(deviceName: String) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket = s
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
        running = true

        sendThread = Thread(::sendLoop, "ControlClient-send").also { it.start() }
        send(ControlMessage.Hello(deviceName = deviceName))
        Thread({ readLoop(s) }, "ControlClient-read").start()
    }

    private fun sendLoop() {
        while (true) {
            val msg = try {
                outQueue.take()
            } catch (_: InterruptedException) {
                break
            }
            try {
                writer?.apply {
                    write(ControlMessage.encode(msg))
                    write("\n")
                    flush()
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "send failed", e)
                break
            }
            if (msg is ControlMessage.Bye) break
        }
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
            if (running) listener.onDisconnected("connection closed")
        } catch (e: Exception) {
            if (running) {
                Log.i(TAG, "read loop ended: ${e.message}")
                listener.onDisconnected(e.message ?: "connection error")
            }
        }
    }

    /** Non-blocking; safe from any thread. */
    fun send(msg: ControlMessage) {
        if (!outQueue.offer(msg)) Log.w(TAG, "outbound queue full, dropping ${msg::class.simpleName}")
    }

    fun requestStream(udpPort: Int, maxWidth: Int, maxHeight: Int, fps: Int, bitrateBps: Int) =
        send(ControlMessage.StartStream(udpPort, maxWidth, maxHeight, fps, bitrateBps))

    fun sendInput(events: List<RemoteInputEvent>) {
        if (events.isNotEmpty()) send(ControlMessage.Input(events))
    }

    fun close() {
        if (!running) return
        running = false
        outQueue.offer(ControlMessage.Bye())
        Thread({
            sendThread?.join(300)
            runCatching { socket?.close() }
            socket = null
            writer = null
        }, "ControlClient-close").start()
    }
}
