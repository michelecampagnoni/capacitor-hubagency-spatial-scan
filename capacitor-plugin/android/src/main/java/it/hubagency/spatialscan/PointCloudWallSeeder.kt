package it.hubagency.spatialscan

import com.google.ar.core.Frame
import kotlin.math.*

/**
 * V3-B: Estrae seed di muro dal point cloud ARCore.
 *
 * Pipeline ogni 500ms:
 *  1. Accumula punti XZ dal point cloud filtrati per confidence e altezza muro
 *  2. RANSAC multi-linea sul buffer accumulato (max 4 linee)
 *  3. Ogni linea → WallSeed → feedSeed() nel tracker
 *
 * RANSAC matematica corretta:
 *  - Linea: P·n̂ = d  (n̂ = normale unitaria, d = distanza dall'origine)
 *  - Inlier: |P·n̂ - d| < INLIER_DIST
 *  - Endpoint: proiettare inliers sull'asse ĝ = perp(n̂)
 *    t_i = P_i · ĝ   (scalare, coord assoluta)
 *    endpoint = t * ĝ + d * n̂   (punto 2D)
 */
class PointCloudWallSeeder {

    companion object {
        private const val CONF_THRESH     = 0.25f
        private const val FLOOR_MARGIN_LO = 0.20f
        private const val FLOOR_MARGIN_HI = 2.20f
        private const val BUFFER_SIZE     = 3000
        private const val SEED_INTERVAL_MS = 500L
        private const val RANSAC_ITERS    = 60
        private const val INLIER_DIST     = 0.08f   // 8cm
        private const val MIN_INLIERS     = 10
        private const val MAX_LINES       = 4
        private const val MIN_SEG_LEN     = 0.20f   // segmento minimo 20cm
    }

    // Buffer XZ di punti a altezza muro — sliding window
    private val buffer = ArrayList<FloatArray>(BUFFER_SIZE)
    private var lastSeedMs = 0L

    /** Campiona il frame corrente e, ogni 500ms, estrae seeds per il tracker. */
    fun process(frame: Frame, floorY: Float, nowMs: Long, tracker: WallHypothesisTracker) {
        // 1. Campiona punti dal frame corrente
        try {
            frame.acquirePointCloud().use { cloud ->
                val pts   = cloud.points
                val count = cloud.ids.limit()
                repeat(count) { i ->
                    val x    = pts[i * 4]
                    val y    = pts[i * 4 + 1]
                    val z    = pts[i * 4 + 2]
                    val conf = pts[i * 4 + 3]
                    if (conf < CONF_THRESH) return@repeat
                    if (y < floorY + FLOOR_MARGIN_LO || y > floorY + FLOOR_MARGIN_HI) return@repeat
                    if (buffer.size < BUFFER_SIZE) {
                        buffer.add(floatArrayOf(x, z))
                    } else {
                        // Reservoir replacement: sostituisce un punto casuale
                        buffer[(Math.random() * BUFFER_SIZE).toInt()] = floatArrayOf(x, z)
                    }
                }
            }
        } catch (_: Exception) { return }

        // 2. Estrai linee ogni 500ms se abbastanza punti
        if (nowMs - lastSeedMs < SEED_INTERVAL_MS || buffer.size < MIN_INLIERS * 2) return
        lastSeedMs = nowMs

        val seeds = ransacLines(buffer)
        seeds.forEach { tracker.feedSeed(it, nowMs) }
    }

    fun reset() { buffer.clear(); lastSeedMs = 0L }

    // ── RANSAC multi-linea ────────────────────────────────────────────────────

    private fun ransacLines(pts: List<FloatArray>): List<WallSeed> {
        val remaining = pts.toMutableList()
        val results   = mutableListOf<WallSeed>()

        repeat(MAX_LINES) {
            if (remaining.size < MIN_INLIERS) return results

            var bestInliers = emptyList<FloatArray>()
            var bestSeed: WallSeed? = null

            repeat(RANSAC_ITERS) {
                // Campiona 2 punti casuali
                val i = (Math.random() * remaining.size).toInt()
                var j = (Math.random() * remaining.size).toInt()
                if (j == i) j = (j + 1) % remaining.size

                val p1 = remaining[i]; val p2 = remaining[j]
                val ex = p2[0] - p1[0]; val ez = p2[1] - p1[1]
                val eLen = sqrt(ex * ex + ez * ez)
                if (eLen < 0.05f) return@repeat   // punti troppo vicini

                // Direzione e normale unitarie
                val dHatX = ex / eLen; val dHatZ = ez / eLen   // ĝ: asse del muro
                val nHatX = -dHatZ;    val nHatZ =  dHatX      // n̂: normale al muro

                // d = distanza con segno dall'origine alla retta
                val d = p1[0] * nHatX + p1[1] * nHatZ

                // Inliers: punti entro INLIER_DIST dalla retta
                val inliers = remaining.filter { p ->
                    abs(p[0] * nHatX + p[1] * nHatZ - d) < INLIER_DIST
                }
                if (inliers.size <= bestInliers.size) return@repeat

                // Proietta gli inliers sull'asse ĝ per trovare endpoint
                // t_i = P_i · ĝ  (coordinata scalare assoluta lungo ĝ)
                val ts    = inliers.map { p -> p[0] * dHatX + p[1] * dHatZ }
                val tMin  = ts.min()
                val tMax  = ts.max()
                val segLen = tMax - tMin
                if (segLen < MIN_SEG_LEN) return@repeat

                // Ricostruzione endpoint in world XZ:
                // endpoint = t * ĝ + d * n̂
                val x1 = tMin * dHatX + d * nHatX
                val z1 = tMin * dHatZ + d * nHatZ
                val x2 = tMax * dHatX + d * nHatX
                val z2 = tMax * dHatZ + d * nHatZ

                // Angolo del muro in [0, PI)
                var angle = atan2(dHatZ, dHatX)
                if (angle < 0f)            angle += PI.toFloat()
                if (angle >= PI.toFloat()) angle -= PI.toFloat()

                bestInliers = inliers
                bestSeed    = WallSeed(Segment2D(x1, z1, x2, z2), angle, d)
            }

            val seed = bestSeed ?: return results
            if (bestInliers.size < MIN_INLIERS) return results

            results.add(seed)
            // Rimuovi inliers per trovare la prossima linea
            val inlierSet = bestInliers.map { System.identityHashCode(it) }.toHashSet()
            remaining.removeAll { System.identityHashCode(it) in inlierSet }
        }
        return results
    }
}

/** Seed di muro estratto da una sorgente esterna (point cloud, ecc.) */
data class WallSeed(val segment: Segment2D, val angle: Float, val offset: Float)
