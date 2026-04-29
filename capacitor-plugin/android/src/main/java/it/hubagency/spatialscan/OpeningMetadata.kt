package it.hubagency.spatialscan

import org.json.JSONObject

/**
 * Stato di collegamento di un'apertura.
 * EXTERNAL  = porta d'ingresso / finestra — non collegata ad altri ambienti.
 * PENDING   = porta interna salvata, in attesa di essere collegata all'ambiente adiacente.
 * LINKED    = collegamento bilaterale confermato (linkedRoomId valorizzato).
 *
 * Retrocompatibilità: record precedenti senza connectionStatus derivano il valore da
 * isInternal + linkedRoomId (vedere fromJson).
 */
enum class ConnectionStatus { EXTERNAL, PENDING, LINKED }

/**
 * Metadati di collegamento per una singola apertura.
 * Separati da OpeningModel per non toccare il motore di capture.
 *
 * Serializzati nell'export JSON come campi aggiuntivi di walls[i].openings[j].
 * Letti da RoomHistoryManager.buildRecord() e salvati in RoomRecord.openings.
 */
data class OpeningMetadata(
    val openingId:        String,
    val wallId:           String,
    val isInternal:       Boolean,
    val linkedRoomId:     String?,
    val connectionLabel:  String?,
    val connectionStatus: ConnectionStatus = when {
        linkedRoomId != null -> ConnectionStatus.LINKED
        isInternal           -> ConnectionStatus.PENDING
        else                 -> ConnectionStatus.EXTERNAL
    }
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("openingId",        openingId)
        put("wallId",           wallId)
        put("isInternal",       isInternal)
        put("connectionStatus", connectionStatus.name)
        if (linkedRoomId    != null) put("linkedRoomId",    linkedRoomId)
        if (connectionLabel != null) put("connectionLabel", connectionLabel)
    }

    companion object {
        fun fromJson(obj: JSONObject): OpeningMetadata {
            val isInternal   = obj.optBoolean("isInternal", false)
            val linkedRoomId = obj.optString("linkedRoomId").takeIf { it.isNotEmpty() }
            val statusStr    = obj.optString("connectionStatus")
            val status = when {
                statusStr.isNotEmpty() -> runCatching { ConnectionStatus.valueOf(statusStr) }
                    .getOrDefault(ConnectionStatus.EXTERNAL)
                linkedRoomId != null -> ConnectionStatus.LINKED
                isInternal           -> ConnectionStatus.PENDING
                else                 -> ConnectionStatus.EXTERNAL
            }
            return OpeningMetadata(
                openingId        = obj.optString("openingId"),
                wallId           = obj.optString("wallId"),
                isInternal       = isInternal,
                linkedRoomId     = linkedRoomId,
                connectionLabel  = obj.optString("connectionLabel").takeIf { it.isNotEmpty() },
                connectionStatus = status
            )
        }
    }
}
