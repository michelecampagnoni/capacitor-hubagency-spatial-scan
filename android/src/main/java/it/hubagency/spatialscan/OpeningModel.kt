package it.hubagency.spatialscan

import kotlin.math.sqrt

enum class OpeningKind {
    DOOR,
    WINDOW,
    FRENCH_DOOR;

    val defaultWidth:  Float get() = when (this) { DOOR -> 0.80f; WINDOW -> 1.20f; FRENCH_DOOR -> 1.20f }
    val defaultBottom: Float get() = when (this) { DOOR -> 0.00f; WINDOW -> 0.90f; FRENCH_DOOR -> 0.00f }
    val defaultHeight: Float get() = when (this) { DOOR -> 2.10f; WINDOW -> 1.20f; FRENCH_DOOR -> 2.20f }
    val label:         String get() = when (this) { DOOR -> "Porta"; WINDOW -> "Finestra"; FRENCH_DOOR -> "Portafinestra" }
}

data class OpeningModel(
    val id:           String,
    val wallId:       String,
    val kind:         OpeningKind,
    var offsetAlongWall: Float,   // meters from wall start (clamped within wall)
    var width:        Float,
    var bottom:       Float,      // meters from floor
    var height:       Float
)

data class WallModel(
    val id:        String,
    val start:     FloatArray,    // [x, y, z]  y=0
    val end:       FloatArray,    // [x, y, z]  y=0
    val thickness: Float = 0.20f,
    val height:    Float,
    val openings:  MutableList<OpeningModel> = mutableListOf()
) {
    /** World length of the wall (XZ). */
    val length: Float get() {
        val dx = end[0] - start[0]; val dz = end[2] - start[2]
        return sqrt(dx * dx + dz * dz)
    }

    /** Unit direction vector (XZ) from start → end. */
    val dirX: Float get() { val l = length; return if (l > 1e-6f) (end[0] - start[0]) / l else 0f }
    val dirZ: Float get() { val l = length; return if (l > 1e-6f) (end[2] - start[2]) / l else 0f }

    /** World position of opening center at floor level (Y=0). */
    fun openingCenter(o: OpeningModel): FloatArray = floatArrayOf(
        start[0] + dirX * (o.offsetAlongWall + o.width / 2f),
        0f,
        start[2] + dirZ * (o.offsetAlongWall + o.width / 2f)
    )

    /** Clamp opening so it stays inside the wall with minMargin from edges. */
    fun clampOpening(o: OpeningModel, minMargin: Float = 0.10f) {
        val maxOffset = (length - o.width - minMargin).coerceAtLeast(minMargin)
        o.offsetAlongWall = o.offsetAlongWall.coerceIn(minMargin, maxOffset)
    }
}

data class RoomModel(
    val floorPolygon: List<FloatArray>,   // [x, y, z], y=0 for all
    val wallHeight:   Float,
    val walls:        List<WallModel>
) {
    companion object {
        /** Build a RoomModel from a closed PerimeterCapture polygon + wall height. */
        fun fromPolygon(polygon: List<FloatArray>, wallHeight: Float): RoomModel {
            val walls = polygon.indices.map { i ->
                val a = polygon[i]
                val b = polygon[(i + 1) % polygon.size]
                WallModel(
                    id     = "w$i",
                    start  = floatArrayOf(a[0], 0f, a[2]),
                    end    = floatArrayOf(b[0], 0f, b[2]),
                    height = wallHeight
                )
            }
            return RoomModel(polygon, wallHeight, walls)
        }
    }
}
