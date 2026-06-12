package com.rushi.spacedesk.client.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue

/**
 * H.264 decoder rendering straight to a Surface.
 * Feed it the codec-config frame (SPS/PPS) first, then access units.
 */
class VideoDecoder(
    private val width: Int,
    private val height: Int,
    private val surface: Surface,
) {
    companion object {
        private const val TAG = "VideoDecoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private class EncodedUnit(val data: ByteArray, val config: Boolean)

    private val queue = LinkedBlockingQueue<EncodedUnit>(60)
    private var codec: MediaCodec? = null

    @Volatile
    private var running = false
    private var feedThread: Thread? = null
    private var drainThread: Thread? = null

    fun start() {
        val format = MediaFormat.createVideoFormat(MIME, width, height)
        codec = MediaCodec.createDecoderByType(MIME).also {
            it.configure(format, surface, null, 0)
            it.start()
        }
        running = true
        feedThread = Thread(::feedLoop, "VideoDecoder-feed").also { it.start() }
        drainThread = Thread(::drainLoop, "VideoDecoder-drain").also { it.start() }
    }

    fun submit(data: ByteArray, config: Boolean) {
        if (!queue.offer(EncodedUnit(data, config))) {
            // Queue full: drop oldest to keep latency bounded.
            queue.poll()
            queue.offer(EncodedUnit(data, config))
        }
    }

    private fun feedLoop() {
        val c = codec ?: return
        while (running) {
            val unit = try {
                queue.take()
            } catch (_: InterruptedException) {
                break
            }
            try {
                val index = c.dequeueInputBuffer(10_000)
                if (index < 0) continue
                val buffer = c.getInputBuffer(index) ?: continue
                buffer.clear()
                buffer.put(unit.data)
                val flags = if (unit.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                c.queueInputBuffer(index, 0, unit.data.size, System.nanoTime() / 1000, flags)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "feed loop stopping", e)
                break
            }
        }
    }

    private fun drainLoop() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val index = c.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) c.releaseOutputBuffer(index, true) // render
            } catch (e: IllegalStateException) {
                break
            }
        }
    }

    fun stop() {
        running = false
        feedThread?.interrupt()
        feedThread?.join(1000)
        drainThread?.join(1000)
        try {
            codec?.stop()
        } catch (_: IllegalStateException) {
        }
        codec?.release()
        codec = null
    }
}
