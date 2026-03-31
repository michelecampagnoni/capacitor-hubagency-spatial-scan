package it.hubagency.spatialscan

import kotlin.math.*
import kotlin.math.roundToInt

/**
 * State machine per la cattura del perimetro stanza tap-by-tap.
 *
 * Punti salvati come FloatArray(3) = [X, Y, Z] in world space.
 * Y dei punti floor è invariato (= lastFloorY da ScanningActivity).
 *
 * Flusso guidato 3-step iniziale:
 *  AWAIT_FIRST_FLOOR → tap P0 (base angolo)
 *  AWAIT_HEIGHT      → tap P1 (cima angolo — cattura altezza, NON aggiunge al poligono)
 *  AWAIT_SECOND_FLOOR → tap P2 (base angolo successivo)
 *  FLOOR_ONLY        → tutti i punti successivi, Y mantenuto invariato
 *
 * Snap MORBIDO: SNAP_THRESHOLD_DEG=0 → snap disabilitato, angoli liberi.
 */
class PerimeterCapture {

    enum class State { IDLE, CAPTURING, CLOSED }

    /** Fase di cattura corrente. Derivata da confirmed.size + capturedHeight. */
    enum class CapturePhase { AWAIT_FIRST_FLOOR, AWAIT_HEIGHT, AWAIT_SECOND_FLOOR, FLOOR_ONLY }

    companion object {
        const val SNAP_DEG           = 90f
        val   SNAP_RAD               = Math.toRadians(SNAP_DEG.toDouble()).toFloat()
        const val SNAP_THRESHOLD_DEG = 0f    // snap disabilitato — angolo libero sempre
        val   SNAP_THRESHOLD_RAD     = Math.toRadians(SNAP_THRESHOLD_DEG.toDouble()).toFloat()
        const val LEN_QUANT_M        = 0.05f // quantizzazione conferma (5cm)
        const val MIN_SEG_M          = 0.15f // distanza minima XZ tra punti
        const val MIN_HEIGHT_M       = 1.50f // altezza minima accettabile per P1
    }

    @Volatile var state: State = State.IDLE
        private set

    /** Altezza stanza catturata al tap P1 (relativa al pavimento, in metri). */
    var capturedHeight: Float? = null
        private set

    /** Fase corrente derivata da confirmed.size e capturedHeight. */
    val capturePhase: CapturePhase
        get() = when {
            confirmed.isEmpty()    -> CapturePhase.AWAIT_FIRST_FLOOR
            capturedHeight == null -> CapturePhase.AWAIT_HEIGHT
            confirmed.size == 1    -> CapturePhase.AWAIT_SECOND_FLOOR
            else                   -> CapturePhase.FLOOR_ONLY
        }

    private val confirmed = mutableListOf<FloatArray>()  // vertici [X, Y, Z]

    val pointCount: Int     get() = confirmed.size
    val canClose:   Boolean get() = confirmed.size >= 3 && state == State.CAPTURING
    val canUndo:    Boolean get() = confirmed.isNotEmpty() && state == State.CAPTURING

    // ── API pubblica ──────────────────────────────────────────────────────────

    /**
     * Aggiunge un punto.
     *
     * Comportamento per fase:
     *  AWAIT_FIRST_FLOOR  → aggiunge P0 al poligono, rawY invariato
     *  AWAIT_HEIGHT       → rawY deve essere l'altezza RELATIVA al pavimento
     *                       (calcolata da ScanningActivity come hitY - lastFloorY).
     *                       Imposta capturedHeight, NON aggiunge al poligono.
     *  AWAIT_SECOND_FLOOR / FLOOR_ONLY → XZ snap + distance check, rawY invariato
     *
     * Restituisce true se il punto è accettato.
     */
    fun addPoint(rawX: Float, rawY: Float, rawZ: Float): Boolean {
        if (state == State.CLOSED) return false

        return when (capturePhase) {
            CapturePhase.AWAIT_FIRST_FLOOR -> {
                confirmed.add(floatArrayOf(rawX, rawY, rawZ))
                state = State.CAPTURING
                true
            }
            CapturePhase.AWAIT_HEIGHT -> {
                // rawY è già l'altezza relativa al pavimento
                capturedHeight = rawY.coerceAtLeast(MIN_HEIGHT_M)
                true  // non aggiunge un vertice al poligono
            }
            CapturePhase.AWAIT_SECOND_FLOOR, CapturePhase.FLOOR_ONLY -> {
                val prev    = confirmed.last()
                val snapped = snapFull(rawX, rawY, rawZ, prev)
                val dist    = distXZ(snapped[0], snapped[2], prev[0], prev[2])
                if (dist < MIN_SEG_M) return false
                confirmed.add(snapped)
                true
            }
        }
    }

    /**
     * Punto live snappato per preview in onDrawFrame.
     * Restituisce null durante AWAIT_HEIGHT (preview verticale gestita da ScanningActivity).
     */
    fun livePreview(rawX: Float, rawY: Float, rawZ: Float): FloatArray? {
        if (confirmed.isEmpty() || capturePhase == CapturePhase.AWAIT_HEIGHT) return null
        val prev = confirmed.last()
        return snapAngleOnly(rawX, rawY, rawZ, prev)
    }

    fun close(): Boolean {
        if (!canClose) return false
        state = State.CLOSED
        return true
    }

    /**
     * Undo phase-aware:
     *  - confirmed.size >= 2  → rimuove ultimo punto floor
     *  - confirmed.size == 1 && capturedHeight != null  → annulla cattura altezza (AWAIT_HEIGHT)
     *  - confirmed.size == 1 && capturedHeight == null  → rimuove P0 → IDLE
     */
    fun undo() {
        if (!canUndo) return
        when {
            capturedHeight != null && confirmed.size == 1 -> {
                // Torna ad AWAIT_HEIGHT senza rimuovere P0
                capturedHeight = null
            }
            else -> {
                confirmed.removeLastOrNull()
                if (confirmed.isEmpty()) state = State.IDLE
            }
        }
    }

    fun reset() {
        confirmed.clear()
        capturedHeight = null
        state = State.IDLE
    }

    /** Copia immutabile dei vertici [X, Y, Z]. */
    fun getPolygon(): List<FloatArray> = confirmed.toList()

    /** Lunghezza XZ dell'ultimo segmento confermato. */
    fun lastSegmentLength(): Float? {
        if (confirmed.size < 2) return null
        val a = confirmed[confirmed.size - 2]
        val b = confirmed.last()
        return distXZ(a[0], a[2], b[0], b[2])
    }

    // ── Snap logic ────────────────────────────────────────────────────────────

    private fun snapFull(rawX: Float, rawY: Float, rawZ: Float, prev: FloatArray): FloatArray {
        val angle   = computeSnappedAngle(rawX, rawZ, prev)
        val rawLen  = distXZ(rawX, rawZ, prev[0], prev[2])
        val snapLen = roundTo(rawLen, LEN_QUANT_M).coerceAtLeast(LEN_QUANT_M)
        return floatArrayOf(
            prev[0] + cos(angle) * snapLen,
            rawY,
            prev[2] + sin(angle) * snapLen
        )
    }

    private fun snapAngleOnly(rawX: Float, rawY: Float, rawZ: Float, prev: FloatArray): FloatArray {
        val angle  = computeSnappedAngle(rawX, rawZ, prev)
        val rawLen = distXZ(rawX, rawZ, prev[0], prev[2])
        return floatArrayOf(
            prev[0] + cos(angle) * rawLen,
            rawY,
            prev[2] + sin(angle) * rawLen
        )
    }

    private fun computeSnappedAngle(rawX: Float, rawZ: Float, prev: FloatArray): Float {
        val rawAngle = atan2(rawZ - prev[2], rawX - prev[0])

        val (baseAngle, relAngle) = if (confirmed.size >= 2) {
            val a = confirmed[confirmed.size - 2]
            val b = confirmed.last()
            val prevSeg = atan2(b[2] - a[2], b[0] - a[0])
            prevSeg to normalizeAngle(rawAngle - prevSeg)
        } else {
            0f to rawAngle
        }

        val nearestSnap = roundTo(relAngle, SNAP_RAD)
        val diff        = abs(normalizeAngle(relAngle - nearestSnap))
        return if (diff < SNAP_THRESHOLD_RAD) baseAngle + nearestSnap else rawAngle
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun distXZ(x1: Float, z1: Float, x2: Float, z2: Float) =
        sqrt((x2 - x1).pow(2) + (z2 - z1).pow(2))

    private fun roundTo(value: Float, step: Float): Float =
        (value / step).roundToInt() * step

    private fun normalizeAngle(a: Float): Float {
        var r = a
        val twoPi = (2 * PI).toFloat()
        while (r >  PI.toFloat()) r -= twoPi
        while (r < -PI.toFloat()) r += twoPi
        return r
    }
}
