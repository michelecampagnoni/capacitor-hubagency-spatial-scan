package it.hubagency.spatialscan

/**
 * Represents a single persisted room scan entry.
 * Stored in room_history.json via RoomHistoryManager.
 */
data class RoomRecord(
    val id:            String,
    val name:          String,
    val timestamp:     Long,        // epoch ms (creation time)
    val area:          Double,      // m²
    val height:        Double,      // m
    val openingCount:  Int,
    val floorPlanPath: String?,     // absolute cache path, may be null if export failed
    val glbPath:       String?      // absolute cache path, may be null if export failed
)
