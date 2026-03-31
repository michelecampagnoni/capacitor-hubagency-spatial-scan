package it.hubagency.spatialscan

import com.google.ar.core.Frame
import kotlin.math.sqrt

/**
 * V3-A: Campionamento e telemetria del point cloud ARCore.
 *
 * NON fa line fitting / RANSAC — serve a rispondere alla domanda:
 * "il point cloud su questo device è abbastanza ricco per V3?"
 *
 * Criteri di valutazione:
 *  - ≥15 punti/frame a altezza muro → sufficiente per line fitting
 *  - ≥200 punti accumulati in 10s   → sufficiente per RANSAC
 *  - Spread XZ > 0.5m               → punti distribuiti lungo superfici
 */
class PointCloudSampler {

    data class FrameSample(
        val totalPts:         Int,   // punti totali nel point cloud
        val afterConfidence:  Int,   // dopo filtro confidence > CONF_THRESH
        val atWallHeight:     Int,   // a altezza muro [floorY+0.2, floorY+2.2]
        val accumulatedTotal: Int,   // punti XZ accumulati nel buffer storico
        val spreadXm:         Float, // range X dei punti a muro (ampiezza orizzontale)
        val spreadZm:         Float  // range Z dei punti a muro
    )

    companion object {
        private const val CONF_THRESH     = 0.25f
        private const val FLOOR_MARGIN_LO = 0.20f   // m sopra il pavimento
        private const val FLOOR_MARGIN_HI = 2.20f   // m sopra il pavimento
        private const val MAX_BUFFER      = 5000
    }

    // Buffer storico punti XZ a altezza muro — reset solo con reset()
    private val buffer = ArrayList<FloatArray>(MAX_BUFFER)

    fun sample(frame: Frame, floorY: Float): FrameSample {
        var total = 0; var afterConf = 0; var atHeight = 0
        var xMin = Float.MAX_VALUE; var xMax = -Float.MAX_VALUE
        var zMin = Float.MAX_VALUE; var zMax = -Float.MAX_VALUE

        try {
            frame.acquirePointCloud().use { cloud ->
                total = cloud.ids.limit()
                val pts = cloud.points

                repeat(total) { i ->
                    val x    = pts[i * 4]
                    val y    = pts[i * 4 + 1]
                    val z    = pts[i * 4 + 2]
                    val conf = pts[i * 4 + 3]

                    if (conf < CONF_THRESH) return@repeat
                    afterConf++

                    val lo = floorY + FLOOR_MARGIN_LO
                    val hi = floorY + FLOOR_MARGIN_HI
                    if (y < lo || y > hi) return@repeat
                    atHeight++

                    // Aggiorna spread XZ
                    if (x < xMin) xMin = x; if (x > xMax) xMax = x
                    if (z < zMin) zMin = z; if (z > zMax) zMax = z

                    // Accumula nel buffer storico (sliding window)
                    if (buffer.size < MAX_BUFFER) {
                        buffer.add(floatArrayOf(x, z))
                    } else {
                        // Rimpiazza un punto casuale (reservoir sampling semplificato)
                        buffer[(Math.random() * MAX_BUFFER).toInt()] = floatArrayOf(x, z)
                    }
                }
            }
        } catch (_: Exception) { /* point cloud non disponibile in questo frame */ }

        val spreadX = if (xMax > xMin) xMax - xMin else 0f
        val spreadZ = if (zMax > zMin) zMax - zMin else 0f

        return FrameSample(total, afterConf, atHeight, buffer.size, spreadX, spreadZ)
    }

    /** Centroide XZ dei punti accumulati — utile per orientare la visualizzazione. */
    fun accumulatedCentroid(): Pair<Float, Float> {
        if (buffer.isEmpty()) return Pair(0f, 0f)
        val cx = buffer.map { it[0] }.average().toFloat()
        val cz = buffer.map { it[1] }.average().toFloat()
        return Pair(cx, cz)
    }

    /** Densità stimata: punti per m² nell'area XZ coperta dal buffer. */
    fun estimateDensity(): Float {
        if (buffer.size < 4) return 0f
        val xs = buffer.map { it[0] }; val zs = buffer.map { it[1] }
        val area = (xs.max() - xs.min()) * (zs.max() - zs.min())
        return if (area > 0.01f) buffer.size / area else 0f
    }

    /** Restituisce una snapshot immutabile del buffer XZ accumulato. Usato da RoomBoxEstimator. */
    fun getBuffer(): List<FloatArray> = buffer.toList()

    fun reset() { buffer.clear() }
}
