package it.hubagency.spatialscan

import android.util.Log
import kotlin.math.*

/**
 * Costruisce il modello stanza finale per buildResult() / GlbExporter / FloorPlanExporter.
 *
 * Pipeline separata dal live tracking:
 *   WallHypothesisTracker (confirmed) → RoomPostProcessor.consolidate() → List<Wall>
 *
 * Il consolidamento è controllato dal flag enableConsolidation (default=true).
 * Con enableConsolidation=false si comporta come la versione precedente (process() base).
 *
 * Metriche del post-processing loggiate su logcat tag SpatialScan:
 *   CONSOLIDATE in=N angClusters=K mergeGroups=M discShort=X clamped=Y final=Z
 */
object FinalRoomModelBuilder {

    private const val TAG = "SpatialScan"

    /**
     * @param confirmedWalls      muri CONFIRMED (o STABLE se < 2 CONFIRMED) dal tracker
     * @param floorY              Y world del pavimento
     * @param wallHeight          altezza stimata pareti (default 2.5m)
     * @param enableConsolidation true = consolidamento aggressivo (produzione)
     *                            false = solo snap+gap (debug/confronto)
     */
    fun build(
        confirmedWalls: List<WallHypothesis>,
        floorY: Float,
        wallHeight: Float,
        enableConsolidation: Boolean = true
    ): List<Wall> {
        if (confirmedWalls.isEmpty()) return emptyList()

        val result = RoomPostProcessor.consolidate(confirmedWalls, enableConsolidation)

        Log.d(TAG, "CONSOLIDATE " +
            "in=${result.inputCount} " +
            "angClusters=${result.angularClusters} " +
            "mergeGroups=${result.mergeGroups} " +
            "discShort=${result.discardedShort} " +
            "clamped=${result.lengthClamped} " +
            "final=${result.finalCount} " +
            "enabled=$enableConsolidation"
        )

        return result.walls.mapIndexed { idx, seg ->
            segmentToWall(seg, idx, floorY, wallHeight)
        }
    }

    private fun segmentToWall(
        seg: Segment2D,
        idx: Int,
        floorY: Float,
        wallHeight: Float
    ): Wall {
        val dx = seg.x2 - seg.x1; val dz = seg.z2 - seg.z1
        val len = sqrt(dx * dx + dz * dz).coerceAtLeast(0.001f)
        val nx = -dz / len
        val nz =  dx / len

        return Wall(
            id         = "consolidated_$idx",
            startPoint = Point3D(seg.x1.toDouble(), floorY.toDouble(), seg.z1.toDouble()),
            endPoint   = Point3D(seg.x2.toDouble(), floorY.toDouble(), seg.z2.toDouble()),
            length     = seg.length.toDouble(),
            height     = wallHeight.toDouble(),
            normal     = Vector3D(nx.toDouble(), 0.0, nz.toDouble()),
            confidence = 0.90  // tutti i muri consolidated vengono da CONFIRMED
        )
    }
}
