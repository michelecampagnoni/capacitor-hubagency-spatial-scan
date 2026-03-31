package it.hubagency.spatialscan

import kotlin.math.*

/**
 * Post-processing finale della stanza su muri STABLE/CONFIRMED.
 * Separato dal live tracking — non tocca WallHypothesisTracker.
 *
 * Due entry point:
 *
 *  process()      — snap ortogonale leggero + gap closure (usato dalla preview live)
 *
 *  consolidate()  — pipeline di pruning aggressivo per buildResult():
 *    1. Clustering angolare (bin 15°)
 *    2. Sub-clustering per offset (< 25cm → stesso muro fisico)
 *    3. Merge per gruppo: segmento dominante (max obs) + estensione da tutti gli altri
 *    4. Selezione top-N per forza (obs totali), max MAX_WALLS=8
 *    5. Pruning segmenti < MIN_WALL_LEN=40cm
 *    6. Clamp lunghezze via IQR fence (Q3 × 2.5, cap a 15m)
 *    7. Gap closure
 *
 * Flag enableConsolidation=false bypassa il consolidamento e cade su process() —
 * utile per debug/confronto.
 */
object RoomPostProcessor {

    // ── Costanti process() ────────────────────────────────────────────────────
    private val ORTHO_SNAP_DEG    = Math.toRadians(8.0).toFloat()
    private val CORNER_GAP_MAX_M  = 0.35f
    private val CLUSTER_ANGLE_RAD = Math.toRadians(20.0).toFloat()

    // ── Costanti consolidate() ────────────────────────────────────────────────
    private val CLUSTER_BIN_RAD   = Math.toRadians(15.0).toFloat()  // bin angolare
    private const val MERGE_OFFSET_M  = 0.25f   // stessa parete se offset < 25cm
    private const val MAX_WALLS        = 8
    private const val MIN_WALL_LEN     = 0.40f  // scarta muri < 40cm
    private const val MAX_WALL_ABS     = 15f    // cap assoluto di sicurezza (15m)

    // ── Entry point preview live ──────────────────────────────────────────────

    /**
     * @param walls  muri STABLE/CONFIRMED dal tracker
     * @return       segmenti post-processati: angoli snappati, gap chiusi
     */
    fun process(walls: List<WallHypothesis>): List<Segment2D> {
        if (walls.size < 2) return walls.map { it.segment }
        val snappedSegs = orthoSnap(walls)
        return closeGaps(snappedSegs)
    }

    // ── Entry point buildResult() ─────────────────────────────────────────────

    /**
     * Consolidamento aggressivo per output finale.
     *
     * @param hypotheses         tutti i muri CONFIRMED (o STABLE come fallback) dal tracker
     * @param enableConsolidation flag debug: false = bypassa e chiama process()
     */
    fun consolidate(
        hypotheses: List<WallHypothesis>,
        enableConsolidation: Boolean = true
    ): ConsolidationResult {
        val inputCount = hypotheses.size

        if (!enableConsolidation || inputCount < 2) {
            val segs = process(hypotheses)
            return ConsolidationResult(segs, inputCount, inputCount, inputCount, 0, 0, segs.size)
        }

        // ── 1. Clustering angolare ────────────────────────────────────────────
        // I muri forti guidano la formazione del cluster (sorted by obs desc)
        val sorted = hypotheses.sortedByDescending { it.observationCount }

        data class AngleCluster(val angle: Float, val members: MutableList<WallHypothesis>)
        val angleClusters = mutableListOf<AngleCluster>()

        for (hyp in sorted) {
            val best = angleClusters.minByOrNull { angleDiff(hyp.angleRad, it.angle) }
            if (best != null && angleDiff(hyp.angleRad, best.angle) < CLUSTER_BIN_RAD) {
                best.members.add(hyp)
            } else {
                angleClusters.add(AngleCluster(hyp.angleRad, mutableListOf(hyp)))
            }
        }

        // ── 2. Sub-clustering per offset (= muro fisico distinto) ────────────
        data class MergeGroup(val members: MutableList<WallHypothesis>) {
            val totalObs: Int get() = members.sumOf { it.observationCount }
            val meanOffset: Float get() = members.map { it.offsetDist }.average().toFloat()
        }

        val allGroups = mutableListOf<MergeGroup>()

        for (aCluster in angleClusters) {
            val byObs = aCluster.members.sortedByDescending { it.observationCount }
            val groups = mutableListOf<MergeGroup>()

            for (hyp in byObs) {
                val best = groups.minByOrNull { abs(hyp.offsetDist - it.meanOffset) }
                if (best != null && abs(hyp.offsetDist - best.meanOffset) < MERGE_OFFSET_M) {
                    best.members.add(hyp)
                } else {
                    groups.add(MergeGroup(mutableListOf(hyp)))
                }
            }
            allGroups.addAll(groups)
        }

        val mergeGroupCount = allGroups.size

        // ── 3. Produce un segmento per merge group ────────────────────────────
        // Dominante = max observationCount; gli altri estendono il segmento.
        data class CWall(val seg: Segment2D, val totalObs: Int, val angle: Float)

        val consolidated = allGroups.map { group ->
            val dominant = group.members.maxByOrNull { it.observationCount }!!
            val angle  = dominant.angleRad
            val offset = dominant.offsetDist
            val dx = cos(angle.toDouble()).toFloat()
            val dz = sin(angle.toDouble()).toFloat()
            val nx = -dz; val nz = dx
            val rx = nx * offset; val rz = nz * offset
            fun proj(px: Float, pz: Float) = (px - rx) * dx + (pz - rz) * dz

            var tMin = Float.MAX_VALUE; var tMax = -Float.MAX_VALUE
            for (hyp in group.members) {
                listOf(proj(hyp.segment.x1, hyp.segment.z1),
                       proj(hyp.segment.x2, hyp.segment.z2)).forEach { t ->
                    if (t < tMin) tMin = t; if (t > tMax) tMax = t
                }
            }
            val seg = Segment2D(rx + dx * tMin, rz + dz * tMin, rx + dx * tMax, rz + dz * tMax)
            CWall(seg, group.totalObs, angle)
        }

        // ── 4. Selezione top MAX_WALLS per forza ─────────────────────────────
        val topN = consolidated.sortedByDescending { it.totalObs }.take(MAX_WALLS)

        // ── 5. Pruning: scarta muri < MIN_WALL_LEN ───────────────────────────
        val afterPrune = topN.filter { it.seg.length >= MIN_WALL_LEN }
        val discardedShort = topN.size - afterPrune.size

        // ── 6. Clamp lunghezze via IQR fence ─────────────────────────────────
        // Soglia = Q3 × 2.5, cap assoluto MAX_WALL_ABS.
        // Riduce dall'endpoint più lontano dal centro, preserva la direzione.
        val lengths = afterPrune.map { it.seg.length }.sorted()
        val q3 = if (lengths.size >= 4) lengths[lengths.size * 3 / 4]
                 else lengths.lastOrNull() ?: MAX_WALL_ABS
        val maxAllowed = (q3 * 2.5f).coerceIn(1.0f, MAX_WALL_ABS)

        var clampedCount = 0
        val afterClamp = afterPrune.map { cw ->
            if (cw.seg.length <= maxAllowed) return@map cw
            clampedCount++
            val cx = cw.seg.cx; val cz = cw.seg.cz
            val dx = cos(cw.angle.toDouble()).toFloat()
            val dz = sin(cw.angle.toDouble()).toFloat()
            val half = maxAllowed / 2f
            CWall(
                Segment2D(cx - dx * half, cz - dz * half, cx + dx * half, cz + dz * half),
                cw.totalObs, cw.angle
            )
        }

        // ── 7. Gap closure ────────────────────────────────────────────────────
        val finalSegs = closeGaps(afterClamp.map { it.seg })

        return ConsolidationResult(
            walls          = finalSegs,
            inputCount     = inputCount,
            angularClusters= angleClusters.size,
            mergeGroups    = mergeGroupCount,
            discardedShort = discardedShort,
            lengthClamped  = clampedCount,
            finalCount     = finalSegs.size
        )
    }

    /**
     * Trova le intersezioni (corner) tra ogni coppia di muri non paralleli.
     */
    fun findCorners(segments: List<Segment2D>): List<Pair<Float, Float>> =
        buildList {
            for (i in segments.indices)
                for (j in i + 1 until segments.size)
                    intersect(segments[i], segments[j])?.let { add(it) }
        }

    // ── Snap ortogonale (usato da process()) ──────────────────────────────────

    private fun orthoSnap(walls: List<WallHypothesis>): List<Segment2D> {
        val assignments = IntArray(walls.size) { -1 }
        val clusterAngles = mutableListOf<Float>()

        walls.forEachIndexed { i, w ->
            val best = clusterAngles.indexOfFirst { ca -> angleDiff(w.angleRad, ca) < CLUSTER_ANGLE_RAD }
            if (best >= 0) {
                assignments[i] = best
            } else if (clusterAngles.size < 2) {
                assignments[i] = clusterAngles.size
                clusterAngles.add(w.angleRad)
            } else {
                assignments[i] = clusterAngles.indices.minBy { angleDiff(w.angleRad, clusterAngles[it]) }
            }
        }

        if (clusterAngles.size < 2) return walls.map { it.segment }

        fun clusterMean(idx: Int): Float {
            val members = walls.indices.filter { assignments[it] == idx }
            if (members.isEmpty()) return clusterAngles[idx]
            val totalW = members.sumOf { walls[it].observationCount.toDouble() }.toFloat()
            val ax = members.sumOf { cos(2.0 * walls[it].angleRad) * walls[it].observationCount }.toFloat()
            val az = members.sumOf { sin(2.0 * walls[it].angleRad) * walls[it].observationCount }.toFloat()
            var a = atan2(az / totalW, ax / totalW).toFloat() / 2f
            if (a < 0f) a += PI.toFloat()
            return a
        }

        val a0 = clusterMean(0); val a1 = clusterMean(1)
        var diff = abs(a0 - a1)
        if (diff > PI.toFloat() / 2f) diff = PI.toFloat() - diff
        if (abs(diff - PI.toFloat() / 2f) > ORTHO_SNAP_DEG) return walls.map { it.segment }

        val snappedA0 = a0
        val snappedA1 = normalizeAngle(a0 + PI.toFloat() / 2f)
        return walls.mapIndexed { i, w ->
            val targetAngle = if (assignments[i] == 0) snappedA0 else snappedA1
            rebuildSegment(w.segment, targetAngle, w.offsetDist)
        }
    }

    // ── Gap closure ───────────────────────────────────────────────────────────

    private fun closeGaps(segments: List<Segment2D>): List<Segment2D> {
        val result = segments.toMutableList()
        for (i in result.indices) {
            for (j in result.indices) {
                if (i == j) continue
                val corner = intersect(result[i], result[j]) ?: continue
                val cornX = corner.first; val cornZ = corner.second
                if (!nearSegmentZone(result[i], cornX, cornZ, 2.0f)) continue
                if (!nearSegmentZone(result[j], cornX, cornZ, 2.0f)) continue
                result[i] = extendToCorner(result[i], cornX, cornZ)
                result[j] = extendToCorner(result[j], cornX, cornZ)
            }
        }
        return result
    }

    private fun extendToCorner(seg: Segment2D, cx: Float, cz: Float): Segment2D {
        val d1 = dist(seg.x1, seg.z1, cx, cz)
        val d2 = dist(seg.x2, seg.z2, cx, cz)
        if (d1 > CORNER_GAP_MAX_M && d2 > CORNER_GAP_MAX_M) return seg
        return if (d1 <= d2) Segment2D(cx, cz, seg.x2, seg.z2)
               else          Segment2D(seg.x1, seg.z1, cx, cz)
    }

    private fun nearSegmentZone(seg: Segment2D, px: Float, pz: Float, factor: Float): Boolean {
        val margin = seg.length * factor + CORNER_GAP_MAX_M
        return dist(seg.cx, seg.cz, px, pz) < margin
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    private fun intersect(s1: Segment2D, s2: Segment2D): Pair<Float, Float>? {
        val d1x = s1.x2 - s1.x1; val d1z = s1.z2 - s1.z1
        val d2x = s2.x2 - s2.x1; val d2z = s2.z2 - s2.z1
        val det = d1x * d2z - d1z * d2x
        if (abs(det) < 1e-5f) return null
        val dx = s2.x1 - s1.x1; val dz = s2.z1 - s1.z1
        val t = (dx * d2z - dz * d2x) / det
        return Pair(s1.x1 + t * d1x, s1.z1 + t * d1z)
    }

    private fun rebuildSegment(seg: Segment2D, newAngle: Float, offset: Float): Segment2D {
        val dx = cos(newAngle.toDouble()).toFloat()
        val dz = sin(newAngle.toDouble()).toFloat()
        val nx = -dz; val nz = dx
        val rx = nx * offset; val rz = nz * offset
        fun proj(px: Float, pz: Float) = (px - rx) * dx + (pz - rz) * dz
        val t1 = proj(seg.x1, seg.z1); val t2 = proj(seg.x2, seg.z2)
        val tMin = minOf(t1, t2); val tMax = maxOf(t1, t2)
        return Segment2D(rx + dx * tMin, rz + dz * tMin, rx + dx * tMax, rz + dz * tMax)
    }

    private fun angleDiff(a1: Float, a2: Float): Float {
        var d = abs(a1 - a2) % PI.toFloat()
        if (d > PI.toFloat() / 2f) d = PI.toFloat() - d
        return d
    }

    private fun normalizeAngle(a: Float): Float {
        var r = a % PI.toFloat(); if (r < 0f) r += PI.toFloat(); return r
    }

    private fun dist(x1: Float, z1: Float, x2: Float, z2: Float) =
        sqrt((x2 - x1).pow(2) + (z2 - z1).pow(2))
}

/**
 * Risultato del consolidamento finale — metriche complete per logcat e smoke test.
 */
data class ConsolidationResult(
    val walls: List<Segment2D>,
    val inputCount: Int,         // muri in ingresso
    val angularClusters: Int,    // direzioni angolari distinte trovate
    val mergeGroups: Int,        // muri fisici distinti prima del pruning
    val discardedShort: Int,     // muri scartati per lunghezza < 40cm
    val lengthClamped: Int,      // muri accorciati dall'IQR fence
    val finalCount: Int          // muri nell'output finale
)
