package com.rushi.spacedesk.host.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.rushi.spacedesk.host.HostState
import com.rushi.spacedesk.shared.NavAction
import com.rushi.spacedesk.shared.RemoteInputEvent
import com.rushi.spacedesk.shared.TouchAction

/**
 * AccessibilityService that injects remote client input system-wide.
 *
 * Touch strategy: incoming DOWN/MOVE/UP events (normalized 0..1) are buffered
 * into stroke segments. Segments are dispatched as continued gestures
 * (StrokeDescription.continueStroke) roughly every [SEGMENT_MS], so drags and
 * scrolls feel live instead of replaying after finger-up. Single pointer in v1.
 */
class InputInjectionService : AccessibilityService() {

    companion object {
        private const val TAG = "InputInjection"
        private const val SEGMENT_MS = 90L
        private const val MIN_TAP_MS = 30L

        @Volatile
        var instance: InputInjectionService? = null
            private set

        val isRunning: Boolean get() = instance != null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // --- screen mapping ---
    private var screenW = 1
    private var screenH = 1

    // --- gesture state (main thread only) ---
    private val pendingPoints = ArrayList<PointF>()
    private var segmentStartMs = 0L
    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var strokeInProgress = false
    private var fingerDown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshScreenSize()
        HostState.accessibilityRunning.value = true
        Log.i(TAG, "connected, screen ${screenW}x$screenH")
    }

    /** Re-reads the display size; rotation swaps width/height. */
    private fun refreshScreenSize() {
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also {
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(it)
        }
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
    }

    override fun onDestroy() {
        instance = null
        HostState.accessibilityRunning.value = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Entry point: called from network threads. */
    fun inject(events: List<RemoteInputEvent>) {
        Log.d(TAG, "inject ${events.size} event(s): ${events.firstOrNull()}")
        mainHandler.post {
            for (e in events) {
                when (e) {
                    is RemoteInputEvent.Touch -> handleTouch(e)
                    is RemoteInputEvent.Nav -> handleNav(e.action)
                    is RemoteInputEvent.Text -> handleText(e.text)
                }
            }
        }
    }

    // ---------------- touch ----------------

    private fun handleTouch(e: RemoteInputEvent.Touch) {
        if (e.pointerId != 0) return // v1: single pointer
        if (e.action == TouchAction.DOWN) refreshScreenSize() // track rotation
        val x = (e.x * screenW).coerceIn(0f, screenW - 1f)
        val y = (e.y * screenH).coerceIn(0f, screenH - 1f)

        when (e.action) {
            TouchAction.DOWN -> {
                pendingPoints.clear()
                pendingPoints.add(PointF(x, y))
                segmentStartMs = SystemClock.uptimeMillis()
                activeStroke = null
                fingerDown = true
            }
            TouchAction.MOVE -> {
                if (!fingerDown) return
                pendingPoints.add(PointF(x, y))
                val elapsed = SystemClock.uptimeMillis() - segmentStartMs
                if (elapsed >= SEGMENT_MS && !strokeInProgress) {
                    flushSegment(willContinue = true)
                }
            }
            TouchAction.UP -> {
                if (!fingerDown) return
                pendingPoints.add(PointF(x, y))
                fingerDown = false
                if (!strokeInProgress) flushSegment(willContinue = false)
                // else: completion callback will flush the tail
            }
            TouchAction.CANCEL -> {
                pendingPoints.clear()
                activeStroke = null
                fingerDown = false
            }
        }
    }

    private fun flushSegment(willContinue: Boolean) {
        if (pendingPoints.isEmpty()) return
        val path = Path()
        path.moveTo(pendingPoints[0].x, pendingPoints[0].y)
        for (i in 1 until pendingPoints.size) path.lineTo(pendingPoints[i].x, pendingPoints[i].y)

        val duration = (SystemClock.uptimeMillis() - segmentStartMs).coerceIn(MIN_TAP_MS, 2_000L)
        val stroke = activeStroke?.continueStroke(path, 0, duration, willContinue)
            ?: GestureDescription.StrokeDescription(path, 0, duration, willContinue)

        // Next segment must start where this one ended.
        val last = pendingPoints.last()
        pendingPoints.clear()
        if (willContinue) pendingPoints.add(PointF(last.x, last.y))
        segmentStartMs = SystemClock.uptimeMillis()
        activeStroke = if (willContinue) stroke else null

        strokeInProgress = true
        val dispatched = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) = onSegmentFinished()
                override fun onCancelled(g: GestureDescription?) {
                    activeStroke = null
                    onSegmentFinished()
                }
            },
            mainHandler,
        )
        if (!dispatched) {
            Log.w(TAG, "dispatchGesture rejected")
            strokeInProgress = false
            activeStroke = null
        }
    }

    private fun onSegmentFinished() {
        strokeInProgress = false
        // Flush whatever accumulated while the last segment was playing.
        if (!fingerDown && activeStroke != null) {
            if (pendingPoints.size <= 1) {
                // Finger already up and no new movement: end the stroke in place.
                if (pendingPoints.isEmpty()) {
                    activeStroke = null
                    return
                }
            }
            flushSegment(willContinue = false)
        } else if (fingerDown && pendingPoints.size > 1) {
            flushSegment(willContinue = true)
        }
    }

    // ---------------- nav / text ----------------

    private fun handleNav(action: NavAction) {
        performGlobalAction(
            when (action) {
                NavAction.BACK -> GLOBAL_ACTION_BACK
                NavAction.HOME -> GLOBAL_ACTION_HOME
                NavAction.RECENTS -> GLOBAL_ACTION_RECENTS
            },
        )
    }

    private fun handleText(text: String) {
        val focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (!focus.isEditable) return
        val existing = focus.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
        }
        focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
