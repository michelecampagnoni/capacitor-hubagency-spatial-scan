package it.hubagency.spatialscan

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renderer GL_LINES per il perimetro in corso di cattura.
 *
 * Disegna (sul piano pavimento, Y = floorY + epsilon):
 *  - Segmenti confermati   : GL_LINE_STRIP verde brillante
 *  - Segmento di chiusura  : GL_LINES verde (quando isClosed)
 *  - Segmento live         : GL_LINES giallo (dall'ultimo punto → reticle snappata)
 *  - Punti vertice         : GL_POINTS bianco
 *
 * Tutti gli shader sono GLES 2.0 puri — nessuna dipendenza da librerie esterne.
 * init() deve essere chiamato nel GL thread (onSurfaceCreated).
 */
class PerimeterRenderer {

    companion object {
        private const val TAG = "PerimeterRenderer"
        private const val FLOOR_EPSILON = 0.015f   // offset Y sopra il pavimento (evita z-fighting)

        private val VERT_SRC = """
            uniform mat4 uMVP;
            attribute vec4 aPos;
            void main() {
                gl_Position  = uMVP * aPos;
                gl_PointSize = 16.0;
            }
        """.trimIndent()

        private val FRAG_SRC = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """.trimIndent()

        // Colori RGBA
        private val COLOR_CONFIRMED = floatArrayOf(0.18f, 1.00f, 0.42f, 1.00f)  // verde
        private val COLOR_LIVE      = floatArrayOf(1.00f, 0.85f, 0.00f, 0.85f)  // giallo
        private val COLOR_DOT       = floatArrayOf(1.00f, 1.00f, 1.00f, 1.00f)  // bianco
    }

    private var program   = 0
    private var aPos      = 0
    private var uMVP      = 0
    private var uColor    = 0
    private val mvp       = FloatArray(16)

    // ── Init ─────────────────────────────────────────────────────────────────

    fun init() {
        val vert = compileShader(GLES20.GL_VERTEX_SHADER,   VERT_SRC)
        val frag = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAG_SRC)
        program  = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vert)
        GLES20.glAttachShader(program, frag)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link error: ${GLES20.glGetProgramInfoLog(program)}")
        }

        aPos   = GLES20.glGetAttribLocation(program,  "aPos")
        uMVP   = GLES20.glGetUniformLocation(program, "uMVP")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
    }

    // ── Draw ─────────────────────────────────────────────────────────────────

    /**
     * @param confirmedPts  vertici XZ confermati (FloatArray(x, z) per punto)
     * @param livePoint     punto live (XZ, già snappato in angolo) — null se nessuno
     * @param isClosed      true dopo close(); disegna segmento P_n → P_0
     * @param floorY        altezza pavimento (Y world space)
     * @param viewMatrix    4×4 float, row-major
     * @param projMatrix    4×4 float, row-major
     */
    fun draw(
        confirmedPts: List<FloatArray>,
        livePoint:    FloatArray?,
        isClosed:     Boolean,
        floorY:       Float,
        viewMatrix:   FloatArray,
        projMatrix:   FloatArray
    ) {
        if (program == 0) return

        Matrix.multiplyMM(mvp, 0, projMatrix, 0, viewMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glLineWidth(4f)

        val y = floorY + FLOOR_EPSILON

        // ── Segmenti confermati ───────────────────────────────────────────────
        if (confirmedPts.size >= 2) {
            val verts = FloatArray(confirmedPts.size * 3)
            confirmedPts.forEachIndexed { i, pt ->
                verts[i * 3]     = pt[0]
                verts[i * 3 + 1] = y
                verts[i * 3 + 2] = pt[1]
            }
            setColor(COLOR_CONFIRMED)
            drawPrimitive(GLES20.GL_LINE_STRIP, verts, confirmedPts.size)
        }

        // ── Segmento di chiusura P_n → P_0 ───────────────────────────────────
        if (isClosed && confirmedPts.size >= 3) {
            val last  = confirmedPts.last()
            val first = confirmedPts.first()
            val verts = floatArrayOf(
                last[0],  y, last[1],
                first[0], y, first[1]
            )
            setColor(COLOR_CONFIRMED)
            drawPrimitive(GLES20.GL_LINES, verts, 2)
        }

        // ── Segmento live (dall'ultimo punto alla reticle snappata) ───────────
        if (!isClosed && confirmedPts.isNotEmpty() && livePoint != null) {
            val prev  = confirmedPts.last()
            val verts = floatArrayOf(
                prev[0],      y, prev[1],
                livePoint[0], y, livePoint[1]
            )
            setColor(COLOR_LIVE)
            drawPrimitive(GLES20.GL_LINES, verts, 2)
        }

        // ── Punti vertice ─────────────────────────────────────────────────────
        if (confirmedPts.isNotEmpty()) {
            val verts = FloatArray(confirmedPts.size * 3)
            confirmedPts.forEachIndexed { i, pt ->
                verts[i * 3]     = pt[0]
                verts[i * 3 + 1] = y + 0.005f
                verts[i * 3 + 2] = pt[1]
            }
            setColor(COLOR_DOT)
            drawPrimitive(GLES20.GL_POINTS, verts, confirmedPts.size)
        }

        GLES20.glUseProgram(0)
    }

    // ── GL helpers ────────────────────────────────────────────────────────────

    private fun setColor(c: FloatArray) {
        GLES20.glUniform4f(uColor, c[0], c[1], c[2], c[3])
    }

    private fun drawPrimitive(mode: Int, verts: FloatArray, count: Int) {
        val buf = makeBuffer(verts)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glDrawArrays(mode, 0, count)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun makeBuffer(verts: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(verts); position(0) }

    private fun compileShader(type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0)
            Log.e(TAG, "Shader compile error [type=$type]: ${GLES20.glGetShaderInfoLog(id)}")
        return id
    }
}
