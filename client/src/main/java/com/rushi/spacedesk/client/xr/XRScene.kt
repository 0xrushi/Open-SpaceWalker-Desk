package com.rushi.spacedesk.client.xr

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

private const val FOV_Y = 60f
private const val DEG = (Math.PI / 180.0).toFloat()

/**
 * One floating screen in the XR workspace. Positioned on a cylinder around
 * the viewer: [yawDeg] is its angle on the arc, [heightY] its elevation,
 * [scale] its width in world units. The quad always faces the viewer axis.
 */
class ScreenNode(val id: Int) {
    @Volatile var yawDeg = 0f
    @Volatile var heightY = 0f
    @Volatile var scale = 2.6f
    @Volatile var videoW = 16
    @Volatile var videoH = 9
    val distance = 3.2f

    // GL-thread state
    var texId = 0
    var surfaceTexture: SurfaceTexture? = null
    @Volatile var surface: Surface? = null
    val frameAvailable = AtomicBoolean(false)

    val quadW: Float get() = scale
    val quadH: Float get() = scale * videoH / videoW.coerceAtLeast(1)

    fun center() = floatArrayOf(
        distance * sin(yawDeg * DEG), heightY, -distance * cos(yawDeg * DEG),
    )

    fun normal() = floatArrayOf(-sin(yawDeg * DEG), 0f, cos(yawDeg * DEG))
    fun right() = floatArrayOf(cos(yawDeg * DEG), 0f, sin(yawDeg * DEG))
}

/** Result of a ray-screen intersection: normalized 0..1 coords on the video. */
data class Hit(val node: ScreenNode, val nx: Float, val ny: Float, val dist: Float)

class XRRenderer(
    private val onSurfaceFor: (ScreenNode) -> Unit,
) : GLSurfaceView.Renderer {

    val nodes = CopyOnWriteArrayList<ScreenNode>()

    @Volatile var camYaw = 0f   // degrees
    @Volatile var camPitch = 0f

    @Volatile private var aspect = 16f / 9f
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uMvp = 0
    private var uStMatrix = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val tmp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val stMatrix = FloatArray(16)

    private val texBuf: FloatBuffer = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f).toBuffer()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(
            """
            uniform mat4 uMvp; uniform mat4 uStMatrix;
            attribute vec4 aPos; attribute vec4 aTex;
            varying vec2 vTex;
            void main() { gl_Position = uMvp * aPos; vTex = (uStMatrix * aTex).xy; }
            """,
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex; uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
            """,
        )
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
        GLES20.glClearColor(0.02f, 0.02f, 0.06f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height
        Matrix.perspectiveM(proj, 0, FOV_Y, aspect, 0.1f, 50f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val p = camPitch * DEG
        val yw = camYaw * DEG
        Matrix.setLookAtM(
            view, 0, 0f, 0f, 0f,
            -sin(yw) * cos(p), sin(p), -cos(yw) * cos(p),
            0f, 1f, 0f,
        )

        GLES20.glUseProgram(program)
        for (node in nodes) {
            ensureTexture(node)
            val st = node.surfaceTexture ?: continue
            if (node.frameAvailable.getAndSet(false)) st.updateTexImage()
            st.getTransformMatrix(stMatrix)

            val c = node.center()
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, c[0], c[1], c[2])
            Matrix.rotateM(model, 0, -node.yawDeg, 0f, 1f, 0f)
            Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)

            drawQuad(node)
        }
    }

    private fun ensureTexture(node: ScreenNode) {
        if (node.surfaceTexture != null) return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        node.texId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, node.texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val st = SurfaceTexture(node.texId)
        st.setOnFrameAvailableListener { node.frameAvailable.set(true) }
        node.surfaceTexture = st
        node.surface = Surface(st)
        onSurfaceFor(node) // tell the activity it can start this screen's decoder
    }

    private fun drawQuad(node: ScreenNode) {
        val w = node.quadW / 2
        val h = node.quadH / 2
        val verts = floatArrayOf(-w, -h, 0f, w, -h, 0f, -w, h, 0f, w, h, 0f).toBuffer()

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, node.texId)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, verts)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    // ----------------------------------------------------------- picking ----

    /**
     * Casts a ray from a touch point (view-relative px) and returns the
     * nearest screen hit. [clampTo] restricts the test to one node and clamps
     * coords to 0..1 (used while dragging off a screen's edge).
     */
    fun pick(touchX: Float, touchY: Float, viewW: Int, viewH: Int,
             clampTo: ScreenNode? = null): Hit? {
        val ndcX = 2f * touchX / viewW - 1f
        val ndcY = 1f - 2f * touchY / viewH
        val tanF = tan(FOV_Y / 2 * DEG)
        // Camera-space ray, rotated by pitch (X) then yaw (Y) into world space.
        var dx = ndcX * tanF * aspect
        var dy = ndcY * tanF
        var dz = -1f
        val p = camPitch * DEG
        val y1 = dy * cos(p) - dz * sin(p)
        val z1 = dy * sin(p) + dz * cos(p)
        dy = y1; dz = z1
        val yw = camYaw * DEG // same R_y as the view matrix derivation
        val x2 = dx * cos(yw) + dz * sin(yw)
        val z2 = -dx * sin(yw) + dz * cos(yw)
        dx = x2; dz = z2

        var best: Hit? = null
        val candidates = clampTo?.let { listOf(it) } ?: nodes
        for (node in candidates) {
            val c = node.center()
            val n = node.normal()
            val denom = dx * n[0] + dy * n[1] + dz * n[2]
            if (denom > -1e-6) continue // back-facing or parallel
            val t = (c[0] * n[0] + c[1] * n[1] + c[2] * n[2]) / denom
            if (t <= 0) continue
            val hx = dx * t - c[0]
            val hy = dy * t - c[1]
            val hz = dz * t - c[2]
            val r = node.right()
            val u = hx * r[0] + hy * r[1] + hz * r[2]
            val v = hy // up is (0,1,0)
            var nx = u / node.quadW + 0.5f
            var ny = 0.5f - v / node.quadH
            if (clampTo != null) {
                nx = nx.coerceIn(0f, 1f); ny = ny.coerceIn(0f, 1f)
            } else if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) {
                continue
            }
            if (best == null || t < best!!.dist) best = Hit(node, nx, ny, t)
        }
        return best
    }
}

// ------------------------------------------------------------------ glue ----

private fun FloatArray.toBuffer(): FloatBuffer =
    ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().put(this).apply { position(0) }

private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
    fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertexSrc))
    GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc))
    GLES20.glLinkProgram(program)
    return program
}
