package it.hubagency.spatialscan

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renderer AR — palette sci-fi Hubique.
 *
 * Layer (ordine draw):
 *  -1. Floor grid (piano XZ) — griglia violet sul pavimento
 *   0. Wall fill violet + griglia proiettata prominente
 *   0b. Live wall fill fucsia
 *   0c. Height preview cyan con frecce — solo AWAIT_HEIGHT
 *   1.  Axis hints
 *   2.  Segmenti confermati cyan
 *   3.  Preview chiusura
 *   4.  Chiusura confermata
 *   5.  Segmento live fucsia
 *   5b. Ghost corner fucsia
 *   6.  Punti vertice cyan
 *   7.  Scheletro 3D violet (post-close)
 *
 * Firma draw() compatibile backward: [floorGridCenter] è opzionale con default null.
 */
class PerimeterRenderer {

    companion object {
        private const val TAG         = "PerimeterRenderer"
        private const val FLOOR_OFFSET = 0.005f

        // ── Palette sci-fi Hubique ────────────────────────────────────────────
        private val COLOR_CONFIRMED      = floatArrayOf(0.12f, 0.85f, 1.00f, 1.00f)   // cyan
        private val COLOR_CLOSE_HINT     = floatArrayOf(0.12f, 0.85f, 1.00f, 0.28f)
        private val COLOR_LIVE           = floatArrayOf(0.92f, 0.08f, 0.58f, 0.95f)   // fucsia
        private val COLOR_GHOST_CORNER   = floatArrayOf(0.92f, 0.08f, 0.60f, 1.00f)
        private val COLOR_DOT            = floatArrayOf(0.12f, 0.90f, 1.00f, 1.00f)
        private val COLOR_WALL_PREVIEW   = floatArrayOf(0.48f, 0.18f, 0.88f, 0.55f)   // violet
        private val COLOR_AXIS_HINT      = floatArrayOf(0.50f, 0.18f, 0.85f, 0.18f)
        private val COLOR_WALL_FILL      = floatArrayOf(0.42f, 0.16f, 0.80f, 0.18f)   // violet fill
        private val COLOR_LIVE_WALL_FILL = floatArrayOf(0.90f, 0.08f, 0.58f, 0.22f)   // fucsia fill
        private val COLOR_HEIGHT_LINE    = floatArrayOf(0.08f, 0.90f, 1.00f, 1.00f)   // cyan
        // Griglia pareti — visibile, step 30cm
        private val COLOR_WALL_GRID      = floatArrayOf(0.65f, 0.30f, 1.00f, 0.28f)
        // Griglia pavimento — iconic, violet/blu
        private val COLOR_FLOOR_GRID     = floatArrayOf(0.55f, 0.22f, 0.92f, 0.22f)

        private val VERT_SRC = """
            uniform mat4 uMVP;
            attribute vec4 aPos;
            void main() {
                gl_Position  = uMVP * aPos;
                gl_PointSize = 20.0;
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
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "Link: ${GLES20.glGetProgramInfoLog(program)}")
        aPos   = GLES20.glGetAttribLocation(program,  "aPos")
        uMVP   = GLES20.glGetUniformLocation(program, "uMVP")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
    }

    /**
     * [floorGridCenter]: centro del grid pavimento (world XZ). Opzionale (default=null).
     * Backward-compatible: il caller può non passarlo.
     */
    fun draw(
        confirmedPts:        List<FloatArray>,
        livePoint:           FloatArray?,
        isClosed:            Boolean,
        canClose:            Boolean,
        wallHeight:          Float,
        capturePhase:        PerimeterCapture.CapturePhase,
        liveHeightM:         Float?,
        viewMatrix:          FloatArray,
        projMatrix:          FloatArray,
        floorGridCenter:     FloatArray? = null,
        reticleSnapped:      Boolean     = false,
        goniometerCenter:    FloatArray? = null,  // null = goniometro nascosto
        goniometerAngle:     Float       = 0f,    // angolo corrente reticolo (rad)
        goniometerSnapAngle: Float?      = null,  // angolo snappato da evidenziare (rad)
        currentFloorY:       Float?      = null   // lastFloorY da ScanningActivity
    ) {
        if (program == 0) return

        Matrix.multiplyMM(mvp, 0, projMatrix, 0, viewMatrix, 0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)

        val floorY = confirmedPts.firstOrNull()?.get(1) ?: (livePoint?.get(1) ?: 0f)
        val drawY  = floorY + FLOOR_OFFSET

        // ── -1. Floor grid — DISABILITATA (amplifica jitter, esteticamente scadente) ──
        // val gridCenter = floorGridCenter ?: confirmedPts.firstOrNull()
        // if (gridCenter != null) { drawFloorGrid(gridCenter[0], gridCenter[2], drawY) }

        // ── 0. Wall fill violet + griglia prominente ───────────────────────────
        if (!isClosed && confirmedPts.size >= 2) {
            drawConfirmedWallFills(confirmedPts, drawY, wallHeight)
            drawWallGridLines(confirmedPts, drawY, wallHeight)
        }

        // ── 0b. Live wall fill fucsia ──────────────────────────────────────────
        if (!isClosed && confirmedPts.isNotEmpty() && livePoint != null) {
            drawLiveWallFill(confirmedPts.last(), livePoint, drawY, wallHeight)
        }

        // ── 0c. Height preview cyan con frecce ────────────────────────────────
        if (capturePhase == PerimeterCapture.CapturePhase.AWAIT_HEIGHT &&
            confirmedPts.size == 1 && liveHeightM != null && liveHeightM > 0.1f) {
            drawHeightPreview(confirmedPts[0], drawY, liveHeightM)
        }

        // ── 1. Axis hints ─────────────────────────────────────────────────────
        if (!isClosed && confirmedPts.size >= 2)
            drawAxisHints(confirmedPts.last(), confirmedPts[confirmedPts.size - 2], drawY)

        // ── 2. Segmenti confermati cyan ────────────────────────────────────────
        if (confirmedPts.size >= 2) {
            val v = FloatArray(confirmedPts.size * 3)
            confirmedPts.forEachIndexed { i, pt -> v[i*3]=pt[0]; v[i*3+1]=drawY; v[i*3+2]=pt[2] }
            setColor(COLOR_CONFIRMED); GLES20.glLineWidth(5f)
            drawPrimitive(GLES20.GL_LINE_STRIP, v, confirmedPts.size)
        }

        // ── 3. Preview chiusura — bordo ciano parete di chiusura ──────────────
        if (!isClosed && canClose && confirmedPts.size >= 2) {
            val last  = confirmedPts.last()
            val first = confirmedPts.first()
            val topY  = drawY + wallHeight
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            // Bordo rettangolare della parete (LINE_LOOP)
            setColor(floatArrayOf(0.12f, 0.85f, 1.00f, 0.60f))
            GLES20.glLineWidth(3f)
            drawPrimitive(GLES20.GL_LINE_LOOP, floatArrayOf(
                last[0],  drawY, last[2],
                first[0], drawY, first[2],
                first[0], topY,  first[2],
                last[0],  topY,  last[2]
            ), 4)
            // Linee verticali agli spigoli per enfatizzare gli edge
            GLES20.glLineWidth(2f)
            drawPrimitive(GLES20.GL_LINES, floatArrayOf(
                last[0],  drawY, last[2],  last[0],  topY, last[2],
                first[0], drawY, first[2], first[0], topY, first[2]
            ), 4)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        // ── 4. Chiusura confermata ─────────────────────────────────────────────
        if (isClosed && confirmedPts.size >= 3) {
            val last = confirmedPts.last(); val first = confirmedPts.first()
            setColor(COLOR_CONFIRMED); GLES20.glLineWidth(5f)
            drawPrimitive(GLES20.GL_LINES, floatArrayOf(
                last[0], drawY, last[2], first[0], drawY, first[2]), 2)
        }

        // ── 5. Segmento live fucsia ────────────────────────────────────────────
        if (!isClosed && confirmedPts.isNotEmpty() && livePoint != null) {
            val prev = confirmedPts.last()
            setColor(COLOR_LIVE); GLES20.glLineWidth(6f)
            drawPrimitive(GLES20.GL_LINES, floatArrayOf(
                prev[0], drawY, prev[2], livePoint[0], drawY, livePoint[2]), 2)
        }

        // ── 5c. Edge verticale ciano al ghost corner ───────────────────────────
        // Visibile da AWAIT_SECOND_FLOOR in poi (capturePhase != AWAIT_HEIGHT e
        // != AWAIT_FIRST_FLOOR). Mostra dove terminerebbe la parete corrente:
        // l'utente allinea questo edge con l'angolo reale della stanza.
        if (!isClosed && livePoint != null &&
            capturePhase != PerimeterCapture.CapturePhase.AWAIT_FIRST_FLOOR &&
            capturePhase != PerimeterCapture.CapturePhase.AWAIT_HEIGHT) {
            val topY = drawY + wallHeight
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            setColor(floatArrayOf(0.12f, 0.85f, 1.00f, 0.75f))
            GLES20.glLineWidth(3f)
            drawPrimitive(GLES20.GL_LINES, floatArrayOf(
                livePoint[0], drawY, livePoint[2],
                livePoint[0], topY,  livePoint[2]
            ), 2)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        // ── 5a. Goniometro a terra ─────────────────────────────────────────────
        // Y: usa lastFloorY dal caller (stima floor attuale), non il Y del punto
        // confermato che può avere drift ARCore. FLOOR_OFFSET evita z-fighting.
        if (goniometerCenter != null) {
            val gonioY = (currentFloorY ?: goniometerCenter[1]) + FLOOR_OFFSET
            drawGoniometer(goniometerCenter[0], gonioY, goniometerCenter[2],
                           goniometerAngle, goniometerSnapAngle)
        }

        // ── 5b. Ghost corner — ciano se agganciato all'asse, fucsia altrimenti ──
        if (!isClosed && livePoint != null)
            drawGhostCorner(livePoint[0], drawY, livePoint[2], reticleSnapped)

        // ── 6. Punti vertice cyan ──────────────────────────────────────────────
        if (confirmedPts.isNotEmpty()) {
            val v = FloatArray(confirmedPts.size * 3)
            confirmedPts.forEachIndexed { i, pt -> v[i*3]=pt[0]; v[i*3+1]=drawY+0.005f; v[i*3+2]=pt[2] }
            setColor(COLOR_DOT)
            drawPrimitive(GLES20.GL_POINTS, v, confirmedPts.size)
        }

        // ── 7. Scheletro 3D violet (post-close) ───────────────────────────────
        if (isClosed && confirmedPts.size >= 3)
            drawWallSkeleton(confirmedPts, drawY, wallHeight)

        GLES20.glUseProgram(0)
    }

    // ── Floor grid ─────────────────────────────────────────────────────────────

    /**
     * Griglia XZ sul piano pavimento — la feature iconica della reference.
     * Step 0.30m, extent 4m, violet semi-trasparente.
     * Il centro segue il reticolo per dare profondità spaziale.
     */
    private fun drawFloorGrid(cx: Float, cz: Float, y: Float) {
        val step   = 0.30f
        val extent = 4.0f
        // Snap center alla griglia per evitare "slide" visivo
        val snapX = (cx / step).roundToInt() * step
        val snapZ = (cz / step).roundToInt() * step

        val lines = mutableListOf<Float>()
        var x = snapX - extent
        while (x <= snapX + extent + 0.01f) {
            lines += listOf(x, y, snapZ - extent,  x, y, snapZ + extent)
            x += step
        }
        var z = snapZ - extent
        while (z <= snapZ + extent + 0.01f) {
            lines += listOf(snapX - extent, y, z,  snapX + extent, y, z)
            z += step
        }
        if (lines.isEmpty()) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        setColor(COLOR_FLOOR_GRID)
        GLES20.glLineWidth(1f)
        drawPrimitive(GLES20.GL_LINES, lines.toFloatArray(), lines.size / 3)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // ── Wall fills ─────────────────────────────────────────────────────────────

    private fun drawConfirmedWallFills(pts: List<FloatArray>, baseY: Float, wallH: Float) {
        val topY  = baseY + wallH
        val seg   = pts.size - 1
        val verts = FloatArray(seg * 6 * 3); var idx = 0
        for (i in 0 until seg) {
            val a = pts[i]; val b = pts[i + 1]
            verts[idx++]=a[0]; verts[idx++]=baseY; verts[idx++]=a[2]
            verts[idx++]=b[0]; verts[idx++]=baseY; verts[idx++]=b[2]
            verts[idx++]=b[0]; verts[idx++]=topY;  verts[idx++]=b[2]
            verts[idx++]=a[0]; verts[idx++]=baseY; verts[idx++]=a[2]
            verts[idx++]=b[0]; verts[idx++]=topY;  verts[idx++]=b[2]
            verts[idx++]=a[0]; verts[idx++]=topY;  verts[idx++]=a[2]
        }
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        setColor(COLOR_WALL_FILL)
        drawPrimitive(GLES20.GL_TRIANGLES, verts, seg * 6)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawLiveWallFill(prev: FloatArray, live: FloatArray, baseY: Float, wallH: Float) {
        val topY  = baseY + wallH
        val verts = floatArrayOf(
            prev[0], baseY, prev[2],  live[0], baseY, live[2],  live[0], topY, live[2],
            prev[0], baseY, prev[2],  live[0], topY,  live[2],  prev[0], topY, prev[2]
        )
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        setColor(COLOR_LIVE_WALL_FILL)
        drawPrimitive(GLES20.GL_TRIANGLES, verts, 6)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * Griglia proiettata sulle pareti — step 30cm, alpha 0.28.
     * Linee orizzontali (quote altezza) + verticali (distanze lungo muro).
     */
    private fun drawWallGridLines(pts: List<FloatArray>, baseY: Float, wallH: Float) {
        val step = 0.30f
        val topY = baseY + wallH
        val seg  = pts.size - 1
        val lines = mutableListOf<Float>()

        // Orizzontali
        var gy = baseY + step
        while (gy < topY - 0.05f) {
            for (i in 0 until seg) {
                val a = pts[i]; val b = pts[i + 1]
                lines += listOf(a[0], gy, a[2], b[0], gy, b[2])
            }
            gy += step
        }
        // Verticali
        for (i in 0 until seg) {
            val a = pts[i]; val b = pts[i + 1]
            val dx = b[0] - a[0]; val dz = b[2] - a[2]
            val segLen = sqrt(dx * dx + dz * dz); if (segLen < 0.01f) continue
            val ndx = dx / segLen; val ndz = dz / segLen
            var t = step
            while (t < segLen - 0.05f) {
                val gx = a[0] + ndx * t; val gz = a[2] + ndz * t
                lines += listOf(gx, baseY, gz, gx, topY, gz)
                t += step
            }
        }
        if (lines.isEmpty()) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        setColor(COLOR_WALL_GRID)
        GLES20.glLineWidth(2f)
        drawPrimitive(GLES20.GL_LINES, lines.toFloatArray(), lines.size / 3)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // ── Height preview cyan ─────────────────────────────────────────────────────

    private fun drawHeightPreview(p0: FloatArray, baseY: Float, heightM: Float) {
        val x    = p0[0]; val z = p0[2]
        val topY = baseY + heightM
        val aw   = 0.08f; val ah = 0.07f
        setColor(COLOR_HEIGHT_LINE)

        GLES20.glLineWidth(5f)
        drawPrimitive(GLES20.GL_LINES, floatArrayOf(x, baseY, z, x, topY, z), 2)

        GLES20.glLineWidth(3f)
        drawPrimitive(GLES20.GL_LINES, floatArrayOf(x - aw, topY, z, x + aw, topY, z), 2)

        // Freccia top
        drawPrimitive(GLES20.GL_LINES, floatArrayOf(
            x - aw * 0.6f, topY - ah, z,  x, topY, z,
            x + aw * 0.6f, topY - ah, z,  x, topY, z
        ), 4)
        // Freccia bottom
        drawPrimitive(GLES20.GL_LINES, floatArrayOf(
            x - aw * 0.6f, baseY + ah, z,  x, baseY, z,
            x + aw * 0.6f, baseY + ah, z,  x, baseY, z
        ), 4)
    }

    // ── Ghost corner fucsia ────────────────────────────────────────────────────

    private fun drawGhostCorner(x: Float, baseY: Float, z: Float, isSnapped: Boolean = false) {
        val y = baseY + 0.004f; val arm = 0.08f; val dia = 0.06f
        setColor(if (isSnapped) COLOR_CONFIRMED else COLOR_GHOST_CORNER)
        GLES20.glLineWidth(6f)
        drawPrimitive(GLES20.GL_LINES, floatArrayOf(
            x - arm, y, z,  x + arm, y, z,
            x, y, z - arm,  x, y, z + arm), 4)
        GLES20.glLineWidth(3f)
        drawPrimitive(GLES20.GL_LINES, floatArrayOf(
            x, y, z-dia,   x+dia, y, z,
            x+dia, y, z,   x, y, z+dia,
            x, y, z+dia,   x-dia, y, z,
            x-dia, y, z,   x, y, z-dia), 8)
    }

    // ── Axis hints ─────────────────────────────────────────────────────────────

    private fun drawAxisHints(last: FloatArray, prev: FloatArray, baseY: Float) {
        val dx = last[0] - prev[0]; val dz = last[2] - prev[2]
        val len = sqrt(dx * dx + dz * dz); if (len < 0.01f) return
        val nx = dx / len; val nz = dz / len; val px = -nz; val pz = nx
        val arm = 1.2f; val y = baseY + 0.003f; val bx = last[0]; val bz = last[2]
        setColor(COLOR_AXIS_HINT); GLES20.glLineWidth(2f)
        drawPrimitive(GLES20.GL_LINES, floatArrayOf(
            bx, y, bz,  bx + nx*arm, y, bz + nz*arm,
            bx, y, bz,  bx + px*arm, y, bz + pz*arm,
            bx, y, bz,  bx - px*arm, y, bz - pz*arm), 6)
    }

    // ── Wall skeleton violet (post-close) ─────────────────────────────────────

    private fun drawWallSkeleton(pts: List<FloatArray>, baseY: Float, wallH: Float) {
        val topY = baseY + wallH; val n = pts.size
        val v = FloatArray(n * 4 * 3); var idx = 0
        for (pt in pts) {
            v[idx++]=pt[0]; v[idx++]=baseY; v[idx++]=pt[2]
            v[idx++]=pt[0]; v[idx++]=topY;  v[idx++]=pt[2]
        }
        for (i in 0 until n) {
            val a = pts[i]; val b = pts[(i+1) % n]
            v[idx++]=a[0]; v[idx++]=topY; v[idx++]=a[2]
            v[idx++]=b[0]; v[idx++]=topY; v[idx++]=b[2]
        }
        setColor(COLOR_WALL_PREVIEW); GLES20.glLineWidth(2f)
        drawPrimitive(GLES20.GL_LINES, v, v.size / 3)
    }

    // ── Goniometro a terra ─────────────────────────────────────────────────────

    /**
     * Goniometro 3D a terra — aspetto goniometro fisico (rif. immagine 1).
     * Struttura: arco esterno + arco interno + raggi ogni 10° + tacche 1°/5°/10°.
     * Colore: ciano 0.70 alpha. Settore ±60° intorno alla direzione del reticolo.
     * Raggio snap in ciano pieno che buca entrambi gli archi.
     */
    private fun drawGoniometer(
        cx: Float, y: Float, cz: Float,
        reticleAngle: Float, snapAngle: Float?
    ) {
        val PI_F       = Math.PI.toFloat()
        val DEG1_RAD   = (PI_F / 180f)
        val STEP1_RAD  = DEG1_RAD          // passo tacche fini
        val STEP5_RAD  = DEG1_RAD * 5f
        val STEP10_RAD = DEG1_RAD * 10f
        val SECTOR_RAD = DEG1_RAD * 65f    // ±65° → allineato a griglia 10°
        val R_OUT      = 0.40f             // arco esterno — 40cm reali a terra
        val R_IN       = 0.17f             // arco interno
        val y2         = y + 0.003f
        val CYAN70     = floatArrayOf(0.12f, 0.85f, 1.00f, 0.70f)
        val CYAN_FULL  = floatArrayOf(0.12f, 0.85f, 1.00f, 1.00f)

        // Bordi settore allineati alla griglia 10°
        val startA = (((reticleAngle - SECTOR_RAD) / STEP10_RAD).toInt()) * STEP10_RAD
        val endA   = startA + SECTOR_RAD * 2f + STEP10_RAD

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        setColor(CYAN70)

        // ── 1. Arco esterno (LINE_STRIP, passo 1°) ───────────────────────────
        val outerArc = mutableListOf<Float>()
        var a = startA
        while (a <= endA + STEP1_RAD * 0.5f) {
            outerArc += cx + cos(a) * R_OUT; outerArc += y2; outerArc += cz + sin(a) * R_OUT
            a += STEP1_RAD
        }
        GLES20.glLineWidth(2f)
        drawPrimitive(GLES20.GL_LINE_STRIP, outerArc.toFloatArray(), outerArc.size / 3)

        // ── 2. Arco interno (LINE_STRIP, passo 1°) ───────────────────────────
        val innerArc = mutableListOf<Float>()
        a = startA
        while (a <= endA + STEP1_RAD * 0.5f) {
            innerArc += cx + cos(a) * R_IN; innerArc += y2; innerArc += cz + sin(a) * R_IN
            a += STEP1_RAD
        }
        GLES20.glLineWidth(1f)
        drawPrimitive(GLES20.GL_LINE_STRIP, innerArc.toFloatArray(), innerArc.size / 3)

        // ── 3. Raggi arco interno → esterno ogni 10° ─────────────────────────
        val spokes = mutableListOf<Float>()
        a = startA
        while (a <= endA) {
            spokes += cx + cos(a) * R_IN;  spokes += y2; spokes += cz + sin(a) * R_IN
            spokes += cx + cos(a) * R_OUT; spokes += y2; spokes += cz + sin(a) * R_OUT
            a += STEP10_RAD
        }
        GLES20.glLineWidth(1f)
        drawPrimitive(GLES20.GL_LINES, spokes.toFloatArray(), spokes.size / 3)

        // ── 4. Tacche sull'arco esterno (ogni 1°) ────────────────────────────
        // Lunghezze: 1°=2cm · 5°=4cm · 10°=già coperto dai raggi
        val ticks = mutableListOf<Float>()
        a = startA
        while (a <= endA) {
            val degRaw = (a * 180f / PI_F).roundToInt()
            val deg    = ((degRaw % 360) + 360) % 360
            val tickLen = when {
                deg % 10 == 0 -> 0f       // già disegnato dai raggi
                deg % 5  == 0 -> 0.04f
                else          -> 0.02f
            }
            if (tickLen > 0f) {
                ticks += cx + cos(a) * R_OUT
                ticks += y2
                ticks += cz + sin(a) * R_OUT
                ticks += cx + cos(a) * (R_OUT + tickLen)
                ticks += y2
                ticks += cz + sin(a) * (R_OUT + tickLen)
            }
            a += STEP1_RAD
        }
        if (ticks.isNotEmpty()) {
            GLES20.glLineWidth(1f)
            drawPrimitive(GLES20.GL_LINES, ticks.toFloatArray(), ticks.size / 3)
        }

        // ── 5. Tacche sull'arco interno (ogni 5°, verso centro) ──────────────
        val innerTicks = mutableListOf<Float>()
        a = startA
        while (a <= endA) {
            val degRaw = (a * 180f / PI_F).roundToInt()
            val deg    = ((degRaw % 360) + 360) % 360
            val tickLen = when {
                deg % 10 == 0 -> 0f       // già coperto dai raggi
                deg % 5  == 0 -> 0.03f
                else          -> 0f
            }
            if (tickLen > 0f) {
                innerTicks += cx + cos(a) * R_IN
                innerTicks += y2
                innerTicks += cz + sin(a) * R_IN
                innerTicks += cx + cos(a) * (R_IN - tickLen)
                innerTicks += y2
                innerTicks += cz + sin(a) * (R_IN - tickLen)
            }
            a += STEP5_RAD
        }
        if (innerTicks.isNotEmpty()) {
            GLES20.glLineWidth(1f)
            drawPrimitive(GLES20.GL_LINES, innerTicks.toFloatArray(), innerTicks.size / 3)
        }

        // ── 6. Raggio snap — ciano pieno, buca entrambi gli archi ────────────
        if (snapAngle != null) {
            setColor(CYAN_FULL)
            GLES20.glLineWidth(3f)
            drawPrimitive(GLES20.GL_LINES, floatArrayOf(
                cx, y2, cz,
                cx + cos(snapAngle) * (R_OUT + 0.10f), y2, cz + sin(snapAngle) * (R_OUT + 0.10f)
            ), 2)
        }

        // ── 7. Punto centrale ─────────────────────────────────────────────────
        setColor(CYAN_FULL)
        drawPrimitive(GLES20.GL_POINTS, floatArrayOf(cx, y2 + 0.002f, cz), 1)

        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // ── GL helpers ─────────────────────────────────────────────────────────────

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
        GLES20.glShaderSource(id, src); GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "Shader [type=$type]: ${GLES20.glGetShaderInfoLog(id)}")
        return id
    }
}
