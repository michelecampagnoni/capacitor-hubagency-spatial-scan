package it.hubagency.spatialscan

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Registro persistente delle aperture interne non ancora collegate bilateralmente.
 * Salvato in filesDir/hub_unlinked_openings.json.
 *
 * Ciclo di vita di una entry:
 *  1. Viene creata quando l'utente conferma un'apertura interna con status PENDING
 *     (sourceRoomId viene popolato dopo il salvataggio del RoomRecord).
 *  2. Viene rimossa quando l'utente collega quella apertura durante la scan
 *     di un ambiente adiacente (status diventa LINKED bilateralmente).
 */
data class UnlinkedOpening(
    val id:             String,   // UUID entry nel registro
    val sourceRoomId:   String,   // UUID del RoomRecord sorgente
    val sourceRoomName: String,   // nome leggibile ("Corridoio")
    val openingId:      String,   // OpeningModel.id nell'ambiente sorgente
    val kind:           OpeningKind,
    val width:          Float,
    val height:         Float,
    val bottom:         Float,
    val wallIndex:      Int,      // indice parete nell'ambiente sorgente (per etichetta)
    val customLabel:    String = ""  // etichetta utente es. "cucina", "bagno"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id",             id)
        put("sourceRoomId",   sourceRoomId)
        put("sourceRoomName", sourceRoomName)
        put("openingId",      openingId)
        put("kind",           kind.name)
        put("width",          width)
        put("height",         height)
        put("bottom",         bottom)
        put("wallIndex",      wallIndex)
        put("customLabel",    customLabel)
    }

    companion object {
        fun fromJson(obj: JSONObject): UnlinkedOpening? = runCatching {
            UnlinkedOpening(
                id             = obj.getString("id"),
                sourceRoomId   = obj.getString("sourceRoomId"),
                sourceRoomName = obj.getString("sourceRoomName"),
                openingId      = obj.getString("openingId"),
                kind           = OpeningKind.valueOf(obj.getString("kind")),
                width          = obj.optDouble("width",  0.80).toFloat(),
                height         = obj.optDouble("height", 2.10).toFloat(),
                bottom         = obj.optDouble("bottom", 0.00).toFloat(),
                wallIndex      = obj.optInt("wallIndex", 0),
                customLabel    = obj.optString("customLabel", "")
            )
        }.getOrNull()
    }
}

object UnlinkedOpeningStore {

    private const val TAG      = "UnlinkedOpeningStore"
    private const val FILENAME = "hub_unlinked_openings.json"

    fun add(context: Context, entry: UnlinkedOpening) {
        try {
            val list = loadAll(context).toMutableList()
            list.add(entry)
            writeAll(context, list)
            Log.d(TAG, "added opening id=${entry.id} from '${entry.sourceRoomName}'")
        } catch (e: Exception) {
            Log.e(TAG, "add failed: ${e.message}", e)
        }
    }

    fun remove(context: Context, id: String) {
        try {
            val list = loadAll(context).filter { it.id != id }
            writeAll(context, list)
            Log.d(TAG, "removed opening id=$id")
        } catch (e: Exception) {
            Log.e(TAG, "remove failed: ${e.message}", e)
        }
    }

    fun loadAll(context: Context): List<UnlinkedOpening> {
        return try {
            val f = File(context.filesDir, FILENAME)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull {
                runCatching { UnlinkedOpening.fromJson(arr.getJSONObject(it)) }.getOrNull()
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadAll failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun writeAll(context: Context, entries: List<UnlinkedOpening>) {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        File(context.filesDir, FILENAME).writeText(arr.toString())
    }
}
