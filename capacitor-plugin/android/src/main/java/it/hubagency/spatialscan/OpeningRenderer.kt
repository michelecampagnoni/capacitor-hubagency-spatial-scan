package it.hubagency.spatialscan

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renderer GLES 2.0 per le aperture (porte/finestre) sui muri.
 *
 * Step 4A: structural opening overlay.
 * - Il muro rimane pieno (gestito da PerimeterRenderer).
 * - L'apertura è disegnata come box traslucido colorato sul piano del muro.
 * - Il muro attivo (selezionato/hovering) è evidenziato in giallo.
 *
 * Coordinate: tutto in world space, Y=0 = pavimento.
 */
class OpeningRenderer {

    companion object {
        // Hover: ciano forte — fill leggero + outline pieno
        private val COLOR_HOVER_FILL    = floatArrayOf(0.00f, 0.90f, 1.00f, 0.18f)
        private val COLOR_HOVER_OUTLINE = floatArrayOf(0.00f, 0.90f, 1.00f, 1.00f)
        // Selected: fucsia/viola — fill più visibile + outline acceso
        private val COLOR_SEL_FILL      = floatArrayOf(0.85f, 0.10f, 0.95f, 0.28f)
        private val COLOR_SEL_OUTLINE   = floatArrayOf(0.95f, 0.20f, 1.00f, 1.00f)

        private val COLOR_DOOR           = floatArrayOf(0.90f, 0.55f, 0.10f, 0.80f)  // arancio porta
        private val COLOR_WINDOW         = floatArrayOf(0.30f, 0.70f, 1.00f, 0.75f)  // azzurro finestra
        private val COLOR_FRENCH_DOOR    = floatArrayOf(0.60f, 0.30f, 0.90f, 0.80f)  // viola portafinestra
        private val COLOR_OPENING_BORDER = floatArrayOf(1.00f, 1.00f, 1.00f, 1.00f)  // bordo bianco

        private val VERT_SRC = """
            uniform mat4 uMVP;
            attribute vec4 aPos;
            void main() {
                gl_Position = uMVP * aPos;
            }
        """.trimIndent()

        private val FRAG_SRC = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """.trimIndent()
    }

    private var program = 0
    private var aPos    = 0
    private var uMVP    = 0
    private var uColor  = 0
    private val mvp     = FloatArray(16)

    fun init() {
        val vert = compileShader(GLES20.GL_VERTEX_SHADER,   VERT_SRC)
        val frag = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAG_SRC)
        program  = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vert)
        GLES20.glAttachShader(program, frag)
        GLES20.glLinkProgram(program)
        aPos   = GLES20.glGetAttribLocation(program,  "aPos")
        uMVP   = GLES20.glGetUniformLocation(program, "uMVP")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
    }

    /**
     * @param roomModel       modello stanza con muri e aperture
     * @param baseY           floorY in world space (es. -0.93) — Y=0 nel modello = pavimento reale
     * @param hoveredWallId   id muro sotto il reticolo (null = nessuno)
     * @param selectedWallId  id muro selezionato per inserimento (null = nessuno)
     * @param viewMatrix      camera view
     * @param projMatrix      camera projection
     */
    fun draw(
        roomModel:      RoomModel,
        baseY:          Float,
        hoveredWallId:  String?,
        selectedWallId: String?,
        viewMatrix:     FloatArray,
        projMatrix:     FloatArray
    ) {
        if (program == 0) return
        Matrix.multiplyMM(mvp, 0, projMatrix, 0, viewMatrix, 0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)

        for (wall in roomModel.walls) {
            when (wall.id) {
                selectedWallId -> drawWallHighlight(wall, baseY, COLOR_SEL_FILL,   COLOR_SEL_OUTLINE,   7f)
                hoveredWallId  -> drawWallHighlight(wall, baseY, COLOR_HOVER_FILL, COLOR_HOVER_OUTLINE, 5f)
            }
            for (opening in wall.openings) {
                drawOpening(wall, baseY, opening)
            }
        }

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(0)
    }

    // ── Wall highlight ─────────────────────────────────────────────────────────

    // baseY = floor Y in world space (es. -0.93). Le coordinate Y del modello
    // sono floor-relative (0 = pavimento), quindi world_Y = baseY + model_Y.

    private fun drawWallHighlight(
        wall: WallModel, baseY: Float,
        fillColor: FloatArray, outlineColor: FloatArray, lineW: Float
    ) {
        val sx = wall.start[0]; val sz = wall.start[2]
        val ex = wall.end[0];   val ez = wall.end[2]
        val yFloor = baseY
        val yTop   = baseY + wall.height
        val quad = floatArrayOf(
            sx, yFloor, sz,
            ex, yFloor, ez,
            ex, yTop,   ez,
            sx, yTop,   sz
        )
        // Fill semitrasparente
        setColor(fillColor)
        drawPrimitive(GLES20.GL_TRIANGLE_FAN, quad, 4)
        // Outline pieno e spesso
        setColor(outlineColor)
        GLES20.glLineWidth(lineW)
        drawPrimitive(GLES20.GL_LINE_LOOP, quad, 4)
    }

    // ── Opening box ───────────────────────────────────────────────────────────

    private fun drawOpening(wall: WallModel, baseY: Float, opening: OpeningModel) {
        val dx = wall.dirX; val dz = wall.dirZ

        val x0 = wall.start[0] + dx * opening.offsetAlongWall
        val z0 = wall.start[2] + dz * opening.offsetAlongWall
        val x1 = wall.start[0] + dx * (opening.offsetAlongWall + opening.width)
        val z1 = wall.start[2] + dz * (opening.offsetAlongWall + opening.width)
        // Y: floor-relative → world space
        val yB = baseY + opening.bottom
        val yT = baseY + opening.bottom + opening.height

        val fillColor = when (opening.kind) {
            OpeningKind.DOOR        -> COLOR_DOOR
            OpeningKind.WINDOW      -> COLOR_WINDOW
            OpeningKind.FRENCH_DOOR -> COLOR_FRENCH_DOOR
        }

        // Fill (traslucido)
        setColor(fillColor)
        // Disegniamo come due triangoli (GL_TRIANGLE_FAN)
        drawPrimitive(GLES20.GL_TRIANGLE_FAN, floatArrayOf(
            x0, yB, z0,
            x1, yB, z1,
            x1, yT, z1,
            x0, yT, z0
        ), 4)

        // Bordo
        setColor(COLOR_OPENING_BORDER)
        GLES20.glLineWidth(3f)
        drawPrimitive(GLES20.GL_LINE_LOOP, floatArrayOf(
            x0, yB, z0,
            x1, yB, z1,
            x1, yT, z1,
            x0, yT, z0
        ), 4)

        // Label helper: lineetta al centro in basso (indica offset point)
        val cx = (x0 + x1) / 2f; val cz = (z0 + z1) / 2f
        setColor(COLOR_OPENING_BORDER)
        GLES20.glLineWidth(2f)
        drawPrimitive(GLES20.GL_LINES, floatArrayOf(
            cx, yB - 0.04f, cz, cx, yB + 0.04f, cz
        ), 2)
    }

    // ── GL helpers ────────────────────────────────────────────────────────────

    private fun setColor(c: FloatArray) = GLES20.glUniform4f(uColor, c[0], c[1], c[2], c[3])

    private fun drawPrimitive(mode: Int, verts: FloatArray, count: Int) {
        val buf = makeBuffer(verts)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glDrawArrays(mode, 0, count)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun makeBuffer(verts: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(verts); position(0) }

    private fun compileShader(type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        return id
    }
}
