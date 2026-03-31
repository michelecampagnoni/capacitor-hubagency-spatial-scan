package it.hubagency.spatialscan

import com.google.ar.core.Plane
import com.google.ar.core.TrackingState

/**
 * Stabilizza la stima di floorY usando mediana mobile su campioni stabili.
 *
 * Logica:
 *  1. Raccoglie campioni di floorY da floor plane ARCore (o fallback camera)
 *  2. Lock quando la mediana degli ultimi 30 campioni ha varianza < 0.5cm²
 *     E il tracking è stato TRACKING continuo
 *  3. Una volta bloccato, aggiorna la mediana con peso basso (aggiornamento lento)
 *
 * Fallback: cameraPoseY - 1.2f usato SOLO come filtro altezza point cloud,
 * non per coordinate definitive dei muri.
 */
class FloorPlaneAnchor {

    private val samples      = ArrayDeque<Float>(60)
    private var lockedFloorY : Float? = null
    private var trackingStreak = 0    // frame consecutivi con TRACKING

    val isLocked: Boolean get() = lockedFloorY != null

    /**
     * @param planes        tutti i planes del frame (per trovare il floor)
     * @param cameraY       Y world della camera (fallback)
     * @param isTracking    se il tracking è attivo in questo frame
     * @return              stima stabile di floorY
     */
    fun update(planes: Collection<Plane>, cameraY: Float, isTracking: Boolean): Float {
        if (!isTracking) {
            trackingStreak = 0
            return lockedFloorY ?: (cameraY - 1.2f)
        }
        trackingStreak++

        // Trova il floor plane (il piano orizzontale più grande)
        val floor = planes
            .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
            .filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
            .maxByOrNull { it.extentX * it.extentZ }

        val raw = floor?.centerPose?.ty() ?: (cameraY - 1.2f)

        // Accumula campioni solo se tracking stabile (evita outlier da pause brevi)
        if (trackingStreak > 5) {
            if (samples.size >= 60) samples.removeFirst()
            samples.addLast(raw)
        }

        // Prova a bloccare: serve varianza bassa su 30+ campioni con tracking continuo
        if (lockedFloorY == null && samples.size >= 30 && trackingStreak >= 30) {
            val med = median(samples)
            val variance = samples.map { (it - med) * (it - med) }.average().toFloat()
            if (variance < 0.0005f) {   // std < ~2.2cm
                lockedFloorY = med
            }
        }

        // Aggiornamento molto lento del lock — solo se la mediana diverge > 5cm
        // Evita drift da jitter camera. EMA 0.002 = ~500 frame per adattarsi a 1cm.
        lockedFloorY?.let { locked ->
            val med = median(samples)
            if (kotlin.math.abs(med - locked) > 0.05f) {
                lockedFloorY = locked * 0.998f + med * 0.002f
            }
        }

        return lockedFloorY ?: raw
    }

    fun reset() {
        samples.clear()
        lockedFloorY = null
        trackingStreak = 0
    }

    private fun median(list: Collection<Float>): Float {
        val sorted = list.sorted()
        val n = sorted.size
        return if (n % 2 == 0) (sorted[n / 2 - 1] + sorted[n / 2]) / 2f else sorted[n / 2]
    }
}
