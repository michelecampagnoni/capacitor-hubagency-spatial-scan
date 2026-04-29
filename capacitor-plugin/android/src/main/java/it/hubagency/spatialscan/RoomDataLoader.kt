package it.hubagency.spatialscan

import android.content.Context
import android.util.Log
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ricostruisce RoomExportData dai file JSON salvati sul device.
 * Utilizzato da SpatialScanPlugin.exportPdf() senza bisogno di una Activity.
 */
object RoomDataLoader {

    fun buildExportData(context: Context): RoomExportData? {
        val roomRecords = RoomHistoryManager.loadAll(context)
        if (roomRecords.isEmpty()) return null

        val rootId       = roomRecords.first().id
        val componentIds = CompositionGraph.getComponentRoomIds(context, rootId)
            .ifEmpty { roomRecords.map { it.id } }
        val roomMap      = roomRecords.associateBy { it.id }

        val allWalls    = mutableListOf<ExportWall>()
        val allPolygons = mutableListOf<Pair<String, List<Pair<Float, Float>>>>()

        for (roomId in componentIds) {
            val json   = RoomHistoryManager.loadRoomData(context, roomId) ?: continue
            val worldT = CompositionGraph.getTransform(context, roomId)
            val walls  = parseWalls(json)
            allWalls.addAll(if (worldT != null)
                transformWalls(walls, worldT.worldOffsetX, worldT.worldOffsetZ, worldT.worldRotRad)
            else walls)

            val name     = roomMap[roomId]?.name ?: roomId.take(6)
            val rawPoly  = parsePolygon(json)
            val worldPoly = if (worldT != null) {
                val c = cos(worldT.worldRotRad); val s = sin(worldT.worldRotRad)
                rawPoly.map { (x, z) ->
                    Pair(x * c - z * s + worldT.worldOffsetX, x * s + z * c + worldT.worldOffsetZ)
                }
            } else rawPoly
            allPolygons.add(name to worldPoly)
        }

        if (allWalls.isEmpty()) return null

        val totalArea = roomRecords.sumOf { it.area }
        val allX = allWalls.flatMap { listOf(it.startX.toDouble(), it.endX.toDouble()) }
        val allZ = allWalls.flatMap { listOf(it.startZ.toDouble(), it.endZ.toDouble()) }
        val dims = RoomDimensions(
            width     = (allX.max() - allX.min()).coerceAtLeast(0.0),
            length    = (allZ.max() - allZ.min()).coerceAtLeast(0.0),
            height    = allWalls.maxOf { it.height.toDouble() },
            area      = totalArea,
            perimeter = 0.0
        )
        return RoomExportData(allWalls, dims, allPolygons)
    }

    // ── Parsing ────────────────────────────────────────────────────────────────

    private fun parseWalls(json: JSONObject): List<ExportWall> = try {
        val walls = json.getJSONArray("walls")
        (0 until walls.length()).map { i ->
            val w  = walls.getJSONObject(i)
            val sp = w.getJSONObject("startPoint"); val ep = w.getJSONObject("endPoint")
            val sx = sp.optDouble("x", 0.0).toFloat(); val sz = sp.optDouble("z", 0.0).toFloat()
            val ex = ep.optDouble("x", 0.0).toFloat(); val ez = ep.optDouble("z", 0.0).toFloat()
            val dx = ex - sx; val dz = ez - sz
            val len = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-6f)
            val h   = w.optDouble("height", 2.5).toFloat()
            val opsArr = w.optJSONArray("openings")
            val openings: List<ExportOpening> = if (opsArr != null) {
                (0 until opsArr.length()).mapNotNull { j ->
                    val o    = opsArr.optJSONObject(j) ?: return@mapNotNull null
                    val kind = runCatching { OpeningKind.valueOf(o.optString("kind", "DOOR")) }
                                  .getOrDefault(OpeningKind.DOOR)
                    ExportOpening(kind,
                        o.optDouble("offsetAlongWall", 0.0).toFloat(),
                        o.optDouble("width",  0.8).toFloat(),
                        o.optDouble("bottom", 0.0).toFloat(),
                        o.optDouble("height", 2.1).toFloat())
                }
            } else emptyList()
            ExportWall(id = w.optString("id", "w$i"),
                startX = sx, startZ = sz, endX = ex, endZ = ez,
                length = len, height = h,
                normalX = dz / len, normalZ = -(dx / len),
                dirX = dx / len, dirZ = dz / len,
                openings = openings)
        }
    } catch (e: Exception) {
        Log.e("RoomDataLoader", "parseWalls failed: ${e.message}"); emptyList()
    }

    private fun parsePolygon(json: JSONObject): List<Pair<Float, Float>> = try {
        val verts = json.getJSONObject("floor").getJSONArray("vertices")
        (0 until verts.length()).map { i ->
            val v = verts.getJSONObject(i)
            Pair(v.optDouble("x", 0.0).toFloat(), v.optDouble("z", 0.0).toFloat())
        }
    } catch (e: Exception) { emptyList() }

    private fun transformWalls(walls: List<ExportWall>, ox: Float, oz: Float, rot: Float): List<ExportWall> {
        val c = cos(rot); val s = sin(rot)
        return walls.map { w ->
            val sx = w.startX * c - w.startZ * s + ox; val sz = w.startX * s + w.startZ * c + oz
            val ex = w.endX   * c - w.endZ   * s + ox; val ez = w.endX   * s + w.endZ   * c + oz
            val dx = ex - sx; val dz = ez - sz
            val len = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-6f)
            w.copy(startX = sx, startZ = sz, endX = ex, endZ = ez, length = len,
                   dirX = dx / len, dirZ = dz / len, normalX = dz / len, normalZ = -(dx / len))
        }
    }
}
