package it.hubagency.spatialscan

import kotlin.math.*

/** Segmento 2D sul piano del pavimento (coordinate XZ world). */
data class Segment2D(
    val x1: Float, val z1: Float,
    val x2: Float, val z2: Float
) {
    val length: Float get() = sqrt((x2 - x1).pow(2) + (z2 - z1).pow(2))
    val cx: Float    get() = (x1 + x2) / 2f
    val cz: Float    get() = (z1 + z2) / 2f
}

/**
 * Ipotesi di muro: aggregazione temporale di osservazioni ARCore grezze.
 *
 * State machine:
 *  CANDIDATE → non mostrato all'utente (troppo poche osservazioni)
 *  STABLE    → mostrato attenuato (abbastanza persistente)
 *  CONFIRMED → mostrato bright (molto persistente, geometria stabile)
 *
 * Promozione (DEBUG B, soglie rilassate):
 *  STABLE    : >= 2 osservazioni E >= 400ms dalla prima vista
 *  CONFIRMED : >= 5 osservazioni E >= 1200ms E lunghezza >= 15cm
 *
 * Espulsione normale: non aggiornato da > 15s (CANDIDATE/STABLE)
 *
 * LockedWallMemory (CONFIRMED):
 *  - 0–10s senza obs    → non scade
 *  - 10–30s senza obs   → confidence decade 0.95→0.70 (visivo dimming)
 *  - 30–60s senza obs   → confidence decade 0.70→0.40 (molto sbiadito)
 *  - >60s senza obs     → rimosso
 *  - Invalidazione immediata solo se >= 3 obs consecutive incompatibili
 *    (angolo > 30° o offset > 60cm rispetto alla geometria attuale)
 */
class WallHypothesis(
    val id: Int,
    initialSegment: Segment2D,
    initialAngle: Float,   // direzione muro nel piano XZ, range [0, PI)
    initialOffset: Float,  // distanza perpendicolare dall'origine alla linea del muro
    val firstSeenMs: Long
) {
    enum class State { CANDIDATE, STABLE, CONFIRMED }

    var segment: Segment2D = initialSegment; private set
    var angleRad: Float    = initialAngle;   private set
    var offsetDist: Float  = initialOffset;  private set
    var state: State       = State.CANDIDATE; private set
    var observationCount: Int = 1;           private set
    var lastSeenMs: Long   = firstSeenMs;    private set

    // LockedWallMemory: timestamp del primo ingresso in CONFIRMED
    private var confirmedSinceMs: Long? = null

    // Confidence in [0, 1]: usata per il rendering (dimming visivo nel decay)
    var confidence: Float = 1.0f; private set

    // Streak di osservazioni incompatibili consecutive (per invalidazione)
    private var incompatibleStreak: Int = 0
    private val INCOMPATIBLE_ANGLE_RAD = Math.toRadians(30.0).toFloat()
    private val INCOMPATIBLE_OFFSET_M  = 0.60f
    private val INVALIDATION_STREAK    = 3

    /** True se deve essere mostrato nell'overlay utente. */
    val isVisibleToUser: Boolean get() = state != State.CANDIDATE
    val isConfirmed:     Boolean get() = state == State.CONFIRMED

    /**
     * Incorpora una nuova osservazione compatibile con questo muro.
     * Aggiorna angolo/offset per media pesata, estende il segmento.
     */
    fun absorb(newSeg: Segment2D, newAngle: Float, newOffset: Float, nowMs: Long) {
        // Verifica incompatibilità prima di assorbire (per invalidazione)
        val angleIncompat = angleDiff(angleRad, newAngle) > INCOMPATIBLE_ANGLE_RAD
        val offsetIncompat = abs(offsetDist - newOffset) > INCOMPATIBLE_OFFSET_M
        if (angleIncompat || offsetIncompat) {
            incompatibleStreak++
            if (incompatibleStreak >= INVALIDATION_STREAK && state == State.CONFIRMED) {
                // Rimuovi il lock: ricade nel regime normale di expiry
                confirmedSinceMs = null
                confidence = 0.0f  // segnala per rimozione immediata
            }
            return
        }
        incompatibleStreak = 0

        val w = observationCount.toFloat()

        // Media angolare per dati assiali (modulo PI) via trucco double-angle
        val ax = cos(2.0 * angleRad) * w + cos(2.0 * newAngle)
        val az = sin(2.0 * angleRad) * w + sin(2.0 * newAngle)
        var merged = atan2(az, ax).toFloat() / 2f
        if (merged < 0f) merged += PI.toFloat()
        angleRad = merged

        offsetDist = (offsetDist * w + newOffset) / (w + 1f)
        segment = extendSegment(newSeg)
        observationCount++
        lastSeenMs = nowMs
        confidence = 1.0f  // refresh completo quando osservata
        refreshState()
    }

    /**
     * Calcola il confidence attuale in base al tempo senza osservazioni.
     * Chiamato ogni frame per aggiornare il dimming visivo.
     * Non modifica lo stato — solo confidence.
     */
    fun updateConfidence(nowMs: Long) {
        if (state != State.CONFIRMED) return
        val silenceMs = nowMs - lastSeenMs
        confidence = when {
            silenceMs <= 10_000L -> 1.0f
            silenceMs <= 30_000L -> {
                // lineare 1.0 → 0.70 in 10–30s
                val t = (silenceMs - 10_000L).toFloat() / 20_000f
                1.0f - t * 0.30f
            }
            silenceMs <= 60_000L -> {
                // lineare 0.70 → 0.40 in 30–60s
                val t = (silenceMs - 30_000L).toFloat() / 30_000f
                0.70f - t * 0.30f
            }
            else -> 0.0f  // scaduto
        }
    }

    fun isExpired(nowMs: Long): Boolean {
        // Muri CONFIRMED usano LockedWallMemory: expiry 60s senza osservazioni
        if (state == State.CONFIRMED || confirmedSinceMs != null) {
            if (confidence <= 0.0f) return true          // invalidato per incompatibilità
            val silenceMs = nowMs - lastSeenMs
            return silenceMs > 60_000L
        }
        // CANDIDATE / STABLE: expiry normale DEBUG B 15s
        return nowMs - lastSeenMs > 15_000L
    }

    /**
     * Merge collineare: assorbe un'altra ipotesi sulla stessa retta.
     * Usato dal tracker dopo ogni frame per unire spezzoni dello stesso muro.
     */
    fun mergeWith(other: WallHypothesis) {
        val wA = observationCount.toFloat()
        val wB = other.observationCount.toFloat()

        // Media angolare pesata (dati assiali, modulo PI)
        val ax = cos(2.0 * angleRad) * wA + cos(2.0 * other.angleRad) * wB
        val az = sin(2.0 * angleRad) * wA + sin(2.0 * other.angleRad) * wB
        var merged = atan2(az, ax).toFloat() / 2f
        if (merged < 0f) merged += PI.toFloat()
        angleRad = merged

        // Offset pesato
        offsetDist = (offsetDist * wA + other.offsetDist * wB) / (wA + wB)

        // Estende il segmento — usa angleRad/offsetDist già aggiornati
        segment = extendSegment(other.segment)

        observationCount += other.observationCount
        lastSeenMs = maxOf(lastSeenMs, other.lastSeenMs)

        // Preserva il lock se almeno uno dei due era CONFIRMED
        if (other.confirmedSinceMs != null && confirmedSinceMs == null) {
            confirmedSinceMs = other.confirmedSinceMs
        }
        confidence = maxOf(confidence, other.confidence)
        incompatibleStreak = 0
        refreshState()
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun refreshState() {
        val durMs = lastSeenMs - firstSeenMs
        // DEBUG B: soglie rilassate (era 10/2500ms/0.35m e 4/1200ms)
        val newState = when {
            observationCount >= 5 && durMs >= 1200L && segment.length >= 0.15f -> State.CONFIRMED
            observationCount >= 2 && durMs >= 400L                             -> State.STABLE
            else                                                                -> State.CANDIDATE
        }
        // Prima transizione a CONFIRMED: attiva LockedWallMemory
        if (newState == State.CONFIRMED && state != State.CONFIRMED && confirmedSinceMs == null) {
            confirmedSinceMs = lastSeenMs
        }
        state = newState
    }

    private fun angleDiff(a1: Float, a2: Float): Float {
        var d = abs(a1 - a2) % PI.toFloat()
        if (d > PI.toFloat() / 2f) d = PI.toFloat() - d
        return d
    }

    /**
     * Estende il segmento corrente per includere newSeg proiettando tutti e 4
     * gli endpoint sull'asse del muro e prendendo min/max.
     */
    private fun extendSegment(newSeg: Segment2D): Segment2D {
        val dx = cos(angleRad.toDouble()).toFloat()
        val dz = sin(angleRad.toDouble()).toFloat()
        val nx = -dz; val nz = dx            // normale (perp all'asse)

        // Punto di riferimento sul muro
        val rx = nx * offsetDist
        val rz = nz * offsetDist

        fun proj(px: Float, pz: Float) = (px - rx) * dx + (pz - rz) * dz

        val ts = floatArrayOf(
            proj(segment.x1, segment.z1), proj(segment.x2, segment.z2),
            proj(newSeg.x1,  newSeg.z1),  proj(newSeg.x2,  newSeg.z2)
        )
        val tMin = ts.min()
        val tMax = ts.max()

        return Segment2D(
            rx + dx * tMin, rz + dz * tMin,
            rx + dx * tMax, rz + dz * tMax
        )
    }
}
