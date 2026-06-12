package com.rushi.spacedesk.client.net

import com.rushi.spacedesk.shared.FrameAssembler
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Binds a UDP socket, reassembles incoming video packets into frames,
 * and hands complete frames to [onFrame].
 */
class VideoReceiver(
    private val onFrame: (data: ByteArray, keyframe: Boolean, config: Boolean) -> Unit,
) {
    private val socket = DatagramSocket() // ephemeral port
    val localPort: Int get() = socket.localPort

    private val assembler = FrameAssembler(onFrame)

    @Volatile
    private var running = true

    private val thread = Thread({
        val buffer = ByteArray(65_536)
        val packet = DatagramPacket(buffer, buffer.size)
        while (running) {
            try {
                socket.receive(packet)
                assembler.onPacket(packet.data, packet.length)
            } catch (_: Exception) {
                if (running) continue else break
            }
        }
    }, "VideoReceiver").also { it.start() }

    fun stop() {
        running = false
        socket.close()
    }
}
