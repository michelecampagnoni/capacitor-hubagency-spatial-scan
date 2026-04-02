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

    /** Rimuove un record per id. No-op se non trovato. */
    fun delete(context: Context, id: String) {
        try {
            val list = loadRaw(context).filter { it.id != id }
            writeAll(context, list)
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

        // Conta aperture su tutti i muri
        var openings = 0
        if (walls != null) {
            for (i in 0 until walls.length()) {
                val wall = walls.optJSONObject(i)
                openings += wall?.optJSONArray("openings")?.length() ?: 0
            }
        }

        return RoomRecord(
            id            = UUID.randomUUID().toString(),
            name          = name.trim().ifEmpty { "Stanza" },
            timestamp     = System.currentTimeMillis(),
            area          = dims?.getDouble("area")   ?: floor?.getDouble("area") ?: 0.0,
            height        = dims?.getDouble("height") ?: 0.0,
            wallCount     = walls?.length()           ?: 0,
            openingCount  = openings,
            floorPlanPath = result.getString("floorPlanPath"),
            glbPath       = result.getString("glbPath")
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
}
