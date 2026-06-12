package com.rushi.spacedesk.host.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface

/**
 * Hardware H.264 encoder fed by a Surface (which the virtual display renders into).
 * Emits complete encoded frames on a dedicated drain thread.
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateBps: Int,
    private val onEncodedFrame: (data: ByteArray, keyframe: Boolean, config: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private var codec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    @Volatile
    private var running = false
    private var drainThread: Thread? = null

    fun start() {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // Low-latency hints (honored where supported).
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        codec = MediaCodec.createEncoderByType(MIME).also {
            it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = it.createInputSurface()
            it.start()
        }
        running = true
        drainThread = Thread(::drainLoop, "VideoEncoder-drain").also { it.start() }
    }

    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: IllegalStateException) {
            Log.w(TAG, "requestKeyFrame failed", e)
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        val c = codec ?: return
        while (running) {
            val index = try {
                c.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                break
            }
            if (index < 0) continue
            try {
                val buffer = c.getOutputBuffer(index) ?: continue
                if (info.size > 0) {
                    val data = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(data, 0, info.size)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    onEncodedFrame(data, keyframe, config)
                }
            } finally {
                try {
                    c.releaseOutputBuffer(index, false)
                } catch (_: IllegalStateException) {
                }
            }
        }
    }

    fun stop() {
        running = false
        drainThread?.join(1000)
        drainThread = null
        try {
            codec?.stop()
        } catch (_: IllegalStateException) {
        }
        codec?.release()
        codec = null
        inputSurface?.release()
        inputSurface = null
    }
}
