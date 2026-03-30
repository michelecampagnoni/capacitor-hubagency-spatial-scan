package it.hubagency.spatialscan

import android.util.Log
import kotlin.math.*

/**
 * Stima le dimensioni della stanza come rettangolo (RoomBox) dal buffer
 * accumulato del point cloud ARCore.
 *
 * Pipeline robusta (non usa min/max grezzi):
 *  1. Rejection outlier: filtra i punti fuori dal range 5°–95° percentile su X e Z
 *  2. PCA 2D sul set filtrato → asse dominante (heading) + asse perpendicolare
 *  3. Proietta tutti i punti filtrati sui 2 assi PCA
 *  4. Usa il range 5°–95° delle proiezioni come W e D (robusto agli outlier)
 *  5. Quantizza W e D a griglia 25cm
 *  6. Controllo stabilità su finestra scorrevole di STABILITY_WINDOW stime:
 *     box stabile ↔ heading stabile (< 5°) e dimensioni stabili (< 15cm variazione)
 *
 * Aggiornamento ogni UPDATE_INTERVAL_MS (500ms), non ogni frame.
 */
class RoomBoxEstimator {

    companion object {
        private const val TAG = "SpatialScan"

        private const val UPDATE_INTERVAL_MS  = 500L
        private const val MIN_POINTS          = 150    // punti minimi per stima
        private const val PERCENTILE_LO       = 0.05f  // 5° percentile (rejection outlier)
        private const val PERCENTILE_HI       = 0.95f  // 95° percentile
        private const val GRID_M              = 0.25f  // quantizzazione 25cm
        private const val MIN_DIM_M           = 0.75f  // dimensione minima plausibile
        private const val STABILITY_WINDOW    = 6      // stime nella finestra scorrevole
        private const val STABLE_HEADING_DEG  = 5f     // variazione heading max per "stabile"
        private const val STABLE_DIM_M        = 0.15f  // variazione dimensione max (15cm)
    }

    private var lastUpdateMs = 0L

    // Finestra scorrevole di stime
    private val history = ArrayDeque<RoomBox>(STABILITY_WINDOW + 1)

    // Stato pubblico
    var isStable = false;          private set
    var lastBox:  RoomBox? = null; private set

    // Metriche debug per logcat / overlay
    var dbgHeadingDeg:     Float = 0f; private set
    var dbgRawWidth:       Float = 0f; private set
    var dbgRawDepth:       Float = 0f; private set
    var dbgFilteredWidth:  Float = 0f; private set
    var dbgFilteredDepth:  Float = 0f; private set
    var dbgPointsIn:       Int   = 0;  private set
    var dbgPointsFiltered: Int   = 0;  private set
    var dbgStableCount:    Int   = 0;  private set

    /**
     * @param points  buffer XZ dal PointCloudSampler (ogni punto = floatArrayOf(x, z))
     * @param floorY  Y world pavimento (da FloorPlaneAnchor)
     * @param ceilingY Y world soffitto (floorY + cameraHeight * 1.4 circa)
     * @param nowMs   timestamp corrente
     * @return        ultima stima disponibile (null se non abbastanza punti)
     */
    fun update(points: List<FloatArray>, floorY: Float, ceilingY: Float, nowMs: Long): RoomBox? {
        if (nowMs - lastUpdateMs < UPDATE_INTERVAL_MS) return lastBox
        if (points.size < MIN_POINTS) return lastBox
        lastUpdateMs = nowMs
        dbgPointsIn = points.size

        // ── 1. Rejection outlier per X e Z ───────────────────────────────────
        val xs = points.map { it[0] }.sorted()
        val zs = points.map { it[1] }.sorted()
        val n  = xs.size
        val xLo = xs[(n * PERCENTILE_LO).toInt().coerceIn(0, n - 1)]
        val xHi = xs[(n * PERCENTILE_HI).toInt().coerceIn(0, n - 1)]
        val zLo = zs[(n * PERCENTILE_LO).toInt().coerceIn(0, n - 1)]
        val zHi = zs[(n * PERCENTILE_HI).toInt().coerceIn(0, n - 1)]

        val filtered = points.filter { p -> p[0] in xLo..xHi && p[1] in zLo..zHi }
        dbgPointsFiltered = filtered.size
        if (filtered.size < MIN_POINTS / 2) return lastBox

        // ── 2. PCA 2D: matrice di covarianza in XZ ────────────────────────────
        val meanX = filtered.sumOf { it[0].toDouble() }.toFloat() / filtered.size
        val meanZ = filtered.sumOf { it[1].toDouble() }.toFloat() / filtered.size

        var cxx = 0f; var czz = 0f; var cxz = 0f
        for (p in filtered) {
            val dx = p[0] - meanX; val dz = p[1] - meanZ
            cxx += dx * dx; czz += dz * dz; cxz += dx * dz
        }
        val sz = filtered.size.toFloat()
        cxx /= sz; czz /= sz; cxz /= sz

        // Autovalore maggiore della matrice 2×2 simmetrica [[cxx,cxz],[cxz,czz]]
        val halfTrace = (cxx + czz) / 2f
        val disc      = sqrt(((cxx - czz) / 2f).pow(2) + cxz.pow(2))
        val lambda1   = halfTrace + disc

        // Autovettore corrispondente a lambda1 → asse dominante (heading)
        val evX: Float; val evZ: Float
        if (abs(cxz) > 1e-7f) {
            val rx = lambda1 - czz; val rz = cxz
            val len = sqrt(rx * rx + rz * rz)
            if (len < 1e-7f) return lastBox
            evX = rx / len; evZ = rz / len
        } else {
            // Matrice diagonale: asse dominante = X o Z
            evX = if (cxx >= czz) 1f else 0f
            evZ = if (cxx >= czz) 0f else 1f
        }

        // Heading in [0, PI): angolo dell'asse dominante
        var heading = atan2(evZ, evX)
        if (heading < 0f)            heading += PI.toFloat()
        if (heading >= PI.toFloat()) heading -= PI.toFloat()
        dbgHeadingDeg = Math.toDegrees(heading.toDouble()).toFloat()

        // ── 3. Proiezione sui 2 assi PCA ─────────────────────────────────────
        // Asse 1 = (evX, evZ) = asse width
        // Asse 2 = (-evZ, evX) = asse depth (perpendicolare)
        val t1s = filtered.map { p -> p[0] * evX  + p[1] * evZ  }.sorted()
        val t2s = filtered.map { p -> p[0] * (-evZ) + p[1] * evX }.sorted()

        val m   = t1s.size
        val t1Lo = t1s[(m * PERCENTILE_LO).toInt().coerceIn(0, m - 1)]
        val t1Hi = t1s[(m * PERCENTILE_HI).toInt().coerceIn(0, m - 1)]
        val t2Lo = t2s[(m * PERCENTILE_LO).toInt().coerceIn(0, m - 1)]
        val t2Hi = t2s[(m * PERCENTILE_HI).toInt().coerceIn(0, m - 1)]

        dbgRawWidth  = t1s.last() - t1s.first()
        dbgRawDepth  = t2s.last() - t2s.first()
        val filtW    = t1Hi - t1Lo
        val filtD    = t2Hi - t2Lo
        dbgFilteredWidth  = filtW
        dbgFilteredDepth  = filtD

        if (filtW < MIN_DIM_M || filtD < MIN_DIM_M) return lastBox

        // ── 4. Quantizzazione 25cm ────────────────────────────────────────────
        val qW = (filtW / GRID_M).roundToInt() * GRID_M
        val qD = (filtD / GRID_M).roundToInt() * GRID_M
        if (qW < MIN_DIM_M || qD < MIN_DIM_M) return lastBox

        // Centro del box in coordinate mondo XZ
        val t1Ctr = (t1Lo + t1Hi) / 2f
        val t2Ctr = (t2Lo + t2Hi) / 2f
        val cx = t1Ctr * evX  + t2Ctr * (-evZ)
        val cz = t1Ctr * evZ  + t2Ctr * evX

        val box = RoomBox(cx, cz, qW, qD, heading, floorY, ceilingY)
        lastBox = box

        // ── 5. Stabilità su finestra scorrevole ───────────────────────────────
        history.addLast(box)
        if (history.size > STABILITY_WINDOW) history.removeFirst()
        dbgStableCount = history.size

        isStable = if (history.size >= STABILITY_WINDOW) {
            val headings = history.map { it.headingRad }
            val widths   = history.map { it.width }
            val depths   = history.map { it.depth }

            // heading: variazione massima tra tutte le coppie nella finestra
            // usa angleDiff assiare [0,PI)
            var maxHeadingVar = 0f
            for (i in headings.indices)
                for (j in i + 1 until headings.size)
                    maxHeadingVar = maxOf(maxHeadingVar, angleDiffAxial(headings[i], headings[j]))

            val headingStable = maxHeadingVar < Math.toRadians(STABLE_HEADING_DEG.toDouble()).toFloat()
            val widthStable   = (widths.max()  - widths.min())  < STABLE_DIM_M
            val depthStable   = (depths.max()  - depths.min()) < STABLE_DIM_M

            headingStable && widthStable && depthStable
        } else false

        Log.d(TAG, "BOX_EST " +
            "pts=${dbgPointsIn}→${dbgPointsFiltered} " +
            "heading=${"%.1f".format(dbgHeadingDeg)}° " +
            "rawW=${"%.2f".format(dbgRawWidth)}m rawD=${"%.2f".format(dbgRawDepth)}m " +
            "filtW=${"%.2f".format(filtW)}m filtD=${"%.2f".format(filtD)}m " +
            "qW=${"%.2f".format(qW)}m qD=${"%.2f".format(qD)}m " +
            "stable=$isStable (${history.size}/$STABILITY_WINDOW)"
        )

        return box
    }

    fun reset() {
        history.clear()
        lastBox = null
        isStable = false
        lastUpdateMs = 0L
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Differenza angolare per angoli assiali in [0, PI): risultato in [0, PI/2]. */
    private fun angleDiffAxial(a1: Float, a2: Float): Float {
        var d = abs(a1 - a2) % PI.toFloat()
        if (d > PI.toFloat() / 2f) d = PI.toFloat() - d
        return d
    }

    private fun Float.roundToInt(): Int = Math.round(this)
}
