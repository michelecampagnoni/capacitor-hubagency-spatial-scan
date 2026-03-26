package it.hubagency.spatialscan

import com.google.ar.core.Plane
import com.google.ar.core.TrackingState

class PlaneProcessor {

    private val detectedPlanes = mutableMapOf<Int, PlaneData>()

    fun updatePlanes(updatedPlanes: Collection<Plane>) {
        for (plane in updatedPlanes) {
            val key = System.identityHashCode(plane)
            detectedPlanes[key] = PlaneData(
                id = key,
                type = plane.type,
                trackingState = plane.trackingState,
                extentX = plane.extentX,
                extentZ = plane.extentZ,
                centerPose = plane.centerPose,
                area = plane.extentX * plane.extentZ,
                lastUpdatedMs = System.currentTimeMillis()
            )
        }
    }

    fun getActivePlanes() =
        detectedPlanes.values.filter { it.trackingState == TrackingState.TRACKING }

    fun getActiveWalls() =
        getActivePlanes().filter { it.type == Plane.Type.VERTICAL }

    fun getFloor() =
        detectedPlanes.values
            .filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING }
            .maxByOrNull { it.area }

    fun getTotalActivePlanes() = getActivePlanes().size
    fun getTotalActiveWalls() = getActiveWalls().size

    fun estimateCoverage(expectedRoomArea: Float = 30f): Double {
        val floorArea = getFloor()?.area ?: 0f
        val wallArea = getActiveWalls().sumOf { it.area.toDouble() }
        return ((floorArea.toDouble() + wallArea * 0.3) / expectedRoomArea).coerceIn(0.0, 1.0)
    }

    fun getAllPlaneData(): List<PlaneData> = detectedPlanes.values.toList()

    fun reset() { detectedPlanes.clear() }
}

data class PlaneData(
    val id: Int,
    val type: Plane.Type,
    val trackingState: TrackingState,
    val extentX: Float,
    val extentZ: Float,
    val centerPose: com.google.ar.core.Pose,
    val area: Float,
    val lastUpdatedMs: Long
) {
    fun isWall() = type == Plane.Type.VERTICAL
    fun isFloor() = type == Plane.Type.HORIZONTAL_UPWARD_FACING
}
