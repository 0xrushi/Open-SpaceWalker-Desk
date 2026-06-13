package com.rushi.spacedesk.client

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.SeekBar
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

/** Broadcast actions sent by xr-companion (same phone) to control SpaceWalker. */
object SpaceWalkerActions {
    const val ZOOM_IN       = "com.rushi.spacedesk.SPACEWALKER_ZOOM_IN"
    const val ZOOM_OUT      = "com.rushi.spacedesk.SPACEWALKER_ZOOM_OUT"
    const val SET_ROTATION  = "com.rushi.spacedesk.SPACEWALKER_ROTATE"
    const val ADD_SCREEN    = "com.rushi.spacedesk.SPACEWALKER_ADD_SCREEN"
    const val REMOVE_SCREEN = "com.rushi.spacedesk.SPACEWALKER_REMOVE_SCREEN"
    const val EXTRA_DEGREES = "degrees"
}

/**
 * SpaceWalker mode: up to [MAX_SCREENS] host screens float on an arc in a 3D
 * workspace rendered with OpenGL (plug XR glasses into the phone and this
 * fills them). Lock mode: drag empty space to look around, touch a screen to
 * control the host (tap-through). Edit mode: drag screens along the arc /
 * up-down, pinch to resize.
 *
 * Zoom (+/−): adjusts [XRRenderer.camDistance] — pulls the camera closer or
 * further from the arc of screens, making everything appear larger or smaller.
 * Capped at [ZOOM_DIST_MIN]..[ZOOM_DIST_MAX].
 *
 * Rotation slider: fine-tunes [XRRenderer.camYaw] across the full 360° range,
 * supplementing the drag-to-look gesture already present in Lock mode.
 */
class SpaceWalkerActivity : ComponentActivity(), ControlClient.Listener {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"

        /** FOV range in degrees. Smaller = zoomed in (telephoto), larger = zoomed out. */
        private const val ZOOM_FOV_DEFAULT = 60f
        private const val ZOOM_FOV_MIN = 15f   // zoomed in
        private const val ZOOM_FOV_MAX = 90f   // zoomed out
        private const val ZOOM_STEP = 5f       // degrees per button press

        /** Slider covers ±180° around the starting yaw (effectively full circle). */
        private const val SLIDER_HALF_DEG = 180
        private const val SLIDER_MAX = SLIDER_HALF_DEG * 2  // 0..360 → −180°..+180°
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
    private lateinit var rotationSlider: SeekBar
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

        // Register here (not onResume) so broadcasts arrive even when xr-companion is in foreground.
        val filter = IntentFilter().apply {
            addAction(SpaceWalkerActions.ZOOM_IN)
            addAction(SpaceWalkerActions.ZOOM_OUT)
            addAction(SpaceWalkerActions.SET_ROTATION)
            addAction(SpaceWalkerActions.ADD_SCREEN)
            addAction(SpaceWalkerActions.REMOVE_SCREEN)
        }
        // RECEIVER_EXPORTED is required: xr-companion is a different app (different UID).
        // RECEIVER_NOT_EXPORTED silently drops broadcasts from other apps.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(spaceWalkerReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(spaceWalkerReceiver, filter)
        }

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
                        // Keep slider in sync when the user drags to look around.
                        syncSliderToYaw()
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
        val container = FrameLayout(this)

        // ── right-side vertical button column (unchanged from original) ──
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

        // ── zoom buttons: decrease FOV = zoom in, increase FOV = zoom out ──
        btn("＋ Zoom") {
            renderer.camFov = (renderer.camFov - ZOOM_STEP).coerceAtLeast(ZOOM_FOV_MIN)
        }
        btn("－ Zoom") {
            renderer.camFov = (renderer.camFov + ZOOM_STEP).coerceAtMost(ZOOM_FOV_MAX)
        }

        btn("✕ Exit") { finish() }

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            setPadding(16, 8, 16, 8)
        }
        updateStatus()

        container.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ),
        )
        container.addView(
            statusText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )

        // ── bottom rotation slider ──
        val sliderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            alpha = 0.80f
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(16, 4, 16, 4)
        }
        sliderRow.addView(TextView(this).apply {
            text = "↻"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 12, 0)
        })

        rotationSlider = SeekBar(this).apply {
            max = SLIDER_MAX  // 0 = −180°, 180 = 0°, 360 = +180°
            progress = SLIDER_HALF_DEG  // start centered
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        // Map 0..360 → −180°..+180°, apply as absolute camYaw offset.
                        renderer.camYaw = (progress - SLIDER_HALF_DEG).toFloat()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        sliderRow.addView(
            rotationSlider,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        container.addView(
            sliderRow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        return container
    }

    /**
     * After a drag-to-look gesture, clamp camYaw to ±180° and update the
     * slider so it reflects the current look direction.
     */
    private fun syncSliderToYaw() {
        val clamped = renderer.camYaw.coerceIn(-SLIDER_HALF_DEG.toFloat(), SLIDER_HALF_DEG.toFloat())
        renderer.camYaw = clamped
        if (::rotationSlider.isInitialized) {
            rotationSlider.progress = (clamped + SLIDER_HALF_DEG).toInt()
        }
    }

    private fun updateStatus() {
        statusText.text = buildString {
            append(if (editMode) "EDIT — drag screens, pinch to resize"
                   else "LOCKED — drag space to look, touch screens to control")
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

    // ------------------------------------------------ broadcast receiver ----

    private val spaceWalkerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                SpaceWalkerActions.ZOOM_IN -> {
                    renderer.camFov = (renderer.camFov - ZOOM_STEP).coerceAtLeast(ZOOM_FOV_MIN)
                }
                SpaceWalkerActions.ZOOM_OUT -> {
                    renderer.camFov = (renderer.camFov + ZOOM_STEP).coerceAtMost(ZOOM_FOV_MAX)
                }
                SpaceWalkerActions.SET_ROTATION -> {
                    val deg = intent.getFloatExtra(SpaceWalkerActions.EXTRA_DEGREES, 0f)
                    renderer.camYaw = deg.coerceIn(-SLIDER_HALF_DEG.toFloat(), SLIDER_HALF_DEG.toFloat())
                    syncSliderToYaw()
                }
                SpaceWalkerActions.ADD_SCREEN    -> mainHandler.post { addScreen() }
                SpaceWalkerActions.REMOVE_SCREEN -> mainHandler.post { removeScreen() }
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(spaceWalkerReceiver)
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
