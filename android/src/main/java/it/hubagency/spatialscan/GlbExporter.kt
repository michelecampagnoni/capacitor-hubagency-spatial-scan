package it.hubagency.spatialscan

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Genera un file GLB (binary GLTF 2.0) dalla geometria della stanza.
 * Ogni parete è suddivisa in quad attorno alle aperture (porte/finestre).
 * - Segmenti solidi a sinistra/destra di ogni apertura: quad full-height
 * - Sopra ogni apertura: quad da topOfOpening a wallHeight
 * - Sotto ogni apertura (solo finestre): quad da 0 a bottom
 * Il pavimento = quad orizzontale a Y=0.
 * Salvato in cacheDir, restituisce path assoluto.
 */
object GlbExporter {

    private const val MAGIC_GLTF   = 0x46546C67
    private const val GLTF_VERSION = 2
    private const val CHUNK_JSON   = 0x4E4F534A
    private const val CHUNK_BIN    = 0x004E4942

    fun export(data: RoomExportData, cacheDir: File): String? {
        if (data.walls.isEmpty()) return null
        return try {
            val allQuads = data.walls.flatMap { wallQuads(it) }
            if (allQuads.isEmpty()) return null

            val wallVerts   = buildVertices(allQuads)
            val wallIndices = buildIndices(allQuads.size)

            val posBytes  = floatsToBytes(wallVerts)
            val idxBytes  = shortsToBytes(wallIndices)

            val posLen    = posBytes.size
            val idxOffset = align4(posLen)
            val idxLen    = idxBytes.size

            val binBuf = ByteArray(idxOffset + align4(idxLen))
            posBytes.copyInto(binBuf, 0)
            idxBytes.copyInto(binBuf, idxOffset)

            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            var i = 0
            while (i < wallVerts.size) {
                val x = wallVerts[i]; val y = wallVerts[i + 1]; val z = wallVerts[i + 2]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                i += 3
            }

            val vertCount = wallVerts.size / 3
            val idxCount  = wallIndices.size

            val json = buildGltfJson(
                binByteLength   = binBuf.size,
                posBufferOffset = 0,         posBufferLen = posLen,
                idxBufferOffset = idxOffset, idxBufferLen = idxLen,
                vertCount       = vertCount, idxCount     = idxCount,
                min             = floatArrayOf(minX, minY, minZ),
                max             = floatArrayOf(maxX, maxY, maxZ)
            )

            val jsonBytes  = json.toByteArray(Charsets.UTF_8)
            val jsonPadded = pad4(jsonBytes, 0x20)
            val binPadded  = pad4(binBuf,    0x00)

            val totalLen = 12 + 8 + jsonPadded.size + 8 + binPadded.size
            val out = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(MAGIC_GLTF); out.putInt(GLTF_VERSION); out.putInt(totalLen)
            out.putInt(jsonPadded.size); out.putInt(CHUNK_JSON); out.put(jsonPadded)
            out.putInt(binPadded.size);  out.putInt(CHUNK_BIN);  out.put(binPadded)

            val file = File(cacheDir, "scan_${System.currentTimeMillis()}.glb")
            FileOutputStream(file).use { it.write(out.array()) }
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    // ── Quad geometry ────────────────────────────────────────────────────────────

    /** A rectangular quad defined by two points on the floor plane and a Y range. */
    private data class Quad(
        val x0: Float, val z0: Float,
        val x1: Float, val z1: Float,
        val yBase: Float, val yTop: Float
    )

    /**
     * Splits one ExportWall into quads, carving holes for each opening.
     * - Solid intervals along the wall → full-height quad
     * - Opening interval → quad below (window only) + quad above
     */
    private fun wallQuads(wall: ExportWall): List<Quad> {
        val quads   = mutableListOf<Quad>()
        val wallLen = wall.length
        val wallH   = wall.height

        if (wall.openings.isEmpty()) {
            quads.add(Quad(wall.startX, wall.startZ, wall.endX, wall.endZ, 0f, wallH))
            return quads
        }

        val sorted = wall.openings.sortedBy { it.offsetAlongWall }
        var cursor  = 0f

        for (o in sorted) {
            val t0 = o.offsetAlongWall.coerceIn(0f, wallLen)
            val t1 = (o.offsetAlongWall + o.width).coerceIn(0f, wallLen)

            // Solid segment before this opening
            if (t0 > cursor) {
                quads.add(segmentQuad(wall, cursor, t0, 0f, wallH))
            }

            // Below opening (window sill)
            val obBottom = o.bottom.coerceIn(0f, wallH)
            if (obBottom > 0f) {
                quads.add(segmentQuad(wall, t0, t1, 0f, obBottom))
            }

            // Above opening (lintel / wall above frame)
            val obTop = (o.bottom + o.height).coerceIn(0f, wallH)
            if (obTop < wallH) {
                quads.add(segmentQuad(wall, t0, t1, obTop, wallH))
            }

            cursor = maxOf(cursor, t1)
        }

        // Solid segment after last opening
        if (cursor < wallLen) {
            quads.add(segmentQuad(wall, cursor, wallLen, 0f, wallH))
        }

        return quads
    }

    private fun segmentQuad(wall: ExportWall, t0: Float, t1: Float, yBase: Float, yTop: Float): Quad {
        val x0 = wall.startX + wall.dirX * t0
        val z0 = wall.startZ + wall.dirZ * t0
        val x1 = wall.startX + wall.dirX * t1
        val z1 = wall.startZ + wall.dirZ * t1
        return Quad(x0, z0, x1, z1, yBase, yTop)
    }

    // ── Vertex / index buffers ────────────────────────────────────────────────────

    /**
     * Each quad → 4 vertices: v0=start-bottom, v1=end-bottom, v2=end-top, v3=start-top.
     */
    private fun buildVertices(quads: List<Quad>): FloatArray {
        val out = FloatArray(quads.size * 4 * 3)
        var idx = 0
        for (q in quads) {
            out[idx++] = q.x0; out[idx++] = q.yBase; out[idx++] = q.z0
            out[idx++] = q.x1; out[idx++] = q.yBase; out[idx++] = q.z1
            out[idx++] = q.x1; out[idx++] = q.yTop;  out[idx++] = q.z1
            out[idx++] = q.x0; out[idx++] = q.yTop;  out[idx++] = q.z0
        }
        return out
    }

    /** Each quad → 6 indices (2 CCW triangles). */
    private fun buildIndices(quadCount: Int): ShortArray {
        val out = ShortArray(quadCount * 6)
        var idx = 0
        for (i in 0 until quadCount) {
            val base = (i * 4).toShort()
            out[idx++] = base
            out[idx++] = (base + 1).toShort()
            out[idx++] = (base + 2).toShort()
            out[idx++] = base
            out[idx++] = (base + 2).toShort()
            out[idx++] = (base + 3).toShort()
        }
        return out
    }

    // ── GLTF JSON ────────────────────────────────────────────────────────────────

    private fun buildGltfJson(
        binByteLength: Int,
        posBufferOffset: Int, posBufferLen: Int,
        idxBufferOffset: Int, idxBufferLen: Int,
        vertCount: Int, idxCount: Int,
        min: FloatArray, max: FloatArray
    ): String {
        val minStr = "[${min[0]},${min[1]},${min[2]}]"
        val maxStr = "[${max[0]},${max[1]},${max[2]}]"
        return """{"asset":{"version":"2.0","generator":"HubAgency ARCore v1.0"},"scene":0,"scenes":[{"name":"Room","nodes":[0]}],"nodes":[{"name":"Room","mesh":0}],"meshes":[{"name":"Room","primitives":[{"attributes":{"POSITION":0},"indices":1,"material":0,"mode":4}]}],"materials":[{"name":"Wall","pbrMetallicRoughness":{"baseColorFactor":[0.88,0.88,0.90,1.0],"metallicFactor":0.05,"roughnessFactor":0.85},"doubleSided":true}],"accessors":[{"bufferView":0,"byteOffset":0,"componentType":5126,"count":$vertCount,"type":"VEC3","min":$minStr,"max":$maxStr},{"bufferView":1,"byteOffset":0,"componentType":5123,"count":$idxCount,"type":"SCALAR"}],"bufferViews":[{"buffer":0,"byteOffset":$posBufferOffset,"byteLength":$posBufferLen},{"buffer":0,"byteOffset":$idxBufferOffset,"byteLength":$idxBufferLen}],"buffers":[{"byteLength":$binByteLength}]}"""
    }

    // ── Utility ──────────────────────────────────────────────────────────────────

    private fun floatsToBytes(floats: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        shorts.forEach { buf.putShort(it) }
        return buf.array()
    }

    private fun align4(n: Int) = (n + 3) and 3.inv()

    private fun pad4(data: ByteArray, padByte: Int): ByteArray {
        val padded = align4(data.size)
        if (padded == data.size) return data
        return data + ByteArray(padded - data.size) { padByte.toByte() }
    }
}
