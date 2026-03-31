package it.hubagency.spatialscan

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renderizza tutte le WallHypothesis con colori distinti per stato — modalità DEBUG B:
 *  CANDIDATE  → arancione (pipeline riceve dati ma non abbastanza)
 *  STABLE     → blu (sufficiente per mostrare all'utente)
 *  CONFIRMED  → verde (geometria stabile e affidabile)
 *
 * Permette di diagnosticare dove si rompe la pipeline visivamente.
 */
class ConfirmedWallRenderer {

    companion object {
        private const val VERT = """
            attribute vec4 a_Position;
            uniform mat4 u_VP;
            void main() { gl_Position = u_VP * a_Position; }
        """
        private const val FRAG = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() { gl_FragColor = u_Color; }
        """
    }

    private var program = 0
    private var aPos    = 0
    private var uVP     = 0
    private var uColor  = 0
    private val vp      = FloatArray(16)
    private var ready   = false

    fun init() {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAG)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        aPos   = GLES20.glGetAttribLocation(program,  "a_Position")
        uVP    = GLES20.glGetUniformLocation(program, "u_VP")
        uColor = GLES20.glGetUniformLocation(program, "u_Color")
        ready  = true
    }

    /**
     * @param walls      TUTTE le ipotesi (incluse CANDIDATE) per debug visivo completo
     * @param floorY     Y world del pavimento
     * @param wallHeight altezza stimata delle pareti
     */
    fun draw(
        walls: List<WallHypothesis>,
        floorY: Float,
        wallHeight: Float,
        viewMatrix: FloatArray,
        projMatrix: FloatArray
    ) {
        if (!ready || walls.isEmpty()) return
        Matrix.multiplyMM(vp, 0, projMatrix, 0, viewMatrix, 0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0)

        for (wall in walls) {
            val s    = wall.segment
            val topY = floorY + wallHeight

            val buf = ByteBuffer.allocateDirect(4 * 4 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.put(s.x1); buf.put(floorY); buf.put(s.z1); buf.put(1f)
            buf.put(s.x2); buf.put(floorY); buf.put(s.z2); buf.put(1f)
            buf.put(s.x2); buf.put(topY);   buf.put(s.z2); buf.put(1f)
            buf.put(s.x1); buf.put(topY);   buf.put(s.z1); buf.put(1f)
            buf.rewind()

            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, 0, buf)

            when (wall.state) {
                WallHypothesis.State.CONFIRMED -> {
                    // Verde — muro confermato; dimming proporzionale a confidence (LockedWallMemory decay)
                    val c = wall.confidence
                    GLES20.glUniform4f(uColor, 0.1f, 1.0f, 0.3f, 0.10f * c)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
                    GLES20.glUniform4f(uColor, 0.1f, 1.0f, 0.3f, 0.50f * c); GLES20.glLineWidth(4f)
                    GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
                    GLES20.glUniform4f(uColor, 0.3f, 1.0f, 0.5f, 0.95f * c); GLES20.glLineWidth(2f)
                    GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
                }
                WallHypothesis.State.STABLE -> {
                    // Blu — muro stabile
                    GLES20.glUniform4f(uColor, 0.2f, 0.5f, 1.0f, 0.08f)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
                    GLES20.glUniform4f(uColor, 0.3f, 0.6f, 1.0f, 0.60f); GLES20.glLineWidth(3f)
                    GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
                }
                WallHypothesis.State.CANDIDATE -> {
                    // Arancione — candidato: ARCore lo vede ma poche osservazioni
                    GLES20.glUniform4f(uColor, 1.0f, 0.55f, 0.0f, 0.05f)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
                    GLES20.glUniform4f(uColor, 1.0f, 0.55f, 0.0f, 0.40f); GLES20.glLineWidth(2f)
                    GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
                }
            }

            GLES20.glDisableVertexAttribArray(aPos)
        }

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, src)
        GLES20.glCompileShader(it)
    }
}
