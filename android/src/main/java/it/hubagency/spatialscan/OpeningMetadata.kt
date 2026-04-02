package it.hubagency.spatialscan

import org.json.JSONObject

/**
 * Metadati di collegamento per una singola apertura (DOOR / FRENCH_DOOR).
 * Separati da OpeningModel per non toccare il motore di capture.
 *
 * Serializzati nell'export JSON come campi aggiuntivi di walls[i].openings[j].
 * Letti da RoomHistoryManager.buildRecord() e salvati in RoomRecord.openings.
 */
data class OpeningMetadata(
    val openingId:       String,   // == OpeningModel.id
    val wallId:          String,   // == OpeningModel.wallId
    val isInternal:      Boolean,  // true = porta verso altro ambiente
    val linkedRoomId:    String?,  // UUID di RoomRecord.id (null = non ancora risolto)
    val connectionLabel: String?   // nome della stanza collegata (null se non risolto)
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("openingId",   openingId)
        put("wallId",      wallId)
        put("isInternal",  isInternal)
        if (linkedRoomId    != null) put("linkedRoomId",    linkedRoomId)
        if (connectionLabel != null) put("connectionLabel", connectionLabel)
    }

    companion object {
        fun fromJson(obj: JSONObject): OpeningMetadata = OpeningMetadata(
            openingId       = obj.optString("openingId"),
            wallId          = obj.optString("wallId"),
            isInternal      = obj.optBoolean("isInternal", false),
            linkedRoomId    = obj.optString("linkedRoomId").takeIf { it.isNotEmpty() },
            connectionLabel = obj.optString("connectionLabel").takeIf { it.isNotEmpty() }
        )
    }
}
