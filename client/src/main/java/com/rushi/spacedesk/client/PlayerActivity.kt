package com.rushi.spacedesk.client

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.rushi.spacedesk.client.media.VideoDecoder
import com.rushi.spacedesk.client.net.ControlClient
import com.rushi.spacedesk.client.net.VideoReceiver
import com.rushi.spacedesk.shared.ControlMessage
import com.rushi.spacedesk.shared.NavAction
import com.rushi.spacedesk.shared.RemoteInputEvent
import com.rushi.spacedesk.shared.TouchAction
import kotlin.concurrent.thread

/**
 * Full-screen remote display: decodes the host's video onto a SurfaceView
 * (sized to the video's aspect ratio, centered) and forwards touch/nav/text
 * input back over the control channel.
 */
class PlayerActivity : ComponentActivity(), ControlClient.Listener {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
    }

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private var control: ControlClient? = null
    private var receiver: VideoReceiver? = null
    private var decoder: VideoDecoder? = null

    // Stream geometry (set when VideoConfig arrives).
    @Volatile
    private var videoWidth = 0

    @Volatile
    private var videoHeight = 0

    @Volatile
    private var surfaceReady = false

    @Volatile
    private var pendingConfigFrame: ByteArray? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        root.addView(buildOverlay())
        setContentView(root)
        hideSystemBars()

        // Re-fit the video whenever our size changes (e.g. device rotation).
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if (r - l != or2 - ol || b - t != ob - ot) applyAspectFit()
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                maybeStartDecoder()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
            }
        })

        surfaceView.setOnTouchListener { _, event -> handleTouch(event) }

        val host = intent.getStringExtra(EXTRA_HOST) ?: return finish()
        val port = intent.getIntExtra(EXTRA_PORT, 0)

        thread(name = "connect") {
            try {
                receiver = VideoReceiver { data, _, config -> onVideoFrame(data, config) }
                control = ControlClient(host, port, this).also { it.connect(Build.MODEL) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    // ---------------- ControlClient.Listener ----------------

    override fun onHelloAck(ack: ControlMessage.HelloAck) {
        if (!ack.inputAllowed) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Host input is off — enable the SpaceDesk accessibility service on the host",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        // Ask for the host's native resolution (long side both ways: the host
        // fits its own aspect ratio inside this box without downscaling).
        val metrics = resources.displayMetrics
        val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
        control?.requestStream(
            udpPort = receiver?.localPort ?: return,
            maxWidth = longSide,
            maxHeight = longSide,
            fps = 30,
            bitrateBps = 6_000_000,
        )
    }

    override fun onVideoConfig(config: ControlMessage.VideoConfig) {
        videoWidth = config.width
        videoHeight = config.height
        runOnUiThread {
            applyAspectFit()
            maybeStartDecoder()
        }
    }

    override fun onDisconnected(reason: String) {
        runOnUiThread {
            Toast.makeText(this, "Disconnected: $reason", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ---------------- video path ----------------

    /**
     * Sizes the SurfaceView so the video keeps its aspect ratio, centered.
     * MediaCodec scales output to fill the surface, so the surface itself
     * must have the video's shape — this also makes touch mapping trivial.
     */
    private fun applyAspectFit() {
        if (videoWidth == 0 || videoHeight == 0 || root.width == 0 || root.height == 0) return
        val scale = minOf(
            root.width.toFloat() / videoWidth,
            root.height.toFloat() / videoHeight,
        )
        val w = (videoWidth * scale).toInt()
        val h = (videoHeight * scale).toInt()
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            surfaceView.layoutParams = lp
        }
    }

    private fun onVideoFrame(data: ByteArray, config: Boolean) {
        val d = decoder
        if (d == null) {
            if (config) pendingConfigFrame = data
            return
        }
        d.submit(data, config)
    }

    private fun maybeStartDecoder() {
        if (decoder != null || !surfaceReady || videoWidth == 0) return
        decoder = VideoDecoder(videoWidth, videoHeight, surfaceView.holder.surface).also { d ->
            d.start()
            pendingConfigFrame?.let { d.submit(it, true) }
            pendingConfigFrame = null
        }
    }

    // ---------------- input path ----------------

    private fun handleTouch(event: MotionEvent): Boolean {
        val w = surfaceView.width.toFloat()
        val h = surfaceView.height.toFloat()
        if (w <= 0 || h <= 0) return true

        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchAction.DOWN
            MotionEvent.ACTION_MOVE -> TouchAction.MOVE
            MotionEvent.ACTION_UP -> TouchAction.UP
            MotionEvent.ACTION_CANCEL -> TouchAction.CANCEL
            else -> return true
        }

        // The surface IS the video (aspect-fit), so normalization is direct.
        val x = (event.x / w).coerceIn(0f, 1f)
        val y = (event.y / h).coerceIn(0f, 1f)

        control?.sendInput(
            listOf(
                RemoteInputEvent.Touch(
                    action = action,
                    pointerId = 0,
                    x = x,
                    y = y,
                    pressure = event.pressure,
                    timeMs = SystemClock.uptimeMillis(),
                ),
            ),
        )
        return true
    }

    private fun buildOverlay(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            alpha = 0.7f
        }

        fun navButton(label: String, action: NavAction) = Button(this).apply {
            text = label
            setOnClickListener { control?.sendInput(listOf(RemoteInputEvent.Nav(action))) }
        }
        bar.addView(navButton("◁", NavAction.BACK))
        bar.addView(navButton("○", NavAction.HOME))
        bar.addView(navButton("□", NavAction.RECENTS))

        val textEntry = EditText(this).apply {
            hint = "Send text…"
            setSingleLine()
        }
        val sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener {
                val t = textEntry.text.toString()
                if (t.isNotEmpty()) {
                    control?.sendInput(listOf(RemoteInputEvent.Text(t)))
                    textEntry.text.clear()
                }
            }
        }
        bar.addView(textEntry, LinearLayout.LayoutParams(400, LinearLayout.LayoutParams.WRAP_CONTENT))
        bar.addView(sendButton)

        return FrameLayout(this).apply {
            addView(
                bar,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ),
            )
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.hide(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onDestroy() {
        control?.close()
        control = null
        decoder?.stop()
        decoder = null
        receiver?.stop()
        receiver = null
        super.onDestroy()
    }
}
