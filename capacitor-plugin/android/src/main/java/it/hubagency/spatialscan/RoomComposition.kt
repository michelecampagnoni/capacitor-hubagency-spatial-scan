package it.hubagency.spatialscan

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.PI

/**
 * Risultato confermato di una composizione planimetrica multi-stanza.
 * Salvato in filesDir/hub_composition_{id}.json.
 */
data class RoomComposition(
    val id:                    String,
    val roomAId:               String,
    val roomBId:               String,
    val offsetX:               Float,
    val offsetZ:               Float,
    val rotationRad:           Float,
    val confirmedAt:           Long,
    val combinedFloorPlanPath: String?  = null,  // PNG planimetria combinata
    val isLocked:              Boolean  = false   // true = confermata, editing bloccato
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id",           id)
        put("roomAId",      roomAId)
        put("roomBId",      roomBId)
        put("offsetX",      offsetX)
        put("offsetZ",      offsetZ)
        put("rotationDeg",  rotationRad * 180.0 / PI)
        put("confirmedAt",  confirmedAt)
        put("isLocked",     isLocked)
        if (combinedFloorPlanPath != null) put("combinedFloorPlanPath", combinedFloorPlanPath)
    }

    companion object {
        private const val TAG = "RoomComposition"

        fun save(context: Context, comp: RoomComposition) {
            try {
                File(context.filesDir, "hub_composition_${comp.id}.json")
                    .writeText(comp.toJson().toString())
                Log.d(TAG, "saved composition id=${comp.id} locked=${comp.isLocked}")
            } catch (e: Exception) {
                Log.e(TAG, "save failed: ${e.message}", e)
            }
        }

        fun create(
            roomAId: String, roomBId: String,
            offsetX: Float, offsetZ: Float, rotationRad: Float,
            combinedFloorPlanPath: String? = null,
            isLocked: Boolean = false
        ): RoomComposition = RoomComposition(
            id                    = UUID.randomUUID().toString(),
            roomAId               = roomAId,
            roomBId               = roomBId,
            offsetX               = offsetX,
            offsetZ               = offsetZ,
            rotationRad           = rotationRad,
            confirmedAt           = System.currentTimeMillis(),
            combinedFloorPlanPath = combinedFloorPlanPath,
            isLocked              = isLocked
        )
    }
}
