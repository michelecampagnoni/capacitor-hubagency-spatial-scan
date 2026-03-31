package it.hubagency.spatialscan

import android.media.Image
import com.google.ar.core.Frame
import kotlin.math.*

/**
 * DepthProcessor usa ARCore Depth API (ARCore 1.44: acquireDepthImage16Bits → android.media.Image)
 * per raccogliere punti 3D e raffinare le misure delle pareti rilevate da WallDetector.
 *
 * Ogni 2 secondi campiona la depth map (1 pixel ogni 8), converte i pixel validi in
 * punti 3D mondo e accumula un point cloud. Al termine della scan raffina start/end dei muri.
 */
class DepthProcessor {

    private val accumulatedPoints = mutableListOf<Point3D>()
    private var lastDepthProcessingMs = 0L
    private val DEPTH_INTERVAL_MS = 2000L
    private val SAMPLING_STEP = 8

    /**
     * Processa il frame se è trascorso l'intervallo minimo.
     * @return true se ha acquisito dati depth, false se saltato o errore
     */
    fun processFrame(frame: Frame): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDepthProcessingMs < DEPTH_INTERVAL_MS) return false
        lastDepthProcessingMs = now

        var depthImage: Image? = null
        return try {
            // In ARCore 1.44 acquireDepthImage16Bits() ritorna android.media.Image
            depthImage = frame.acquireDepthImage16Bits()

            val depthBuffer = depthImage.planes[0].buffer.asShortBuffer()
            val width = depthImage.width
            val height = depthImage.height

            val proj = FloatArray(16)
            frame.camera.getProjectionMatrix(proj, 0, 0.1f, 100f)

            val camMatrix = FloatArray(16)
            frame.camera.pose.toMatrix(camMatrix, 0)

            // fx = proj[0] (P[0][0] column-major), fy = proj[5] (P[1][1])
            val fx = proj[0]
            val fy = proj[5]
            if (fx == 0f || fy == 0f) return false

            var u = 0
            while (u < width) {
                var v = 0
                while (v < height) {
                    val rawShort = depthBuffer.get(v * width + u)
                    val depthMm = rawShort.toInt() and 0xFFFF
                    // Solo profondità valide: 10 cm – 6 m
                    if (depthMm in 100..5999) {
                        val d = depthMm / 1000.0f
                        // NDC coordinates [-1, 1]
                        val ndcX = 2.0f * u / width - 1.0f
                        val ndcY = 1.0f - 2.0f * v / height
                        // Camera space (ARCore: camera guarda -Z)
                        val cx = ndcX / fx * d
                        val cy = ndcY / fy * d
                        val cz = -d
                        // World space via camMatrix (camera→world, column-major)
                        // result[i] = sum_j  camMatrix[j*4+i] * v[j]
                        val wx = camMatrix[0] * cx + camMatrix[4] * cy + camMatrix[8] * cz + camMatrix[12]
                        val wy = camMatrix[1] * cx + camMatrix[5] * cy + camMatrix[9] * cz + camMatrix[13]
                        val wz = camMatrix[2] * cx + camMatrix[6] * cy + camMatrix[10] * cz + camMatrix[14]
                        accumulatedPoints.add(Point3D(wx.toDouble(), wy.toDouble(), wz.toDouble()))
                    }
                    v += SAMPLING_STEP
                }
                u += SAMPLING_STEP
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            depthImage?.close()
        }
    }

    /**
     * Raffina start/end di ogni muro usando i punti depth vicini al piano del muro.
     * Se non ci sono abbastanza punti accumulati, ritorna i wall invariati.
     */
    fun extractWallSegmentsFromDepth(existingWalls: List<Wall>): List<Wall> {
        if (accumulatedPoints.size < 100) return existingWalls

        // Solo punti a "altezza parete" (0.3 m – 3.0 m dal pavimento)
        val verticalPoints = accumulatedPoints.filter { it.y > 0.3 && it.y < 3.0 }
        if (verticalPoints.isEmpty()) return existingWalls

        return existingWalls.map { wall ->
            val nearbyPoints = verticalPoints.filter { distancePointToWall(it, wall) < 0.15 }
            if (nearbyPoints.size > 5) {
                val dir = normalize(
                    wall.endPoint.x - wall.startPoint.x,
                    wall.endPoint.z - wall.startPoint.z
                )
                val projs = nearbyPoints.map { p ->
                    (p.x - wall.startPoint.x) * dir.first +
                    (p.z - wall.startPoint.z) * dir.second
                }
                val minP = projs.min()
                val maxP = projs.max()
                val refined = maxP - minP
                if (refined in 0.3..15.0) {
                    wall.copy(
                        startPoint = Point3D(
                            wall.startPoint.x + minP * dir.first,
                            wall.startPoint.y,
                            wall.startPoint.z + minP * dir.second
                        ),
                        endPoint = Point3D(
                            wall.startPoint.x + maxP * dir.first,
                            wall.startPoint.y,
                            wall.startPoint.z + maxP * dir.second
                        ),
                        length = refined,
                        confidence = (wall.confidence * 0.6 + 0.4).coerceIn(0.0, 1.0)
                    )
                } else wall
            } else wall
        }
    }

    private fun distancePointToWall(point: Point3D, wall: Wall): Double {
        val dx = wall.endPoint.x - wall.startPoint.x
        val dz = wall.endPoint.z - wall.startPoint.z
        val len2 = dx * dx + dz * dz
        if (len2 < 1e-6) return Double.MAX_VALUE
        val t = ((point.x - wall.startPoint.x) * dx + (point.z - wall.startPoint.z) * dz) / len2
        val cx = wall.startPoint.x + t.coerceIn(0.0, 1.0) * dx
        val cz = wall.startPoint.z + t.coerceIn(0.0, 1.0) * dz
        return sqrt((point.x - cx).pow(2) + (point.z - cz).pow(2))
    }

    private fun normalize(x: Double, z: Double): Pair<Double, Double> {
        val len = sqrt(x * x + z * z)
        return if (len < 1e-6) Pair(1.0, 0.0) else Pair(x / len, z / len)
    }

    fun getAccumulatedPointCount() = accumulatedPoints.size

    fun reset() {
        accumulatedPoints.clear()
        lastDepthProcessingMs = 0L
    }
}
