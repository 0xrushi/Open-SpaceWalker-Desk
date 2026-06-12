package com.rushi.spacedesk.host.net

import android.util.Log
import com.rushi.spacedesk.shared.ControlMessage
import com.rushi.spacedesk.shared.RemoteInputEvent
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * TCP control server: accepts one client at a time, speaks newline-delimited
 * JSON [ControlMessage]s. A new connection replaces the existing one.
 */
class ControlServer(
    private val port: Int,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /** Return the HelloAck to send for this client. */
        fun onHello(hello: ControlMessage.Hello): ControlMessage.HelloAck

        /** Client asked for video; [clientAddress] is where UDP packets go. */
        fun onStartStream(req: ControlMessage.StartStream, clientAddress: InetAddress, session: ClientSession)

        fun onInput(events: List<RemoteInputEvent>)
        fun onClientDisconnected()
    }

    class ClientSession(private val socket: Socket, private val writer: BufferedWriter) {
        val remoteAddress: InetAddress = socket.inetAddress

        @Synchronized
        fun send(msg: ControlMessage) {
            try {
                writer.write(ControlMessage.encode(msg))
                writer.write("\n")
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "send failed", e)
            }
        }

        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "ControlServer"
    }

    @Volatile
    private var running = true
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var currentSession: ClientSession? = null

    private val acceptThread = Thread({
        try {
            val server = ServerSocket(port)
            serverSocket = server
            while (running) {
                val socket = server.accept()
                currentSession?.close() // single-client v1: newest wins
                Thread({ serveClient(socket) }, "ControlServer-client").start()
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "accept loop died", e)
        }
    }, "ControlServer-accept")

    fun start() = acceptThread.start()

    private fun serveClient(socket: Socket) {
        socket.tcpNoDelay = true
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val session = ClientSession(socket, writer)
        currentSession = session
        try {
            while (running && !socket.isClosed) {
                val line = reader.readLine() ?: break
                when (val msg = runCatching { ControlMessage.decode(line) }.getOrNull() ?: continue) {
                    is ControlMessage.Hello -> session.send(callbacks.onHello(msg))
                    is ControlMessage.StartStream -> callbacks.onStartStream(msg, socket.inetAddress, session)
                    is ControlMessage.Input -> callbacks.onInput(msg.events)
                    is ControlMessage.Ping -> session.send(ControlMessage.Pong)
                    is ControlMessage.Bye -> break
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "client connection ended: ${e.message}")
        } finally {
            session.close()
            if (currentSession === session) {
                currentSession = null
                callbacks.onClientDisconnected()
            }
        }
    }

    fun broadcast(msg: ControlMessage) {
        currentSession?.send(msg)
    }

    fun stop() {
        running = false
        currentSession?.send(ControlMessage.Bye("host stopped sharing"))
        currentSession?.close()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }
}
