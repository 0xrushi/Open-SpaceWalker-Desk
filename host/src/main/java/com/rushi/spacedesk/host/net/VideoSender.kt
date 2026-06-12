package com.rushi.spacedesk.host.net

import com.rushi.spacedesk.shared.VideoPacket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends encoded frames as UDP packets to a single client.
 * Runs on its own thread so a slow network never blocks the encoder;
 * delta frames are dropped when the queue backs up.
 */
class VideoSender(private val target: InetAddress, private val targetPort: Int) {

    private class QueuedFrame(val data: ByteArray, val keyframe: Boolean, val config: Boolean)

    private val socket = DatagramSocket()
    private val queue = LinkedBlockingQueue<QueuedFrame>(30)
    private val frameId = AtomicLong(0)

    @Volatile
    private var running = true

    private val thread = Thread({
        while (running) {
            val frame = try {
                queue.take()
            } catch (_: InterruptedException) {
                break
            }
            val id = frameId.getAndIncrement()
            for (packet in VideoPacket.packetize(frame.data, id, frame.keyframe, frame.config)) {
                try {
                    socket.send(DatagramPacket(packet, packet.size, target, targetPort))
                } catch (_: Exception) {
                    // Best effort; UDP loss is handled by the client's frame assembler.
                }
            }
        }
    }, "VideoSender").also { it.start() }

    fun submit(data: ByteArray, keyframe: Boolean, config: Boolean) {
        val frame = QueuedFrame(data, keyframe, config)
        if (config || keyframe) {
            // Never drop config/keyframes: make room if needed.
            while (!queue.offer(frame)) queue.poll()
        } else {
            queue.offer(frame) // dropped if full
        }
    }

    fun stop() {
        running = false
        thread.interrupt()
        socket.close()
    }
}
