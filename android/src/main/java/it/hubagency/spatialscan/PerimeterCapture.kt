package it.hubagency.spatialscan

import kotlin.math.*
import kotlin.math.roundToInt

/**
 * State machine per la cattura del perimetro stanza tap-by-tap.
 *
 * Input:  punti XZ sul pavimento (da raycast reticle → floor).
 * Output: poligono chiuso via getPolygon() → List<FloatArray(x, z)>.
 *
 * Snap MVP:
 *  - Angolo: multiplo di 90° (relativo al segmento precedente; primo lato assoluto)
 *  - Lunghezza: 5cm alla conferma (non in live preview, per non essere grossolano)
 *
 * Thread safety: tutti i metodi che mutano lo stato (addPoint, close, undo, reset)
 * devono essere chiamati dallo stesso thread (GL thread via queueEvent).
 */
class PerimeterCapture {

    enum class State { IDLE, CAPTURING, CLOSED }

    companion object {
        const val SNAP_DEG    = 90f                                        // MVP: solo 90°
        val   SNAP_RAD        = Math.toRadians(SNAP_DEG.toDouble()).toFloat()
        const val LEN_QUANT_M = 0.05f   // quantizzazione conferma (5cm)
        const val MIN_SEG_M   = 0.08f   // distanza minima tra punti (evita doppio tap)
    }

    @Volatile var state: State = State.IDLE
        private set

    private val confirmed = mutableListOf<FloatArray>()   // vertici XZ confermati

    val pointCount: Int   get() = confirmed.size
    val canClose:   Boolean get() = confirmed.size >= 3 && state == State.CAPTURING
    val canUndo:    Boolean get() = confirmed.isNotEmpty() && state == State.CAPTURING

    // ── API pubblica ──────────────────────────────────────────────────────────

    /**
     * Aggiunge un punto confermato. Applica snap 90° + quantizzazione 5cm.
     * Restituisce true se il punto è stato accettato.
     */
    fun addPoint(rawX: Float, rawZ: Float): Boolean {
        if (state == State.CLOSED) return false

        if (confirmed.isEmpty()) {
            // Primo punto: nessuno snap, solo transizione di stato
            confirmed.add(floatArrayOf(rawX, rawZ))
            state = State.CAPTURING
            return true
        }

        val prev     = confirmed.last()
        val snapped  = snapFull(rawX, rawZ, prev)
        val dist     = dist2D(snapped[0], snapped[1], prev[0], prev[1])
        if (dist < MIN_SEG_M) return false   // troppo vicino, ignora

        confirmed.add(snapped)
        return true
    }

    /**
     * Punto live snappato in angolo (non in lunghezza) per la preview in onDrawFrame.
     * Nessuna mutazione di stato.
     */
    fun livePreview(rawX: Float, rawZ: Float): FloatArray? {
        if (confirmed.isEmpty()) return null
        val prev = confirmed.last()
        return snapAngleOnly(rawX, rawZ, prev)
    }

    /** Chiude il poligono (aggiunge segmento P_n → P_0 logicamente). */
    fun close(): Boolean {
        if (!canClose) return false
        state = State.CLOSED
        return true
    }

    fun undo() {
        if (!canUndo) return
        confirmed.removeLastOrNull()
        if (confirmed.isEmpty()) state = State.IDLE
    }

    fun reset() {
        confirmed.clear()
        state = State.IDLE
    }

    /** Vertici XZ confermati in ordine. Copia immutabile. */
    fun getPolygon(): List<FloatArray> = confirmed.toList()

    /** Lunghezza dell'ultimo segmento confermato. Null se < 2 punti. */
    fun lastSegmentLength(): Float? {
        if (confirmed.size < 2) return null
        val a = confirmed[confirmed.size - 2]
        val b = confirmed.last()
        return dist2D(a[0], a[1], b[0], b[1])
    }

    // ── Snap logic ────────────────────────────────────────────────────────────

    /** Snap angolo 90° + quantizzazione lunghezza 5cm. */
    private fun snapFull(rawX: Float, rawZ: Float, prev: FloatArray): FloatArray {
        val angle  = computeSnappedAngle(rawX, rawZ, prev)
        val rawLen = dist2D(rawX, rawZ, prev[0], prev[1])
        val snapLen = roundTo(rawLen, LEN_QUANT_M).coerceAtLeast(LEN_QUANT_M)
        return floatArrayOf(
            prev[0] + cos(angle) * snapLen,
            prev[1] + sin(angle) * snapLen
        )
    }

    /** Snap solo angolo, lunghezza raw — per preview live. */
    private fun snapAngleOnly(rawX: Float, rawZ: Float, prev: FloatArray): FloatArray {
        val angle  = computeSnappedAngle(rawX, rawZ, prev)
        val rawLen = dist2D(rawX, rawZ, prev[0], prev[1])
        return floatArrayOf(
            prev[0] + cos(angle) * rawLen,
            prev[1] + sin(angle) * rawLen
        )
    }

    private fun computeSnappedAngle(rawX: Float, rawZ: Float, prev: FloatArray): Float {
        val rawAngle = atan2(rawZ - prev[1], rawX - prev[0])
        return if (confirmed.size >= 2) {
            // Angolo relativo al segmento precedente
            val a = confirmed[confirmed.size - 2]
            val b = confirmed.last()
            val prevSegAngle = atan2(b[1] - a[1], b[0] - a[0])
            val rel         = normalizeAngle(rawAngle - prevSegAngle)
            val snappedRel  = roundTo(rel, SNAP_RAD)
            prevSegAngle + snappedRel
        } else {
            // Primo lato: snap assoluto
            roundTo(rawAngle, SNAP_RAD)
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun dist2D(x1: Float, z1: Float, x2: Float, z2: Float) =
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
