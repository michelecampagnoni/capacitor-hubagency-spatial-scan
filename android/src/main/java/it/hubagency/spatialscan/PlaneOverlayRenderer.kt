package it.hubagency.spatialscan

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renderizza i piani ARCore rilevati con stile ispirato a RoomPlan:
 *  - Fill quasi invisibile (alpha ~0.08) — camera feed sempre leggibile
 *  - Bordi BIANCHI luminosi (alpha ~0.90, 2px) — l'unico elemento visivo forte
 *  - Piani in PAUSED: bordi gialli attenuati
 *  - Nessun colore solido. Minimalismo.
 */
class PlaneOverlayRenderer {

    companion object {
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
    private val vp = FloatArray(16)
    private var ready = false

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
     * Disegna i piani ARCore come fill quasi-trasparente + bordi bianchi.
     * Chiamare sul GL thread dopo backgroundRenderer.draw(frame).
     */
    fun draw(planes: Collection<Plane>, viewMatrix: FloatArray, projMatrix: FloatArray) {
        if (!ready) return
        Matrix.multiplyMM(vp, 0, projMatrix, 0, viewMatrix, 0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0)

        for (plane in planes) {
            if (plane.trackingState == TrackingState.STOPPED) continue
            if (plane.subsumedBy != null) continue
            val halfW = plane.extentX / 2f
            val halfH = plane.extentZ / 2f
            if (halfW < 0.05f || halfH < 0.05f) continue

            // 4 angoli del piano in coordinate locali → world
            val pose = plane.centerPose
            val world = arrayOf(
                floatArrayOf(-halfW, 0f, -halfH),
                floatArrayOf( halfW, 0f, -halfH),
                floatArrayOf( halfW, 0f,  halfH),
                floatArrayOf(-halfW, 0f,  halfH)
            ).map { pose.transformPoint(it) }

            val buf = ByteBuffer.allocateDirect(4 * 4 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            world.forEach { v -> buf.put(v[0]); buf.put(v[1]); buf.put(v[2]); buf.put(1f) }
            buf.rewind()

            val isTracking = plane.trackingState == TrackingState.TRACKING

            // ── Fill quasi invisibile ────────────────────────────────────────
            val fillAlpha = when {
                !isTracking -> 0.03f
                plane.type == Plane.Type.VERTICAL -> 0.07f
                else -> 0.05f
            }
            GLES20.glUniform4f(uColor, 0.95f, 0.97f, 1.0f, fillAlpha)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 4, GLES20.GL_FLOAT, false, 0, buf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)

            // ── Bordi con effetto glow (alone largo + linea centrale luminosa) ──
            if (isTracking) {
                // Alone esterno (simula blur/glow)
                GLES20.glUniform4f(uColor, 1.0f, 1.0f, 1.0f, 0.18f)
                GLES20.glLineWidth(7.0f)
                GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
                // Alone intermedio
                GLES20.glUniform4f(uColor, 1.0f, 1.0f, 1.0f, 0.40f)
                GLES20.glLineWidth(4.0f)
                GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
                // Linea centrale netta
                GLES20.glUniform4f(uColor, 1.0f, 1.0f, 1.0f, 0.92f)
                GLES20.glLineWidth(2.0f)
                GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
            } else {
                GLES20.glUniform4f(uColor, 0.95f, 0.82f, 0.30f, 0.45f)
                GLES20.glLineWidth(1.5f)
                GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
            }
            GLES20.glDisableVertexAttribArray(aPosition)
        }

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, src)
        GLES20.glCompileShader(it)
    }
}
