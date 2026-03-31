package it.hubagency.spatialscan

import com.google.ar.core.Plane
import com.google.ar.core.TrackingState

/**
 * Wrappa la collezione di piani ARCore.
 *
 * Approccio corretto: usa session.getAllTrackables(Plane::class.java) ogni frame
 * invece di accumulare da getUpdatedTrackables(). ARCore deduplica i piani da sola:
 * getAllTrackables() restituisce sempre lo stato corrente senza duplicati.
 * Piani subsumed (assorbiti da piani più grandi) vengono esclusi automaticamente.
 */
class PlaneProcessor {

    private var currentPlanes: Collection<Plane> = emptyList()

    /**
     * Sostituisce l'intera collezione con il risultato di
     * session.getAllTrackables(Plane::class.java).
     * Chiamare una volta per frame sul GL thread.
     */
    fun refreshPlanes(planes: Collection<Plane>) {
        // Esclude piani subsumed (ARCore li unisce con piani più grandi)
        currentPlanes = planes.filter { it.subsumedBy == null }
    }

    /** Piani attivamente tracciati (non subsumed, non stopped). */
    fun getActivePlanes(): List<Plane> =
        currentPlanes.filter { it.trackingState == TrackingState.TRACKING }

    fun getActiveWalls(): List<Plane> =
        getActivePlanes().filter { it.type == Plane.Type.VERTICAL }

    fun getFloor(): Plane? =
        getActivePlanes()
            .filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
            .maxByOrNull { it.extentX * it.extentZ }

    fun getTotalActivePlanes(): Int = getActivePlanes().size
    fun getTotalActiveWalls(): Int  = getActiveWalls().size

    fun estimateCoverage(expectedRoomArea: Float = 30f): Double {
        val floorArea = getFloor()?.let { it.extentX * it.extentZ } ?: 0f
        val wallArea  = getActiveWalls().sumOf { (it.extentX * it.extentZ).toDouble() }
        return ((floorArea.toDouble() + wallArea * 0.3) / expectedRoomArea).coerceIn(0.0, 1.0)
    }

    /** Converte in PlaneData (snapshot) per WallDetector e DepthProcessor. */
    fun getAllPlaneData(): List<PlaneData> = currentPlanes.map { it.toData() }

    /** Lista di PlaneData solo per le pareti attive (per WallDetector). */
    fun getActiveWallData(): List<PlaneData> = getActiveWalls().map { it.toData() }

    fun reset() { currentPlanes = emptyList() }

    private fun Plane.toData() = PlaneData(
        id             = System.identityHashCode(this),
        type           = type,
        trackingState  = trackingState,
        extentX        = extentX,
        extentZ        = extentZ,
        centerPose     = centerPose,
        area           = extentX * extentZ,
        lastUpdatedMs  = System.currentTimeMillis()
    )
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
    fun isWall()  = type == Plane.Type.VERTICAL
    fun isFloor() = type == Plane.Type.HORIZONTAL_UPWARD_FACING
}
