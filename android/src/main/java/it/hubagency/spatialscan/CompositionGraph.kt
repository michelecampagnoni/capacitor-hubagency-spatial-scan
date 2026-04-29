package it.hubagency.spatialscan

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * World transform di una stanza nel sistema di riferimento della root
 * della sua componente connessa.
 *
 * La root non ha entry nel grafo: il suo transform è l'identità (0, 0, 0).
 *
 * parentId serve solo per BFS (ricostruzione componente connessa).
 * worldOffsetX/Z e worldRotRad sono le coordinate mondiali assolute:
 * un punto (x,z) in spazio locale diventa (x*cos(rot)-z*sin(rot)+ox, x*sin(rot)+z*cos(rot)+oz).
 */
data class RoomWorldTransform(
    val roomId:       String,
    val parentId:     String,
    val worldOffsetX: Float,
    val worldOffsetZ: Float,
    val worldRotRad:  Float,
    val confirmedAt:  Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("roomId",       roomId)
        put("parentId",     parentId)
        put("worldOffsetX", worldOffsetX.toDouble())
        put("worldOffsetZ", worldOffsetZ.toDouble())
        put("worldRotRad",  worldRotRad.toDouble())
        put("confirmedAt",  confirmedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): RoomWorldTransform = RoomWorldTransform(
            roomId       = obj.getString("roomId"),
            parentId     = obj.getString("parentId"),
            worldOffsetX = obj.optDouble("worldOffsetX", 0.0).toFloat(),
            worldOffsetZ = obj.optDouble("worldOffsetZ", 0.0).toFloat(),
            worldRotRad  = obj.optDouble("worldRotRad",  0.0).toFloat(),
            confirmedAt  = obj.optLong("confirmedAt", 0L)
        )
    }
}

/**
 * Grafo di composizione planimetrica multi-stanza.
 *
 * Struttura: foresta di alberi. Ogni edge è "parentId → childId".
 * La root di ogni albero è il primo ambiente scansionato nella componente.
 * La root non ha entry nel grafo; il suo world transform è l'identità.
 *
 * Persistenza: filesDir/hub_graph.json
 */
object CompositionGraph {

    private const val TAG      = "CompositionGraph"
    private const val FILENAME = "hub_graph.json"

    // ── API pubblica ──────────────────────────────────────────────────────────

    /**
     * Aggiunge o sovrascrive il world transform di un room.
     * Chiamato da RoomComposerActivity.confirmSave().
     */
    fun addTransform(context: Context, t: RoomWorldTransform) {
        try {
            val list = loadAll(context).toMutableList()
            val idx  = list.indexOfFirst { it.roomId == t.roomId }
            if (idx >= 0) list[idx] = t else list.add(t)
            writeAll(context, list)
            Log.d(TAG, "addTransform roomId=${t.roomId} parent=${t.parentId} " +
                       "world=(${t.worldOffsetX},${t.worldOffsetZ},${Math.toDegrees(t.worldRotRad.toDouble()).toInt()}°)")
        } catch (e: Exception) {
            Log.e(TAG, "addTransform failed: ${e.message}", e)
        }
    }

    /**
     * Restituisce il world transform di un room.
     * Null = room è root della sua componente (transform identità: offset 0,0 rot 0).
     */
    fun getTransform(context: Context, roomId: String): RoomWorldTransform? =
        loadAll(context).firstOrNull { it.roomId == roomId }

    /**
     * Restituisce l'ID della root della componente connessa che contiene roomId.
     * La root è il nodo senza entry nel grafo.
     */
    fun getRootId(context: Context, roomId: String): String {
        val byRoom = loadAll(context).associateBy { it.roomId }
        var current = roomId
        val visited = mutableSetOf<String>()
        while (true) {
            if (!visited.add(current)) break   // ciclo guard
            current = byRoom[current]?.parentId ?: break  // nessun parent → root
        }
        return current
    }

    /**
     * Restituisce tutti i roomId nella stessa componente connessa (BFS bidirezionale).
     * Funziona anche quando roomId è la root (nessun edge entrante).
     */
    fun getComponentRoomIds(context: Context, roomId: String): List<String> {
        val all = loadAll(context)

        // Costruisci grafo non orientato per BFS
        val adj = mutableMapOf<String, MutableList<String>>()
        for (t in all) {
            adj.getOrPut(t.parentId) { mutableListOf() }.add(t.roomId)
            adj.getOrPut(t.roomId)   { mutableListOf() }.add(t.parentId)
        }

        val visited = mutableSetOf<String>()
        val queue   = ArrayDeque<String>()
        queue.add(roomId); visited.add(roomId)
        while (queue.isNotEmpty()) {
            for (neighbor in adj[queue.removeFirst()] ?: emptyList()) {
                if (visited.add(neighbor)) queue.add(neighbor)
            }
        }
        return visited.toList()
    }

    /**
     * Rimuove il world transform di un room dal grafo.
     * Chiamato dalla cancellazione stanza nel Composer.
     */
    fun removeTransform(context: Context, roomId: String) {
        try {
            val list = loadAll(context).filter { it.roomId != roomId }
            writeAll(context, list)
            Log.d(TAG, "removeTransform roomId=$roomId")
        } catch (e: Exception) {
            Log.e(TAG, "removeTransform failed: ${e.message}", e)
        }
    }

    /**
     * Restituisce i roomId dei figli diretti di parentId nel grafo.
     */
    fun getChildIds(context: Context, parentId: String): List<String> =
        loadAll(context).filter { it.parentId == parentId }.map { it.roomId }

    fun loadAll(context: Context): List<RoomWorldTransform> {
        return try {
            val f = File(context.filesDir, FILENAME)
            if (!f.exists()) return emptyList()
            val arr = JSONObject(f.readText()).getJSONArray("nodes")
            (0 until arr.length()).mapNotNull {
                runCatching { RoomWorldTransform.fromJson(arr.getJSONObject(it)) }.getOrNull()
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadAll failed: ${e.message}", e); emptyList()
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun writeAll(context: Context, list: List<RoomWorldTransform>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        File(context.filesDir, FILENAME)
            .writeText(JSONObject().apply { put("nodes", arr) }.toString())
    }
}
