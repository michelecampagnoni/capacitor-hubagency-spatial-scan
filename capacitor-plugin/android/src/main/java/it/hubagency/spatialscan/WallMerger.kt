package it.hubagency.spatialscan

import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import kotlin.math.abs
import kotlin.math.cos

/**
 * Raggruppa i piani ARCore verticali per direzione della normale.
 * Piani coplanari (stessa parete fisica) vengono consolidati → il più grande è il rappresentante.
 * Risultato: al massimo 1 piano per parete distinta — nessuna sovrapposizione visiva.
 */
object WallMerger {

    // Soglia angolare: piani la cui normale differisce di meno di MERGE_ANGLE° vengono uniti
    private const val MERGE_ANGLE_DEG = 22.0
    private val MERGE_COS = cos(Math.toRadians(MERGE_ANGLE_DEG)).toFloat()

    /**
     * @param planes   tutti i piani da session.getAllTrackables(Plane::class.java)
     * @return         un piano per parete fisica distinta (il più esteso per ogni direzione)
     */
    fun mergeWalls(planes: Collection<Plane>): List<Plane> {
        val candidates = planes.filter { p ->
            p.trackingState == TrackingState.TRACKING &&
            p.subsumedBy == null &&
            p.type == Plane.Type.VERTICAL &&
            isTrulyVertical(p) &&
            p.extentX > 0.25f &&   // almeno 25cm larghezza per comparire nell'overlay live
            p.extentZ > 0.4f       // almeno 40cm altezza
        }

        val groups = mutableListOf<MutableList<Plane>>()
        for (plane in candidates) {
            val n = normal(plane)
            val existing = groups.firstOrNull { g ->
                val gn = normal(g[0])
                // Normali antiparallele (stessa parete vista da lati opposti) o parallele
                abs(dot(n, gn)) >= MERGE_COS
            }
            if (existing != null) existing.add(plane) else groups.add(mutableListOf(plane))
        }

        // Rappresentante = piano con l'area maggiore nel gruppo
        return groups.mapNotNull { g -> g.maxByOrNull { it.extentX * it.extentZ } }
    }

    /** Piano orizzontale più grande (pavimento). */
    fun largestFloor(planes: Collection<Plane>): Plane? =
        planes.filter { p ->
            p.trackingState == TrackingState.TRACKING &&
            p.subsumedBy == null &&
            p.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        }.maxByOrNull { it.extentX * it.extentZ }

    // ── helper ──────────────────────────────────────────────────────────────────

    private fun normal(p: Plane): FloatArray =
        p.centerPose.rotateVector(floatArrayOf(0f, 1f, 0f, 0f))

    private fun dot(a: FloatArray, b: FloatArray) =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun isTrulyVertical(p: Plane): Boolean =
        abs(p.centerPose.rotateVector(floatArrayOf(0f, 1f, 0f, 0f))[1]) < 0.35f
}
