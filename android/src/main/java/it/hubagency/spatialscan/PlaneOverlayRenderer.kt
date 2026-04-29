package it.hubagency.spatialscan

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max

/**
 * Disegna una griglia di celle su ogni piano ARCore rilevato (pavimento + pareti + soffitto).
 *
 * Celle 30×30cm per default.
 * Colore:
 *  - Pavimento/soffitto (HORIZONTAL): teal
 *  - Pareti (VERTICAL):               blu
 *  - Piano in PAUSED:                 grigio attenuato
 *
 * L'effetto progressivo emerge naturalmente man mano che ARCore rileva nuovi piani.
 */
class PlaneOverlayRenderer {

    companion object {
        private const val GRID_CELL_M  = 0.30f   // dimensione cella griglia (30cm)
        private const val MAX_STEPS    = 40       // limite celle per asse (evita lag su piani grandi)

        private const val VERT = """
            attribute vec4 a_Position;
            uniform mat4 u_VP;
            void main() {
                gl_Position = u_VP * a_Position;
            }
        """
        private const val FRAG = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
    }

    private var program   = 0
    private var aPosition = 0
    private var uVP       = 0
    private var uColor    = 0
    private val vp        = FloatArray(16)
    private var ready     = false

    fun init() {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAG)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        uVP       = GLES20.glGetUniformLocation(program, "u_VP")
        uColor    = GLES20.glGetUniformLocation(program, "u_Color")
        ready = true
    }

    /**
     * Chiamare sul GL thread dopo backgroundRenderer.draw(frame).
     * Passa tutti i piani ARCore (HORIZONTAL + VERTICAL).
     */
    fun draw(planes: Collection<Plane>, viewMatrix: FloatArray, projMatrix: FloatArray) {
        if (!ready) return
        Matrix.multiplyMM(vp, 0, projMatrix, 0, viewMatrix, 0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0)

        for (plane in planes) {
            if (plane.trackingState == TrackingState.STOPPED) continue
            if (plane.subsumedBy != null) continue
            val halfW = plane.extentX / 2f
            val halfH = plane.extentZ / 2f
            if (halfW < 0.05f || halfH < 0.05f) continue
            drawPlaneGrid(plane, halfW, halfH)
        }

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // ── Grid drawing ──────────────────────────────────────────────────────────

    private fun drawPlaneGrid(plane: Plane, halfW: Float, halfH: Float) {
        val pose       = plane.centerPose
        val isTracking = plane.trackingState == TrackingState.TRACKING

        val stepsX = max(1, ceil(plane.extentX / GRID_CELL_M).toInt()).coerceAtMost(MAX_STEPS)
        val stepsZ = max(1, ceil(plane.extentZ / GRID_CELL_M).toInt()).coerceAtMost(MAX_STEPS)

        // vertCount = 2 endpoints × (stepsX+1 linee Z + stepsZ+1 linee X)
        val vertCount = 2 * (stepsX + 1 + stepsZ + 1)
        val verts     = FloatArray(vertCount * 4)  // XYZW per vertice
        var idx       = 0

        // Linee parallele a Z (variando X)
        for (i in 0..stepsX) {
            val x  = -halfW + i * (plane.extentX / stepsX)
            val p0 = pose.transformPoint(floatArrayOf(x, 0f, -halfH))
            val p1 = pose.transformPoint(floatArrayOf(x, 0f,  halfH))
            verts[idx++]=p0[0]; verts[idx++]=p0[1]; verts[idx++]=p0[2]; verts[idx++]=1f
            verts[idx++]=p1[0]; verts[idx++]=p1[1]; verts[idx++]=p1[2]; verts[idx++]=1f
        }

        // Linee parallele a X (variando Z)
        for (j in 0..stepsZ) {
            val z  = -halfH + j * (plane.extentZ / stepsZ)
            val p0 = pose.transformPoint(floatArrayOf(-halfW, 0f, z))
            val p1 = pose.transformPoint(floatArrayOf( halfW, 0f, z))
            verts[idx++]=p0[0]; verts[idx++]=p0[1]; verts[idx++]=p0[2]; verts[idx++]=1f
            verts[idx++]=p1[0]; verts[idx++]=p1[1]; verts[idx++]=p1[2]; verts[idx++]=1f
        }

        val buf = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(verts); buf.rewind()

        val alpha = if (isTracking) 0.60f else 0.20f
        when {
            !isTracking                    -> GLES20.glUniform4f(uColor, 0.55f, 0.55f, 0.55f, alpha)
            plane.type == Plane.Type.VERTICAL
                                           -> GLES20.glUniform4f(uColor, 0.20f, 0.65f, 1.00f, alpha)  // blu: pareti
            else                           -> GLES20.glUniform4f(uColor, 0.15f, 1.00f, 0.70f, alpha)  // teal: pavimento
        }

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 4, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glLineWidth(1.5f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertCount)
        GLES20.glDisableVertexAttribArray(aPosition)
    }

    private fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, src)
        GLES20.glCompileShader(it)
    }
}
