package it.hubagency.spatialscan

import android.util.Log
import kotlin.math.*

/**
 * Post-scan conservative geometry rectification.
 *
 * Snaps wall angles to the nearest 90° or 45° multiple (relative to the dominant axis)
 * only when the deviation is ≤ SNAP_HARD_DEG (5°). Larger deviations are treated
 * as genuine geometry and left unchanged.
 *
 * This is NOT "force everything to 90°/45°". It is a targeted correction of measurement
 * noise in rooms that are structurally orthogonal or have 45° diagonal walls.
 *
 * Algorithm:
 *  1. Find dominant axis = direction of the longest wall (most reliable measurement)
 *  2. Derive eight candidate snap directions: dominantAxis + k * 45° (k = 0..7)
 *  3. For each edge:
 *       deviation = min angular distance from any candidate snap direction
 *       if deviation ≤ SNAP_HARD_RAD  → snap (measurement noise)
 *       if deviation  > SNAP_HARD_RAD → leave unchanged (genuine irregularity)
 *       if length     < MIN_EDGE_M    → skip (short edges are less reliable)
 *  4. Reconstruct polygon vertices greedily from v[0] using snapped angles + original lengths
 *  5. Distribute closure error linearly across vertices v[0]..v[n-1]
 *
 * Inputs/outputs: world-space FloatArray([X, Y, Z]) — Y component preserved unchanged.
 */
object RoomRectifier {

    /** Auto-snap threshold (degrees). Below → measurement noise. Above → genuine geometry. */
    const val SNAP_HARD_DEG = 5f

    /** Minimum edge length (metres) eligible for snapping. Short edges have high angular noise. */
    const val MIN_EDGE_M = 0.20f

    data class Result(
        val polygon:      List<FloatArray>,  // [X, Y, Z] — possibly rectified
        val changed:      Boolean,
        val snappedCount: Int,               // number of edges that were actually moved
        val totalEdges:   Int
    )

    fun rectify(polygon: List<FloatArray>): Result {
        val n = polygon.size
        if (n < 3) return Result(polygon, false, 0, 0)

        // ── 1. Edge geometry ──────────────────────────────────────────────────
        val angles  = FloatArray(n)
        val lengths = FloatArray(n)
        for (i in 0 until n) {
            val a = polygon[i]; val b = polygon[(i + 1) % n]
            val dx = b[0] - a[0]; val dz = b[2] - a[2]
            lengths[i] = sqrt(dx * dx + dz * dz)
            angles[i]  = atan2(dz, dx)
        }

        // ── 2. Dominant axis from longest edge ────────────────────────────────
        val longestIdx    = lengths.indices.maxByOrNull { lengths[it] } ?: 0
        val dominantAxis  = angles[longestIdx]

        Log.d("RoomRectifier", "dominant axis: edge[$longestIdx] len=${
            "%.2f".format(lengths[longestIdx])}m angle=${"%.1f".format(
            Math.toDegrees(dominantAxis.toDouble()))}°")

        // ── 3. Snap eligible edges ────────────────────────────────────────────
        val snapRad       = Math.toRadians(SNAP_HARD_DEG.toDouble()).toFloat()
        val snappedAngles = angles.copyOf()
        var snappedCount  = 0

        for (i in 0 until n) {
            val nearest = nearestAxisAngle(angles[i], dominantAxis)
            val dev     = angularDeviation(angles[i], nearest)
            val devDeg  = Math.toDegrees(dev.toDouble())
            val action  = when {
                lengths[i] < MIN_EDGE_M -> "SKIP(short)"
                dev <= snapRad          -> "SNAP"
                else                    -> "KEEP"
            }
            Log.d("RoomRectifier", "  edge[$i] len=${"%.2f".format(lengths[i])}m " +
                "raw=${"%.1f".format(Math.toDegrees(angles[i].toDouble()))}° " +
                "nearest=${"%.1f".format(Math.toDegrees(nearest.toDouble()))}° " +
                "dev=${"%.2f".format(devDeg)}° → $action")
            if (action == "SNAP") {
                snappedAngles[i] = nearest
                if (dev > 1e-5f) snappedCount++
            }
        }

        Log.d("RoomRectifier", "snapped $snappedCount / $n edges. " +
            "closure err after reconstruct will be logged below.")
        if (snappedCount == 0) return Result(polygon, false, 0, n)

        // ── 4. Reconstruct vertices greedily from v[0] ────────────────────────
        val newX = FloatArray(n); val newZ = FloatArray(n)
        newX[0] = polygon[0][0]; newZ[0] = polygon[0][2]
        for (i in 0 until n - 1) {
            newX[i + 1] = newX[i] + cos(snappedAngles[i]) * lengths[i]
            newZ[i + 1] = newZ[i] + sin(snappedAngles[i]) * lengths[i]
        }

        // ── 5. Closure error distribution ─────────────────────────────────────
        // Where would the last edge land relative to v[0]?
        val closeX = newX[n - 1] + cos(snappedAngles[n - 1]) * lengths[n - 1]
        val closeZ = newZ[n - 1] + sin(snappedAngles[n - 1]) * lengths[n - 1]
        val errX   = polygon[0][0] - closeX
        val errZ   = polygon[0][2] - closeZ
        // Distribute linearly: v[0] gets 0, v[n-1] gets errX*(n-1)/n
        // This ensures the polygon closes (last edge direction self-corrects via v[n-1]→v[0])
        for (i in 0 until n) {
            val t   = i.toFloat() / n
            newX[i] += errX * t
            newZ[i] += errZ * t
        }

        Log.d("RoomRectifier", "closure error: errX=${"%.4f".format(errX)}m errZ=${"%.4f".format(errZ)}m")

        val rectified = (0 until n).map { i ->
            floatArrayOf(newX[i], polygon[i][1], newZ[i])
        }
        return Result(rectified, true, snappedCount, n)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the nearest snap angle: one of (dominantAxis + k * π/4) for k = 0..7,
     * normalized to [-π, π]. The eight candidates cover 90° and 45° multiples
     * relative to the dominant axis.
     */
    private fun nearestAxisAngle(rawAngle: Float, dominantAxis: Float): Float {
        val quarterPi  = (PI / 4).toFloat()
        val twoPi      = (2 * PI).toFloat()
        val piF        = PI.toFloat()
        val candidates = Array(8) { k ->
            var a = dominantAxis + k * quarterPi
            while (a >  piF) a -= twoPi
            while (a < -piF) a += twoPi
            a
        }
        return candidates.minByOrNull { angularDeviation(rawAngle, it) } ?: rawAngle
    }

    /** Minimum angular distance between two angles ∈ [0, π]. */
    private fun angularDeviation(a: Float, b: Float): Float {
        var d = abs(a - b)
        if (d > PI.toFloat()) d = (2 * PI).toFloat() - d
        return d
    }
}
