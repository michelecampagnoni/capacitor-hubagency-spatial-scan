package it.hubagency.spatialscan

/**
 * Carries link data from a completed room scan to the next one.
 *
 * Stored in ScanningActivity.pendingNextRoomSeed (companion object).
 * Consumed by spawnOpening() on the first matching opening placed in the new room.
 *
 * - fromRoomId / fromRoomName: identity of the source room
 * - fromOpeningId: id of the specific opening chosen as connection point (null = no specific opening)
 * - suggested*: dimensions to pre-fill on the mirrored opening (null = use kind defaults)
 */
data class NextRoomSeed(
    val fromRoomId:      String,
    val fromRoomName:    String,
    val fromOpeningId:   String?,
    val suggestedKind:   OpeningKind?,   // null = no pre-fill
    val suggestedWidth:  Float?,
    val suggestedHeight: Float?,
    val suggestedBottom: Float?
)
