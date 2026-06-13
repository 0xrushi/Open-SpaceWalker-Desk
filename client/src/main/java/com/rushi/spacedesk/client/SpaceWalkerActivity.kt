package com.rushi.spacedesk.client

import android.annotation.SuppressLint
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.rushi.spacedesk.client.media.VideoDecoder
import com.rushi.spacedesk.client.net.ControlClient
import com.rushi.spacedesk.client.net.VideoReceiver
import com.rushi.spacedesk.client.xr.ScreenNode
import com.rushi.spacedesk.client.xr.XRRenderer
import com.rushi.spacedesk.shared.ControlMessage
import com.rushi.spacedesk.shared.RemoteInputEvent
import com.rushi.spacedesk.shared.TouchAction
import kotlin.concurrent.thread
import kotlin.math.abs

private const val MAX_SCREENS = 3

/**
 * SpaceWalker mode: up to [MAX_SCREENS] host screens float on an arc in a 3D
 * workspace rendered with OpenGL (plug XR glasses into the phone and this
 * fills them). Lock mode: drag empty space to look around, touch a screen to
 * control the host (tap-through). Edit mode: drag screens along the arc /
 * up-down, pinch to resize.
 */
class SpaceWalkerActivity : ComponentActivity(), ControlClient.Listener {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
    }

    private inner class Screen(val id: Int) {
        val node = ScreenNode(id)
        val receiver = VideoReceiver { data, _, config -> onVideoFrame(this, data, config) }
        var decoder: VideoDecoder? = null
        @Volatile var pendingConfig: ByteArray? = null
        @Volatile var videoW = 0
        @Volatile var videoH = 0
    }

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: XRRenderer
    private lateinit var statusText: TextView
    private val mainHandler = Handler(Looper.getMainLooper())

    private var control: ControlClient? = null
    private val screens = LinkedHashMap<Int, Screen>()

    @Volatile private var inputAllowed = false
    private var editMode = false

    // touch state
    private var orbiting = false
    private var forwardingTo: ScreenNode? = null
    private var draggingNode: ScreenNode? = null
    private var lastX = 0f
    private var lastY = 0f
    private var pinchDist = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        renderer = XRRenderer(onSurfaceFor = { node -> mainHandler.post { maybeStartDecoder(node.id) } })
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        glView.setOnTouchListener { _, ev -> handleTouch(ev) }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(glView)
        root.addView(buildOverlay())
        setContentView(root)
        hideSystemBars()

        val host = intent.getStringExtra(EXTRA_HOST) ?: return finish()
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        thread(name = "connect") {
            try {
                control = ControlClient(host, port, this).also { it.connect("${Build.MODEL}-XR") }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    // ------------------------------------------------- screen management ----

    private fun addScreen() {
        if (screens.size >= MAX_SCREENS) {
            toast("Maximum $MAX_SCREENS screens")
            return
        }
        val id = (0 until MAX_SCREENS).first { it !in screens }
        val screen = Screen(id)
        // Spread screens along the arc: center, left, right.
        screen.node.yawDeg = when (screens.size) {
            0 -> 0f; 1 -> -42f; else -> 42f
        }
        screens[id] = screen
        renderer.nodes.add(screen.node)

        val metrics = resources.displayMetrics
        val long = maxOf(metrics.widthPixels, metrics.heightPixels)
        control?.send(
            ControlMessage.StartStream(
                videoUdpPort = screen.receiver.localPort,
                maxWidth = long, maxHeight = long,
                fps = 30, bitrateBps = 5_000_000,
                screenId = id,
            ),
        )
        updateStatus()
        if (id > 0) toast("Screen ${id + 1} requested — approve the capture dialog on the host")
    }

    private fun removeScreen() {
        val id = screens.keys.maxOrNull() ?: return
        if (id == 0) {
            toast("Screen 1 is the primary — exit instead")
            return
        }
        val screen = screens.remove(id) ?: return
        control?.send(ControlMessage.RemoveScreen(id))
        renderer.nodes.remove(screen.node)
        thread { screen.decoder?.stop(); screen.receiver.stop() }
        updateStatus()
    }

    private fun maybeStartDecoder(id: Int) {
        val screen = screens[id] ?: return
        val surface = screen.node.surface ?: return
        if (screen.decoder != null || screen.videoW == 0) return
        screen.node.surfaceTexture?.setDefaultBufferSize(screen.videoW, screen.videoH)
        screen.decoder = VideoDecoder(screen.videoW, screen.videoH, surface).also { d ->
            d.start()
            screen.pendingConfig?.let { d.submit(it, true) }
            screen.pendingConfig = null
        }
    }

    private fun onVideoFrame(screen: Screen, data: ByteArray, config: Boolean) {
        val d = screen.decoder
        if (d == null) {
            if (config) screen.pendingConfig = data
            return
        }
        d.submit(data, config)
    }

    // ------------------------------------------- ControlClient.Listener -----

    override fun onHelloAck(ack: ControlMessage.HelloAck) {
        inputAllowed = ack.inputAllowed
        mainHandler.post { addScreen() } // primary screen
    }

    override fun onVideoConfig(config: ControlMessage.VideoConfig) {
        val screen = screens[config.screenId] ?: return
        val changed = screen.videoW != config.width || screen.videoH != config.height
        if (changed && screen.decoder != null) {
            screen.decoder?.stop()
            screen.decoder = null
            screen.pendingConfig = null
        }
        screen.videoW = config.width
        screen.videoH = config.height
        screen.node.videoW = config.width
        screen.node.videoH = config.height
        mainHandler.post { maybeStartDecoder(config.screenId) }
    }

    override fun onDisconnected(reason: String) {
        runOnUiThread {
            Toast.makeText(this, "Disconnected: $reason", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // -------------------------------------------------------------- touch ---

    private fun handleTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x; lastY = ev.y
                val hit = renderer.pick(ev.x, ev.y, glView.width, glView.height)
                if (editMode) {
                    draggingNode = hit?.node
                    orbiting = hit == null
                } else if (hit != null && inputAllowed) {
                    forwardingTo = hit.node
                    sendTouch("DOWN", hit.node.id, hit.nx, hit.ny)
                } else {
                    orbiting = true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (editMode && draggingNode != null && ev.pointerCount == 2) {
                    pinchDist = dist(ev)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (editMode && draggingNode != null && ev.pointerCount == 2 && pinchDist > 0) {
                    val d = dist(ev)
                    draggingNode!!.scale = (draggingNode!!.scale * d / pinchDist).coerceIn(1f, 6f)
                    pinchDist = d
                    return true
                }
                val dx = ev.x - lastX
                val dy = ev.y - lastY
                lastX = ev.x; lastY = ev.y
                when {
                    forwardingTo != null -> {
                        val hit = renderer.pick(ev.x, ev.y, glView.width, glView.height,
                                                clampTo = forwardingTo)
                        if (hit != null) sendTouch("MOVE", hit.node.id, hit.nx, hit.ny)
                    }
                    draggingNode != null -> {
                        draggingNode!!.yawDeg += dx * 0.12f
                        draggingNode!!.heightY -= dy * 0.006f
                    }
                    orbiting -> {
                        renderer.camYaw += dx * 0.18f
                        renderer.camPitch = (renderer.camPitch + dy * 0.18f).coerceIn(-75f, 75f)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                forwardingTo?.let { node ->
                    val hit = renderer.pick(ev.x, ev.y, glView.width, glView.height, clampTo = node)
                    sendTouch("UP", node.id, hit?.nx ?: 0.5f, hit?.ny ?: 0.5f)
                }
                forwardingTo = null
                draggingNode = null
                orbiting = false
                pinchDist = 0f
            }
        }
        return true
    }

    private fun sendTouch(action: String, screenId: Int, nx: Float, ny: Float) {
        control?.sendInput(
            listOf(
                RemoteInputEvent.Touch(
                    action = TouchAction.valueOf(action),
                    pointerId = 0, x = nx, y = ny,
                    timeMs = SystemClock.uptimeMillis(),
                    screenId = screenId,
                ),
            ),
        )
    }

    private fun dist(ev: MotionEvent): Float {
        val dx = ev.getX(0) - ev.getX(1)
        val dy = ev.getY(0) - ev.getY(1)
        return abs(dx) + abs(dy)
    }

    // ------------------------------------------------------------ overlay ---

    private fun buildOverlay(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = 0.75f
        }

        fun btn(label: String, onClick: (Button) -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick(this) }
            bar.addView(this)
        }
        btn("＋ Screen") { addScreen() }
        btn("－ Screen") { removeScreen() }
        btn("✏ Edit") { b ->
            editMode = !editMode
            b.text = if (editMode) "🔒 Lock" else "✏ Edit"
            updateStatus()
        }
        btn("✕ Exit") { finish() }

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            setPadding(16, 8, 16, 8)
        }
        updateStatus()

        return FrameLayout(this).apply {
            addView(bar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ))
            addView(statusText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ))
        }
    }

    private fun updateStatus() {
        statusText.text = buildString {
            append(if (editMode) "EDIT — drag screens, pinch to resize" else "LOCKED — drag space to look, touch screens to control")
            append("  •  ${screens.size}/$MAX_SCREENS screens")
        }
    }

    private fun toast(msg: String) =
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

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
        for (screen in screens.values) {
            screen.decoder?.stop()
            screen.receiver.stop()
        }
        screens.clear()
        super.onDestroy()
    }
}
