package it.hubagency.spatialscan

import org.json.JSONObject

/**
 * Record immutabile di una stanza scansionata.
 * Serializzato/deserializzato da RoomHistoryManager come JSON.
 */
data class RoomRecord(
    val id:            String,   // UUID generato al salvataggio
    val name:          String,   // nome assegnato dall'utente
    val timestamp:     Long,     // ms epoch (System.currentTimeMillis())
    val area:          Double,   // m²
    val height:        Double,   // m
    val wallCount:     Int,
    val openingCount:  Int,
    val floorPlanPath: String?,  // path assoluto PNG (in cacheDir)
    val glbPath:       String?   // path assoluto GLB (in cacheDir)
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id",            id)
        put("name",          name)
        put("timestamp",     timestamp)
        put("area",          area)
        put("height",        height)
        put("wallCount",     wallCount)
        put("openingCount",  openingCount)
        if (floorPlanPath != null) put("floorPlanPath", floorPlanPath)
        if (glbPath       != null) put("glbPath",       glbPath)
    }

    companion object {
        fun fromJson(obj: JSONObject): RoomRecord = RoomRecord(
            id            = obj.getString("id"),
            name          = obj.getString("name"),
            timestamp     = obj.getLong("timestamp"),
            area          = obj.optDouble("area", 0.0),
            height        = obj.optDouble("height", 0.0),
            wallCount     = obj.optInt("wallCount", 0),
            openingCount  = obj.optInt("openingCount", 0),
            floorPlanPath = obj.optString("floorPlanPath").takeIf { it.isNotEmpty() },
            glbPath       = obj.optString("glbPath").takeIf { it.isNotEmpty() }
        )
    }
}
