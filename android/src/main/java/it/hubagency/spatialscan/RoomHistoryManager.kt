package it.hubagency.spatialscan

import android.content.Context
import android.util.Log
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent local storage for room scan history.
 *
 * Format: JSON array written to context.filesDir/room_history.json
 * No external dependencies — uses Android's built-in org.json.
 *
 * Thread safety: all public methods are safe to call from the main thread.
 * Reads and writes are synchronous and fast (small file, <1ms typical).
 */
object RoomHistoryManager {

    private const val TAG       = "RoomHistoryManager"
    private const val FILE_NAME = "room_history.json"

    /** Append a new room record to the history. */
    fun save(record: RoomRecord, context: Context) {
        val all = loadAll(context).toMutableList()
        all.add(record)
        write(all, context)
        Log.d(TAG, "saved '${record.name}' id=${record.id} area=${"%.1f".format(record.area)}m²")
    }

    /** Returns all saved rooms, oldest first. Empty list on any error. */
    fun loadAll(context: Context): List<RoomRecord> {
        return try {
            val file = historyFile(context)
            if (!file.exists()) return emptyList()
            val json = JSONArray(file.readText())
            (0 until json.length()).map { i -> fromJson(json.getJSONObject(i)) }
        } catch (e: Exception) {
            Log.e(TAG, "loadAll failed", e)
            emptyList()
        }
    }

    /**
     * Delete a room by id.
     * @return true if a record was found and removed, false if id was not found.
     */
    fun delete(id: String, context: Context): Boolean {
        val all = loadAll(context).toMutableList()
        val removed = all.removeIf { it.id == id }
        if (removed) write(all, context)
        return removed
    }

    /** Serialize a list of RoomRecord into a Capacitor JSArray for JS delivery. */
    fun toJSArray(records: List<RoomRecord>): JSArray = JSArray().also { arr ->
        records.forEach { r ->
            arr.put(JSObject().apply {
                put("id",           r.id)
                put("name",         r.name)
                put("timestamp",    r.timestamp)
                put("area",         r.area)
                put("height",       r.height)
                put("openingCount", r.openingCount)
                if (r.floorPlanPath != null) put("floorPlanPath", r.floorPlanPath)
                if (r.glbPath       != null) put("glbPath",       r.glbPath)
            })
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun write(records: List<RoomRecord>, context: Context) {
        try {
            val json = JSONArray()
            records.forEach { r ->
                json.put(JSONObject().apply {
                    put("id",            r.id)
                    put("name",          r.name)
                    put("timestamp",     r.timestamp)
                    put("area",          r.area)
                    put("height",        r.height)
                    put("openingCount",  r.openingCount)
                    put("floorPlanPath", r.floorPlanPath ?: JSONObject.NULL)
                    put("glbPath",       r.glbPath       ?: JSONObject.NULL)
                })
            }
            historyFile(context).writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "write failed", e)
        }
    }

    private fun fromJson(obj: JSONObject) = RoomRecord(
        id            = obj.getString("id"),
        name          = obj.getString("name"),
        timestamp     = obj.getLong("timestamp"),
        area          = obj.getDouble("area"),
        height        = obj.getDouble("height"),
        openingCount  = obj.getInt("openingCount"),
        floorPlanPath = obj.optString("floorPlanPath").ifEmpty { null },
        glbPath       = obj.optString("glbPath").ifEmpty { null }
    )

    private fun historyFile(context: Context) = File(context.filesDir, FILE_NAME)
}
