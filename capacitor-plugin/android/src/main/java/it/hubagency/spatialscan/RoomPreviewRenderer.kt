package it.hubagency.spatialscan

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Plane
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Renderizza un'anteprima 3D minimalista in un sotto-viewport (top-right).
 * Usa WallHypothesis (geometria stabile/confermata) — non raw ARCore planes.
 */
class RoomPreviewRenderer {

    companion object {
        private const val VERT = """
            attribute vec4 a_Pos;
            uniform mat4 u_MVP;
            void main() { gl_Position = u_MVP * a_Pos; }
        """
        private const val FRAG = """
            precision mediump float;
            uniform vec4 u_Color;
            uniform vec3 u_Normal;
            void main() {
                vec3 n = normalize(u_Normal);
                vec3 L = normalize(vec3(0.5, 1.0, 0.5));
                float diff = max(dot(n, L), 0.0);
                float light = diff * 0.60 + 0.40;
                gl_FragColor = vec4(u_Color.rgb * light, u_Color.a);
            }
        """
    }

    private var program = 0
    private var aPos    = 0
    private var uMVP    = 0
    private var uColor  = 0
    private var uNormal = 0
    private var screenW = 0
    private var screenH = 0
    private var ready   = false

    private val mvp  = FloatArray(16)
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private var orbitAngle = 0f

    fun init() {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAG)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        aPos    = GLES20.glGetAttribLocation(program,  "a_Pos")
        uMVP    = GLES20.glGetUniformLocation(program, "u_MVP")
        uColor  = GLES20.glGetUniformLocation(program, "u_Color")
        uNormal = GLES20.glGetUniformLocation(program, "u_Normal")
        ready   = true
    }

    fun setScreenSize(w: Int, h: Int) { screenW = w; screenH = h }

    /**
     * @param walls      muri STABLE/CONFIRMED (WallHypothesis)
     * @param floorY     Y world del pavimento
     * @param wallHeight altezza stimata pareti
     * @param floorPlane piano pavimento ARCore (opzionale)
     *
     * Internamente applica RoomPostProcessor per mostrare il modello stanza
     * post-processato (snap ortogonale + gap closure) — separato dal live overlay.
     */
    fun draw(walls: List<WallHypothesis>, floorY: Float, wallHeight: Float, floorPlane: Plane?) {
        if (!ready || screenW == 0 || walls.isEmpty()) return

        // Post-processing: angoli snappati + gap chiusi
        // Il live overlay (ConfirmedWallRenderer) usa i segmenti raw; qui usiamo il modello ideale.
        val processedSegs = RoomPostProcessor.process(walls)
        // Associa ogni segmento processato al suo stato (per il colore)
        val wallStates = walls.map { it.isConfirmed }

        val size = (screenW * 0.22f).toInt().coerceAtLeast(80)
        val vpX  = screenW - size - 14
        val vpY  = screenH - size - 52

        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(vpX, vpY, size, size)
        GLES20.glViewport(vpX, vpY, size, size)
        GLES20.glClearColor(0.04f, 0.06f, 0.14f, 0.90f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Centroide sui segmenti processati (geometria corretta per la camera)
        val cx = processedSegs.map { it.cx.toDouble() }.average().toFloat()
        val cz = processedSegs.map { it.cz.toDouble() }.average().toFloat()

        // Distanza camera automatica
        var maxDist = 2.5f
        processedSegs.forEach { s ->
            val dx = s.cx - cx; val dz = s.cz - cz
            maxDist = maxOf(maxDist, sqrt(dx * dx + dz * dz) + s.length / 2f + 0.5f)
        }
        val camDist = maxDist * 1.7f

        // Orbita lenta
        orbitAngle = (orbitAngle + 0.25f) % 360f
        val rad  = Math.toRadians(orbitAngle.toDouble()).toFloat()
        val elev = Math.toRadians(35.0).toFloat()
        val camX = cx + camDist * cos(elev) * sin(rad)
        val camY = camDist * sin(elev) + floorY
        val camZ = cz + camDist * cos(elev) * cos(rad)
        val lookY = floorY + wallHeight / 2f  // guarda al centro parete, non al pavimento

        Matrix.setLookAtM(view, 0, camX, camY, camZ, cx, lookY, cz, 0f, 1f, 0f)
        Matrix.perspectiveM(proj, 0, 52f, 1f, 0.1f, camDist * 5f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)

        // ── Pavimento ─────────────────────────────────────────────────────────
        floorPlane?.let { fp ->
            val hw = fp.extentX / 2f; val hh = fp.extentZ / 2f
            val pose = fp.centerPose
            val corners = listOf(
                floatArrayOf(-hw, 0f, -hh), floatArrayOf(hw, 0f, -hh),
                floatArrayOf(hw, 0f, hh),   floatArrayOf(-hw, 0f, hh)
            ).map { pose.transformPoint(it) }
            GLES20.glUniform4f(uColor, 0.20f, 0.42f, 0.90f, 0.40f)
            GLES20.glUniform3f(uNormal, 0f, 1f, 0f)
            drawCorners(corners)
        }

        // ── Pareti (geometria post-processata) ────────────────────────────────
        processedSegs.forEachIndexed { i, s ->
            val isConfirmed = wallStates.getOrElse(i) { false }
            val topY = floorY + wallHeight
            val corners = listOf(
                floatArrayOf(s.x1, floorY, s.z1), floatArrayOf(s.x2, floorY, s.z2),
                floatArrayOf(s.x2, topY,   s.z2), floatArrayOf(s.x1, topY,   s.z1)
            )

            val dx = s.x2 - s.x1; val dz = s.z2 - s.z1
            val len = sqrt(dx * dx + dz * dz)
            val nx = if (len > 0f) -dz / len else 0f
            val nz = if (len > 0f)  dx / len else 1f

            val wallAlpha = if (isConfirmed) 0.80f else 0.50f
            val wallColor = if (isConfirmed)
                floatArrayOf(0.82f, 0.92f, 1.0f) else floatArrayOf(0.55f, 0.75f, 1.0f)

            GLES20.glUniform4f(uColor, wallColor[0], wallColor[1], wallColor[2], wallAlpha)
            GLES20.glUniform3f(uNormal, nx, 0f, nz)
            drawCorners(corners.map { it.copyOf() })

            val edgeAlpha = if (isConfirmed) 0.95f else 0.70f
            GLES20.glUniform4f(uColor, 0.5f, 0.80f, 1.0f, edgeAlpha)
            drawWireCorners(corners.map { it.copyOf() })
        }

        // ── Ripristina ─────────────────────────────────────────────────────────
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, screenW, screenH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun buildBuf(corners: List<FloatArray>): java.nio.FloatBuffer {
        val buf = ByteBuffer.allocateDirect(4 * 4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        corners.forEach { v -> buf.put(v[0]); buf.put(v[1]); buf.put(v[2]); buf.put(1f) }
        buf.rewind(); return buf
    }

    private fun drawCorners(corners: List<FloatArray>) {
        val buf = buildBuf(corners)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun drawWireCorners(corners: List<FloatArray>) {
        val buf = buildBuf(corners)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glLineWidth(1.5f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, src)
        GLES20.glCompileShader(it)
    }
}
