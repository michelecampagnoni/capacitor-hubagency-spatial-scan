package it.hubagency.spatialscan

import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import kotlin.math.*

/**
 * Trasforma le osservazioni ARCore grezze in ipotesi muro temporali stabili.
 *
 * DEBUG B — soglie rilassate per diagnosi campo:
 *  extentX >= 0.12m (era 0.30m)
 *  extentZ >= 0.25m (era 0.60m)
 *  normalY < 0.35   (era 0.28)
 *  expiry 15s       (era 5s)
 *  STABLE: obs>=2, dur>=400ms (era 4, 1200ms)
 *  CONFIRMED: obs>=5, dur>=1200ms (era 10, 2500ms)
 */
class WallHypothesisTracker {

    // Soglie di matching
    private val ANGLE_THRESH_RAD  = Math.toRadians(22.0).toFloat()
    private val OFFSET_THRESH_M   = 0.40f
    private val SPATIAL_THRESH_M  = 1.00f

    // Soglie di validazione piano — DEBUG B: permissive
    private val MIN_EXTENT_X  = 0.12f   // era 0.30
    private val MIN_EXTENT_Z  = 0.25f   // era 0.60
    private val MAX_NORMAL_Y  = 0.35f   // era 0.28

    private val hypotheses = mutableListOf<WallHypothesis>()
    private var nextId = 0

    // Metriche debug aggiornate ogni update()
    var lastAcceptedObs:      Int = 0; private set
    var lastRejectedObs:      Int = 0; private set
    var lastMergeCount:       Int = 0; private set
    // Breakdown rejection — per diagnosticare dove si perdono i piani
    var lastRejNotTracking:   Int = 0; private set
    var lastRejSubsumed:      Int = 0; private set
    var lastRejNotVertical:   Int = 0; private set
    var lastRejNormalY:       Int = 0; private set
    var lastRejExtentX:       Int = 0; private set
    var lastRejExtentZ:       Int = 0; private set
    var lastTotalPlanes:      Int = 0; private set

    fun update(planes: Collection<Plane>, nowMs: Long): List<WallHypothesis> {
        var accepted = 0
        var rejNotTracking = 0; var rejSubsumed = 0; var rejNotVertical = 0
        var rejNormalY = 0; var rejExtentX = 0; var rejExtentZ = 0

        lastTotalPlanes = planes.size

        for (plane in planes) {
            // ── Rejection breakdown ──────────────────────────────────────────
            if (plane.trackingState != TrackingState.TRACKING) { rejNotTracking++; continue }
            if (plane.subsumedBy != null)                       { rejSubsumed++;    continue }
            if (plane.type != Plane.Type.VERTICAL)             { rejNotVertical++; continue }
            val n = plane.centerPose.rotateVector(floatArrayOf(0f, 1f, 0f, 0f))
            if (abs(n[1]) > MAX_NORMAL_Y)                      { rejNormalY++;     continue }
            if (plane.extentX < MIN_EXTENT_X)                  { rejExtentX++;     continue }
            if (plane.extentZ < MIN_EXTENT_Z)                  { rejExtentZ++;     continue }

            val (seg, angle, offset) = projectToSegment(plane) ?: continue
            accepted++

            var bestHyp: WallHypothesis? = null
            var bestScore = Float.MAX_VALUE
            for (h in hypotheses) {
                val score = matchScore(h, seg, angle, offset)
                if (score < bestScore) { bestScore = score; bestHyp = h }
            }

            if (bestHyp != null) {
                bestHyp.absorb(seg, angle, offset, nowMs)
            } else {
                hypotheses.add(WallHypothesis(nextId++, seg, angle, offset, nowMs))
            }
        }

        lastAcceptedObs    = accepted
        lastRejectedObs    = rejNotTracking + rejSubsumed + rejNotVertical + rejNormalY + rejExtentX + rejExtentZ
        lastRejNotTracking = rejNotTracking
        lastRejSubsumed    = rejSubsumed
        lastRejNotVertical = rejNotVertical
        lastRejNormalY     = rejNormalY
        lastRejExtentX     = rejExtentX
        lastRejExtentZ     = rejExtentZ

        // LockedWallMemory: aggiorna confidence per decay visivo
        hypotheses.forEach { it.updateConfidence(nowMs) }
        hypotheses.removeAll { it.isExpired(nowMs) }
        mergeCollinear()

        return hypotheses.toList()
    }

    fun reset() { hypotheses.clear(); nextId = 0 }

    fun getAllHypotheses():   List<WallHypothesis> = hypotheses.toList()
    fun getStableWalls():    List<WallHypothesis> = hypotheses.filter { it.isVisibleToUser }
    fun getConfirmedWalls(): List<WallHypothesis> = hypotheses.filter { it.isConfirmed }

    /**
     * V3-B: inietta un seed esterno (point cloud RANSAC) nel tracker.
     * Usa la stessa logica di matching/absorb dell'update() normale.
     * Chiamato da PointCloudWallSeeder ogni 500ms.
     */
    fun feedSeed(seed: WallSeed, nowMs: Long) {
        var bestHyp: WallHypothesis? = null
        var bestScore = Float.MAX_VALUE
        for (h in hypotheses) {
            val score = matchScore(h, seed.segment, seed.angle, seed.offset)
            if (score < bestScore) { bestScore = score; bestHyp = h }
        }
        if (bestHyp != null) {
            bestHyp.absorb(seed.segment, seed.angle, seed.offset, nowMs)
        } else {
            hypotheses.add(WallHypothesis(nextId++, seed.segment, seed.angle, seed.offset, nowMs))
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun projectToSegment(plane: Plane): Triple<Segment2D, Float, Float>? {
        val wn  = plane.centerPose.rotateVector(floatArrayOf(0f, 1f, 0f, 0f))
        val nnx = wn[0]; val nnz = wn[2]
        val nLen = sqrt(nnx * nnx + nnz * nnz)
        if (nLen < 0.1f) return null

        val nx = nnx / nLen; val nz = nnz / nLen
        val dx = -nz;        val dz = nx

        val cx = plane.centerPose.tx()
        val cz = plane.centerPose.tz()
        val offset = cx * nx + cz * nz

        var angle = atan2(dz, dx)
        if (angle < 0f)            angle += PI.toFloat()
        if (angle >= PI.toFloat()) angle -= PI.toFloat()

        val poly = plane.polygon
        if (poly.capacity() >= 4) {
            var tMin = Float.MAX_VALUE; var tMax = -Float.MAX_VALUE
            val pts = poly.capacity() / 2
            for (i in 0 until pts) {
                val lx = poly.get(i * 2); val lz = poly.get(i * 2 + 1)
                val world = plane.centerPose.transformPoint(floatArrayOf(lx, 0f, lz))
                val t = world[0] * dx + world[2] * dz
                if (t < tMin) tMin = t; if (t > tMax) tMax = t
            }
            if (tMax - tMin >= 0.05f) {
                return Triple(
                    Segment2D(dx * tMin + nx * offset, dz * tMin + nz * offset,
                              dx * tMax + nx * offset, dz * tMax + nz * offset),
                    angle, offset
                )
            }
        }

        val halfW = plane.extentX / 2f
        return Triple(
            Segment2D(cx + dx * (-halfW), cz + dz * (-halfW), cx + dx * halfW, cz + dz * halfW),
            angle, offset
        )
    }

    private fun mergeCollinear() {
        var merges = 0; var changed = true
        while (changed) {
            changed = false
            outer@ for (i in 0 until hypotheses.size) {
                for (j in i + 1 until hypotheses.size) {
                    val a = hypotheses[i]; val b = hypotheses[j]
                    if (collinearCompatible(a, b)) {
                        a.mergeWith(b); hypotheses.removeAt(j)
                        merges++; changed = true; break@outer
                    }
                }
            }
        }
        lastMergeCount = merges
    }

    private fun collinearCompatible(a: WallHypothesis, b: WallHypothesis): Boolean {
        if (a.state == WallHypothesis.State.CANDIDATE || b.state == WallHypothesis.State.CANDIDATE) return false
        if (angleDiff(a.angleRad, b.angleRad) > Math.toRadians(12.0).toFloat()) return false
        if (abs(a.offsetDist - b.offsetDist) > 0.30f) return false
        val dx  = cos(a.angleRad.toDouble()).toFloat()
        val dz  = sin(a.angleRad.toDouble()).toFloat()
        val aCt = a.segment.cx * dx + a.segment.cz * dz
        val bCt = b.segment.cx * dx + b.segment.cz * dz
        val gap = abs(aCt - bCt) - a.segment.length / 2f - b.segment.length / 2f
        return gap <= 0.60f
    }

    private fun angleDiff(a1: Float, a2: Float): Float {
        var d = abs(a1 - a2) % PI.toFloat()
        if (d > PI.toFloat() / 2f) d = PI.toFloat() - d
        return d
    }

    private fun matchScore(h: WallHypothesis, seg: Segment2D, angle: Float, offset: Float): Float {
        if (angleDiff(h.angleRad, angle) > ANGLE_THRESH_RAD) return Float.MAX_VALUE
        if (abs(h.offsetDist - offset) > OFFSET_THRESH_M)   return Float.MAX_VALUE
        val dx  = cos(h.angleRad.toDouble()).toFloat()
        val dz  = sin(h.angleRad.toDouble()).toFloat()
        val hCt = h.segment.cx * dx + h.segment.cz * dz
        val sCt = seg.cx        * dx + seg.cz        * dz
        val gap = abs(hCt - sCt) - h.segment.length / 2f - seg.length / 2f
        if (gap > SPATIAL_THRESH_M) return Float.MAX_VALUE
        return angleDiff(h.angleRad, angle) + abs(h.offsetDist - offset) * 0.8f + maxOf(0f, gap) * 0.3f
    }
}
