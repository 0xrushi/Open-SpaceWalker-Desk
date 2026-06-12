package com.rushi.spacedesk.shared

import java.nio.ByteBuffer

/**
 * UDP video packetization.
 *
 * Each encoded frame (H.264 access unit, or codec config = SPS/PPS) is split into
 * packets of at most [Protocol.MAX_VIDEO_PAYLOAD] bytes with this 16-byte header:
 *
 *   magic      u16  0x5D5C
 *   flags      u8   bit0 = keyframe, bit1 = codec config
 *   reserved   u8
 *   frameId    u32  monotonically increasing per frame
 *   seq        u16  packet index within frame
 *   total      u16  packet count for frame
 *   payloadLen u16
 *   reserved   u16
 */
object VideoPacket {
    const val MAGIC: Int = 0x5D5C
    const val HEADER_SIZE = 16
    const val FLAG_KEYFRAME = 0x01
    const val FLAG_CONFIG = 0x02

    data class Header(
        val flags: Int,
        val frameId: Long,
        val seq: Int,
        val total: Int,
        val payloadLen: Int,
    ) {
        val isKeyframe get() = flags and FLAG_KEYFRAME != 0
        val isConfig get() = flags and FLAG_CONFIG != 0
    }

    /** Splits [frame] into ready-to-send datagram payloads. */
    fun packetize(frame: ByteArray, frameId: Long, keyframe: Boolean, config: Boolean): List<ByteArray> {
        val flags = (if (keyframe) FLAG_KEYFRAME else 0) or (if (config) FLAG_CONFIG else 0)
        val total = ((frame.size + Protocol.MAX_VIDEO_PAYLOAD - 1) / Protocol.MAX_VIDEO_PAYLOAD).coerceAtLeast(1)
        val packets = ArrayList<ByteArray>(total)
        for (seq in 0 until total) {
            val offset = seq * Protocol.MAX_VIDEO_PAYLOAD
            val len = minOf(Protocol.MAX_VIDEO_PAYLOAD, frame.size - offset)
            val buf = ByteBuffer.allocate(HEADER_SIZE + len)
            buf.putShort(MAGIC.toShort())
            buf.put(flags.toByte())
            buf.put(0)
            buf.putInt(frameId.toInt())
            buf.putShort(seq.toShort())
            buf.putShort(total.toShort())
            buf.putShort(len.toShort())
            buf.putShort(0)
            buf.put(frame, offset, len)
            packets.add(buf.array())
        }
        return packets
    }

    /** Parses a datagram; returns null if it is not a valid video packet. */
    fun parseHeader(data: ByteArray, length: Int): Header? {
        if (length < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(data, 0, length)
        if (buf.short.toInt() and 0xFFFF != MAGIC) return null
        val flags = buf.get().toInt() and 0xFF
        buf.get() // reserved
        val frameId = (buf.int.toLong()) and 0xFFFFFFFFL
        val seq = buf.short.toInt() and 0xFFFF
        val total = buf.short.toInt() and 0xFFFF
        val payloadLen = buf.short.toInt() and 0xFFFF
        if (HEADER_SIZE + payloadLen > length || seq >= total) return null
        return Header(flags, frameId, seq, total, payloadLen)
    }
}

/**
 * Reassembles frames from out-of-order/lossy UDP packets.
 * Incomplete frames falling outside [Protocol.FRAME_REORDER_WINDOW] are dropped.
 */
class FrameAssembler(private val onFrame: (frame: ByteArray, keyframe: Boolean, config: Boolean) -> Unit) {

    private class Partial(total: Int, val flags: Int) {
        val parts = arrayOfNulls<ByteArray>(total)
        var received = 0
        fun isComplete() = received == parts.size
        fun assemble(): ByteArray {
            var size = 0
            for (p in parts) size += p!!.size
            val out = ByteArray(size)
            var off = 0
            for (p in parts) {
                p!!.copyInto(out, off); off += p.size
            }
            return out
        }
    }

    private val pending = HashMap<Long, Partial>()
    private var newestFrameId = -1L

    @Synchronized
    fun onPacket(data: ByteArray, length: Int) {
        val h = VideoPacket.parseHeader(data, length) ?: return
        if (h.frameId > newestFrameId) newestFrameId = h.frameId

        val partial = pending.getOrPut(h.frameId) { Partial(h.total, h.flags) }
        if (h.total != partial.parts.size) return // corrupt / mismatched
        if (partial.parts[h.seq] == null) {
            partial.parts[h.seq] = data.copyOfRange(VideoPacket.HEADER_SIZE, VideoPacket.HEADER_SIZE + h.payloadLen)
            partial.received++
        }
        if (partial.isComplete()) {
            pending.remove(h.frameId)
            onFrame(
                partial.assemble(),
                partial.flags and VideoPacket.FLAG_KEYFRAME != 0,
                partial.flags and VideoPacket.FLAG_CONFIG != 0,
            )
        }

        // Garbage-collect stale incomplete frames.
        if (pending.size > Protocol.FRAME_REORDER_WINDOW) {
            val cutoff = newestFrameId - Protocol.FRAME_REORDER_WINDOW
            pending.keys.removeAll { it < cutoff }
        }
    }
}
