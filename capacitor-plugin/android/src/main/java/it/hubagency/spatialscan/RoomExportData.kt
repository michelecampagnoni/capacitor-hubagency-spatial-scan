package it.hubagency.spatialscan

import kotlin.math.abs

/**
 * Export-ready representation of a room derived from RoomModel.
 * Bridges RoomModel (with WallModel + openings) to the export layer.
 * All wall Y coordinates are 0. Openings preserve full geometry.
 */

data class ExportOpening(
    val kind:                 OpeningKind,
    val offsetAlongWall:      Float,
    val width:                Float,
    val bottom:               Float,
    val height:               Float,
    // ── Connection metadata (propagated from OpeningModel) ────────────────────
    val isInternalConnection: Boolean = false,
    val linkedRoomId:         String? = null,
    val linkedOpeningId:      String? = null,
    val connectionLabel:      String? = null
)

data class ExportWall(
    val id:       String,
    val startX:   Float,
    val startZ:   Float,
    val endX:     Float,
    val endZ:     Float,
    val length:   Float,
    val height:   Float,
    val normalX:  Float,
    val normalZ:  Float,
    val dirX:     Float,
    val dirZ:     Float,
    val openings: List<ExportOpening>
)

class RoomExportData(
    val walls:      List<ExportWall>,
    val dimensions: RoomDimensions
) {
    companion object {
        fun fromRoomModel(rm: RoomModel): RoomExportData {
            val exportWalls = rm.walls.map { wm ->
                ExportWall(
                    id       = wm.id,
                    startX   = wm.start[0],
                    startZ   = wm.start[2],
                    endX     = wm.end[0],
                    endZ     = wm.end[2],
                    length   = wm.length,
                    height   = wm.height,
                    normalX  = wm.dirZ,       // outward normal: rotate dir 90° right
                    normalZ  = -wm.dirX,
                    dirX     = wm.dirX,
                    dirZ     = wm.dirZ,
                    openings = wm.openings.map { o ->
                        ExportOpening(
                            kind                 = o.kind,
                            offsetAlongWall      = o.offsetAlongWall,
                            width                = o.width,
                            bottom               = o.bottom,
                            height               = o.height,
                            isInternalConnection = o.isInternalConnection,
                            linkedRoomId         = o.linkedRoomId,
                            linkedOpeningId      = o.linkedOpeningId,
                            connectionLabel      = o.connectionLabel
                        )
                    }
                )
            }
            val dims = computeDimensions(exportWalls, rm.wallHeight)
            return RoomExportData(exportWalls, dims)
        }

        private fun computeDimensions(walls: List<ExportWall>, wallHeight: Float): RoomDimensions {
            if (walls.isEmpty()) return RoomDimensions(0.0, 0.0, wallHeight.toDouble(), 0.0, 0.0)
            val allX = walls.flatMap { listOf(it.startX.toDouble(), it.endX.toDouble()) }
            val allZ = walls.flatMap { listOf(it.startZ.toDouble(), it.endZ.toDouble()) }
            val width     = (allX.max() - allX.min()).coerceAtLeast(0.0)
            val length    = (allZ.max() - allZ.min()).coerceAtLeast(0.0)
            val perimeter = walls.sumOf { it.length.toDouble() } * 0.5  // preserve existing behaviour
            val area      = shoelace(walls)
            return RoomDimensions(width, length, wallHeight.toDouble(), area, perimeter)
        }

        /** Signed area via shoelace, walls form a closed polygon. */
        private fun shoelace(walls: List<ExportWall>): Double {
            var sum = 0.0
            for (w in walls) {
                sum += w.startX.toDouble() * w.endZ.toDouble() -
                       w.endX.toDouble()   * w.startZ.toDouble()
            }
            return abs(sum) / 2.0
        }
    }
}
