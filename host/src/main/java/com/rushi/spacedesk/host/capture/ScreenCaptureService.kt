package com.rushi.spacedesk.host.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.rushi.spacedesk.host.HostState
import com.rushi.spacedesk.host.MainActivity
import com.rushi.spacedesk.host.input.InputInjectionService
import com.rushi.spacedesk.host.net.ControlServer
import com.rushi.spacedesk.host.net.NsdAdvertiser
import com.rushi.spacedesk.host.net.VideoSender
import com.rushi.spacedesk.shared.ControlMessage
import com.rushi.spacedesk.shared.Protocol
import com.rushi.spacedesk.shared.RemoteInputEvent
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Foreground service that owns the whole sharing session:
 * MediaProjection -> VirtualDisplay -> VideoEncoder -> VideoSender,
 * plus the control server and NSD advertisement.
 */
class ScreenCaptureService : Service(), ControlServer.Callbacks {

    companion object {
        private const val TAG = "ScreenCapture"
        private const val CHANNEL_ID = "spacedesk_capture"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.rushi.spacedesk.host.START"
        const val ACTION_STOP = "com.rushi.spacedesk.host.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: VideoEncoder? = null
    private var sender: VideoSender? = null
    private var controlServer: ControlServer? = null
    private var nsdAdvertiser: NsdAdvertiser? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDpi = 0

    /** Last codec-config (SPS/PPS) frame, resent whenever a stream (re)starts. */
    @Volatile
    private var lastConfigFrame: ByteArray? = null

    // Active stream parameters, kept so the stream can be rebuilt on rotation.
    @Volatile
    private var activeRequest: ControlMessage.StartStream? = null

    @Volatile
    private var activeClientAddress: InetAddress? = null

    @Volatile
    private var activeSession: ControlServer.ClientSession? = null

    private val streamLock = Any()
    private val streamExecutor = Executors.newSingleThreadExecutor()

    /** Restarts the stream when the host display rotates (size swap). */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != android.view.Display.DEFAULT_DISPLAY) return
            val oldW = screenWidth
            val oldH = screenHeight
            readScreenMetrics()
            if ((screenWidth != oldW || screenHeight != oldH) && activeRequest != null) {
                Log.i(TAG, "display now ${screenWidth}x$screenHeight, restarting stream")
                streamExecutor.execute { startOrRestartStream() }
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "projection stopped by system/user")
            Handler(Looper.getMainLooper()).post { stopSelf() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSharing(intent)
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSharing(intent: Intent) {
        if (projection != null) return // already running

        startForegroundWithNotification()

        readScreenMetrics()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultData == null) {
            stopSelf()
            return
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, resultData)?.also {
            it.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        }
        if (projection == null) {
            stopSelf()
            return
        }

        controlServer = ControlServer(Protocol.DEFAULT_CONTROL_PORT, this).also { it.start() }
        nsdAdvertiser = NsdAdvertiser(this).also { it.register(Protocol.DEFAULT_CONTROL_PORT) }

        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        HostState.isSharing.value = true
        HostState.streamInfo.value = "Waiting for a client…"
        Log.i(TAG, "sharing started, control port ${Protocol.DEFAULT_CONTROL_PORT}")
    }

    private fun readScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDpi = metrics.densityDpi
        }
    }

    // ---------------- ControlServer.Callbacks ----------------

    override fun onHello(hello: ControlMessage.Hello): ControlMessage.HelloAck {
        HostState.connectedClient.value = hello.deviceName
        return ControlMessage.HelloAck(
            hostName = Build.MODEL,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            inputAllowed = HostState.inputAllowed.value && InputInjectionService.isRunning,
        )
    }

    override fun onStartStream(
        req: ControlMessage.StartStream,
        clientAddress: InetAddress,
        session: ControlServer.ClientSession,
    ) {
        activeRequest = req
        activeClientAddress = clientAddress
        activeSession = session
        streamExecutor.execute { startOrRestartStream() }
    }

    /** (Re)builds encoder + virtual display + sender for the current screen size. */
    private fun startOrRestartStream(): Unit = synchronized(streamLock) {
        val req = activeRequest ?: return
        val clientAddress = activeClientAddress ?: return
        val session = activeSession ?: return

        stopStreamLocked()

        // Fit host screen into client's requested max size, even-aligned.
        val scale = minOf(
            req.maxWidth.toFloat() / screenWidth,
            req.maxHeight.toFloat() / screenHeight,
            1f,
        )
        val outW = ((screenWidth * scale).toInt() and -2).coerceAtLeast(2)
        val outH = ((screenHeight * scale).toInt() and -2).coerceAtLeast(2)

        val newSender = VideoSender(clientAddress, req.videoUdpPort)
        sender = newSender

        val newEncoder = VideoEncoder(outW, outH, req.fps, req.bitrateBps) { data, keyframe, config ->
            if (config) lastConfigFrame = data
            newSender.submit(data, keyframe, config)
        }

        // Tell the client the new geometry BEFORE frames start flowing, so it
        // can restart its decoder in time for the incoming SPS/PPS.
        session.send(ControlMessage.VideoConfig(outW, outH, req.fps))

        newEncoder.start()
        encoder = newEncoder

        virtualDisplay = projection?.createVirtualDisplay(
            "spacedesk-stream",
            outW, outH, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            newEncoder.inputSurface,
            null, null,
        )

        newEncoder.requestKeyFrame()

        HostState.streamInfo.value = "${outW}x$outH @ ${req.fps}fps → $clientAddress"
        Log.i(TAG, "stream started ${outW}x$outH -> $clientAddress:${req.videoUdpPort}")
    }

    override fun onInput(events: List<RemoteInputEvent>) {
        if (!HostState.inputAllowed.value) return
        InputInjectionService.instance?.inject(events)
    }

    override fun onClientDisconnected() {
        HostState.connectedClient.value = null
        HostState.streamInfo.value = "Waiting for a client…"
        activeRequest = null
        activeClientAddress = null
        activeSession = null
        stopStream()
    }

    // ---------------- teardown ----------------

    private fun stopStream() = synchronized(streamLock) { stopStreamLocked() }

    private fun stopStreamLocked() {
        virtualDisplay?.release()
        virtualDisplay = null
        encoder?.stop()
        encoder = null
        sender?.stop()
        sender = null
    }

    override fun onDestroy() {
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .unregisterDisplayListener(displayListener)
        activeRequest = null
        stopStream()
        streamExecutor.shutdown()
        nsdAdvertiser?.unregister()
        nsdAdvertiser = null
        controlServer?.stop()
        controlServer = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        HostState.isSharing.value = false
        HostState.connectedClient.value = null
        HostState.streamInfo.value = null
        super.onDestroy()
    }

    // ---------------- notification ----------------

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW),
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SpaceDesk is sharing your screen")
            .setContentText("Tap to manage")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
