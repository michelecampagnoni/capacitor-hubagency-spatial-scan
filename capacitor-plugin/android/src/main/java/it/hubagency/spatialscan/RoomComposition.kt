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
    val id:           String,
    val roomAId:      String,
    val roomBId:      String,
    val offsetX:      Float,   // metri: traslazione Room B rispetto ad A
    val offsetZ:      Float,
    val rotationRad:  Float,   // radianti: rotazione Room B
    val confirmedAt:  Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id",           id)
        put("roomAId",      roomAId)
        put("roomBId",      roomBId)
        put("offsetX",      offsetX)
        put("offsetZ",      offsetZ)
        put("rotationDeg",  rotationRad * 180.0 / PI)
        put("confirmedAt",  confirmedAt)
    }

    companion object {
        private const val TAG = "RoomComposition"

        fun save(context: Context, comp: RoomComposition) {
            try {
                File(context.filesDir, "hub_composition_${comp.id}.json")
                    .writeText(comp.toJson().toString())
                Log.d(TAG, "saved composition id=${comp.id}")
            } catch (e: Exception) {
                Log.e(TAG, "save failed: ${e.message}", e)
            }
        }

        fun create(roomAId: String, roomBId: String,
                   offsetX: Float, offsetZ: Float, rotationRad: Float): RoomComposition =
            RoomComposition(
                id          = UUID.randomUUID().toString(),
                roomAId     = roomAId,
                roomBId     = roomBId,
                offsetX     = offsetX,
                offsetZ     = offsetZ,
                rotationRad = rotationRad,
                confirmedAt = System.currentTimeMillis()
            )
    }
}
