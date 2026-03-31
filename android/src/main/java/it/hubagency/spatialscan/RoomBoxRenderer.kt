package it.hubagency.spatialscan

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*

/**
 * Renderizza il RoomBox come wireframe 3D pulito.
 *
 * Struttura geometrica:
 *  - 4 spigoli verticali (angoli stanza, floor→ceiling)
 *  - 4 spigoli orizzontali al pavimento (perimetro floor)
 *  - 4 spigoli orizzontali al soffitto (perimetro ceiling)
 *  - Linee griglia su ogni parete: orizzontali ogni GRID_STEP, verticali ogni GRID_STEP
 *
 * Comportamento:
 *  - Prima che il box sia stabile: linee bianche semi-trasparenti, senza griglia (bozza)
 *  - Quando stabile: linee bianche più opache + griglia leggera
 */
class RoomBoxRenderer {

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
        private const val GRID_STEP = 0.50f   // griglia ogni 50cm
        // Stima conservativa: box 10m×10m, altezza 3m → max ~150 linee griglia
        private const val MAX_LINES = 200
        private const val FLOATS_PER_LINE = 6  // 2 vertici × 3 float (x,y,z)
    }

    private var program = 0
    private var aPos    = 0
    private var uVP     = 0
    private var uColor  = 0
    private val vp      = FloatArray(16)
    private var ready   = false

    fun init() {
        val vs = compile(GLES20.GL_VERTEX_SHADER,   VERT)
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
     * @param box      RoomBox da renderizzare
     * @param isStable se true: stile "confermato" (grid + linee opache)
     *                 se false: stile "bozza" (solo spigoli, semi-trasparente)
     */
    fun draw(
        box: RoomBox,
        isStable: Boolean,
        viewMatrix: FloatArray,
        projMatrix: FloatArray
    ) {
        if (!ready) return
        Matrix.multiplyMM(vp, 0, projMatrix, 0, viewMatrix, 0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0)

        val corners = box.corners()
        val floorY  = box.floorY
        val ceilY   = box.ceilingY

        // ── Spigoli box (12 linee) ─────────────────────────────────────────────
        val edgeAlpha = if (isStable) 0.92f else 0.40f
        GLES20.glUniform4f(uColor, 1f, 1f, 1f, edgeAlpha)
        GLES20.glLineWidth(if (isStable) 3.5f else 1.5f)
        GLES20.glEnableVertexAttribArray(aPos)

        val edgeBuf = buildEdgeBuffer(corners, floorY, ceilY)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, edgeBuf)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 24)  // 12 linee × 2 vertici

        // ── Griglia (solo quando stabile) ────────────────────────────────────
        if (isStable) {
            GLES20.glUniform4f(uColor, 1f, 1f, 1f, 0.18f)
            GLES20.glLineWidth(1f)
            val gridBuf = buildGridBuffer(corners, floorY, ceilY)
            if (gridBuf != null) {
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, gridBuf.first)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridBuf.second * 2)
            }
        }

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    // ── Costruzione buffer spigoli ────────────────────────────────────────────

    /**
     * 12 linee del wireframe box:
     * - 4 bottom: corners[i] → corners[(i+1)%4] al floorY
     * - 4 top:    corners[i] → corners[(i+1)%4] al ceilY
     * - 4 vertical: corners[i] floor → ceiling
     */
    private fun buildEdgeBuffer(
        corners: Array<FloatArray>,
        floorY: Float,
        ceilY: Float
    ): FloatBuffer {
        val buf = allocBuf(24 * 3)
        // 4 bottom edges
        for (i in 0..3) {
            val j = (i + 1) % 4
            buf.put(corners[i][0]); buf.put(floorY); buf.put(corners[i][1])
            buf.put(corners[j][0]); buf.put(floorY); buf.put(corners[j][1])
        }
        // 4 top edges
        for (i in 0..3) {
            val j = (i + 1) % 4
            buf.put(corners[i][0]); buf.put(ceilY); buf.put(corners[i][1])
            buf.put(corners[j][0]); buf.put(ceilY); buf.put(corners[j][1])
        }
        // 4 vertical edges
        for (i in 0..3) {
            buf.put(corners[i][0]); buf.put(floorY); buf.put(corners[i][1])
            buf.put(corners[i][0]); buf.put(ceilY);  buf.put(corners[i][1])
        }
        buf.rewind()
        return buf
    }

    // ── Costruzione buffer griglia ────────────────────────────────────────────

    /**
     * Linee griglia su ciascuna delle 4 pareti.
     * Per ogni parete AB:
     *  - orizzontali: a Y = floorY + step, +2*step, ... < ceilY
     *  - verticali:   a t = step, 2*step, ... < wallLength (lungo parete)
     *
     * @return Pair(FloatBuffer, numLines) oppure null se nessuna linea
     */
    private fun buildGridBuffer(
        corners: Array<FloatArray>,
        floorY: Float,
        ceilY: Float
    ): Pair<FloatBuffer, Int>? {
        val lines = ArrayList<FloatArray>(MAX_LINES * 2)
        val wallHeight = ceilY - floorY

        for (i in 0..3) {
            val j = (i + 1) % 4
            val ax = corners[i][0]; val az = corners[i][1]
            val bx = corners[j][0]; val bz = corners[j][1]
            val wallLen = dist(ax, az, bx, bz)
            if (wallLen < 0.01f) continue
            val dx = (bx - ax) / wallLen; val dz = (bz - az) / wallLen

            // Linee orizzontali
            var h = floorY + GRID_STEP
            while (h < ceilY - 0.05f) {
                lines.add(floatArrayOf(ax, h, az))
                lines.add(floatArrayOf(bx, h, bz))
                h += GRID_STEP
            }

            // Linee verticali
            var t = GRID_STEP
            while (t < wallLen - 0.05f) {
                val px = ax + dx * t; val pz = az + dz * t
                lines.add(floatArrayOf(px, floorY, pz))
                lines.add(floatArrayOf(px, ceilY,  pz))
                t += GRID_STEP
            }
        }

        if (lines.isEmpty()) return null
        val numLines = lines.size / 2
        val buf = allocBuf(lines.size * 3)
        for (v in lines) { buf.put(v[0]); buf.put(v[1]); buf.put(v[2]) }
        buf.rewind()
        return Pair(buf, numLines)
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun allocBuf(floatCount: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floatCount * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private fun dist(ax: Float, az: Float, bx: Float, bz: Float) =
        sqrt((bx - ax).pow(2) + (bz - az).pow(2))

    private fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, src)
        GLES20.glCompileShader(it)
    }
}
