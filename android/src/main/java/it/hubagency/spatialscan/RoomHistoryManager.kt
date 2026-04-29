package it.hubagency.spatialscan

import android.content.Context
import android.util.Log
import com.getcapacitor.JSObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persistenza locale dello storico stanze.
 *
 * Formato: filesDir/hub_rooms.json — array JSON di RoomRecord.
 * Nessuna dipendenza esterna: usa org.json (incluso in Android SDK).
 */
object RoomHistoryManager {

    private const val TAG      = "RoomHistoryManager"
    private const val FILENAME = "hub_rooms.json"

    // ── API pubblica ──────────────────────────────────────────────────────────

    /**
     * Salva una nuova stanza estraendo i metadati dal JSObject restituito da buildResult().
     * Se il salvataggio fallisce, logga l'errore senza propagare eccezioni.
     */
    fun save(context: Context, result: JSObject, name: String): RoomRecord? {
        return try {
            val record = buildRecord(result, name)
            val list   = loadRaw(context).toMutableList()
            list.add(0, record)          // più recente in cima
            writeAll(context, list)
            saveRoomData(context, record.id, result)
            Log.d(TAG, "saved room '${record.name}' id=${record.id} area=${record.area}m²")
            record
        } catch (e: Exception) {
            Log.e(TAG, "save failed: ${e.message}", e)
            null
        }
    }

    /** Restituisce tutti i record in ordine dal più recente. */
    fun loadAll(context: Context): List<RoomRecord> {
        return try {
            loadRaw(context)
        } catch (e: Exception) {
            Log.e(TAG, "loadAll failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Aggiorna bilateralmente il metadata di un'apertura in una stanza già salvata.
     * Usato quando la stanza adiacente collega una porta precedentemente PENDING.
     * Aggiorna sia hub_rooms.json che hub_room_{id}.json.
     */
    fun updateOpeningMetadata(
        context:        Context,
        roomId:         String,
        openingId:      String,
        linkedRoomId:   String,
        linkedRoomName: String
    ) {
        try {
            // 1. Aggiorna hub_room_{id}.json (dati geometrici completi)
            val roomData = loadRoomData(context, roomId)
            if (roomData != null) {
                val walls = roomData.optJSONArray("walls")
                if (walls != null) {
                    for (i in 0 until walls.length()) {
                        val wall = walls.optJSONObject(i) ?: continue
                        val ops  = wall.optJSONArray("openings") ?: continue
                        for (j in 0 until ops.length()) {
                            val op = ops.optJSONObject(j) ?: continue
                            if (op.optString("id") == openingId) {
                                op.put("isInternal",       true)
                                op.put("connectionStatus", ConnectionStatus.LINKED.name)
                                op.put("linkedRoomId",     linkedRoomId)
                                op.put("connectionLabel",  linkedRoomName)
                            }
                        }
                    }
                }
                File(context.filesDir, "hub_room_$roomId.json").writeText(roomData.toString())
            }

            // 2. Aggiorna hub_rooms.json (indice stanze)
            val list = loadRaw(context).toMutableList()
            val idx  = list.indexOfFirst { it.id == roomId }
            if (idx >= 0) {
                val record      = list[idx]
                val newOpenings = record.openings.toMutableList()
                val existingIdx = newOpenings.indexOfFirst { it.openingId == openingId }
                val wallId      = newOpenings.getOrNull(existingIdx)?.wallId ?: ""
                val newMeta = OpeningMetadata(
                    openingId        = openingId,
                    wallId           = wallId,
                    isInternal       = true,
                    linkedRoomId     = linkedRoomId,
                    connectionLabel  = linkedRoomName,
                    connectionStatus = ConnectionStatus.LINKED
                )
                if (existingIdx >= 0) newOpenings[existingIdx] = newMeta
                else newOpenings.add(newMeta)
                list[idx] = record.copy(openings = newOpenings)
                writeAll(context, list)
            }
            Log.d(TAG, "updateOpeningMetadata roomId=$roomId openingId=$openingId linkedTo=$linkedRoomId")
        } catch (e: Exception) {
            Log.e(TAG, "updateOpeningMetadata failed: ${e.message}", e)
        }
    }

    /** Rimuove un record per id e cancella il file geometrico associato. No-op se non trovato. */
    fun delete(context: Context, id: String) {
        try {
            val list = loadRaw(context).filter { it.id != id }
            writeAll(context, list)
            File(context.filesDir, "hub_room_$id.json").delete()
            Log.d(TAG, "deleted room id=$id")
        } catch (e: Exception) {
            Log.e(TAG, "delete failed: ${e.message}", e)
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun buildRecord(result: JSObject, name: String): RoomRecord {
        val dims     = result.getJSObject("roomDimensions")
        val walls    = result.getJSONArray("walls")
        val floor    = result.getJSObject("floor")

        // Conta aperture e raccoglie metadati collegamento
        var openingCount = 0
        val openingMetas = mutableListOf<OpeningMetadata>()
        if (walls != null) {
            for (i in 0 until walls.length()) {
                val wall   = walls.optJSONObject(i) ?: continue
                val wallId = wall.optString("id")
                val ops    = wall.optJSONArray("openings") ?: continue
                openingCount += ops.length()
                for (j in 0 until ops.length()) {
                    val op = ops.optJSONObject(j) ?: continue
                    if (op.has("isInternal")) {
                        openingMetas.add(OpeningMetadata(
                            openingId       = op.optString("id"),
                            wallId          = wallId,
                            isInternal      = op.optBoolean("isInternal", false),
                            linkedRoomId    = op.optString("linkedRoomId").takeIf { it.isNotEmpty() },
                            connectionLabel = op.optString("connectionLabel").takeIf { it.isNotEmpty() }
                        ))
                    }
                }
            }
        }

        return RoomRecord(
            id            = UUID.randomUUID().toString(),
            name          = name.trim().ifEmpty { "Stanza" },
            timestamp     = System.currentTimeMillis(),
            area          = dims?.getDouble("area")   ?: floor?.getDouble("area") ?: 0.0,
            height        = dims?.getDouble("height") ?: 0.0,
            wallCount     = walls?.length()           ?: 0,
            openingCount  = openingCount,
            floorPlanPath = result.getString("floorPlanPath"),
            glbPath       = result.getString("glbPath"),
            openings      = openingMetas
        )
    }

    private fun file(context: Context): File =
        File(context.filesDir, FILENAME)

    private fun loadRaw(context: Context): List<RoomRecord> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val json = f.readText()
        val arr  = JSONArray(json)
        return (0 until arr.length()).map { RoomRecord.fromJson(arr.getJSONObject(it)) }
    }

    private fun writeAll(context: Context, records: List<RoomRecord>) {
        val arr = JSONArray()
        records.forEach { arr.put(it.toJson()) }
        file(context).writeText(arr.toString())
    }

    private fun saveRoomData(context: Context, id: String, result: JSObject) {
        try {
            File(context.filesDir, "hub_room_$id.json").writeText(result.toString())
        } catch (e: Exception) {
            Log.e(TAG, "saveRoomData failed id=$id: ${e.message}", e)
        }
    }

    fun loadRoomData(context: Context, id: String): JSONObject? {
        return try {
            val f = File(context.filesDir, "hub_room_$id.json")
            if (!f.exists()) return null
            JSONObject(f.readText())
        } catch (e: Exception) {
            Log.e(TAG, "loadRoomData failed id=$id: ${e.message}", e)
            null
        }
    }
}
