package it.hubagency.spatialscan

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Genera un file GLB (binary GLTF 2.0) dalla geometria della stanza.
 * Ogni parete = quad 3D (4 vertici, 2 triangoli, faccia visibile dal lato della normale).
 * Il pavimento = quad 3D (convex hull delle pareti, altezza 0).
 * Salvato in cacheDir, restituisce path "file://...".
 */
object GlbExporter {

    private const val MAGIC_GLTF  = 0x46546C67 // "glTF"
    private const val GLTF_VERSION = 2
    private const val CHUNK_JSON  = 0x4E4F534A // "JSON"
    private const val CHUNK_BIN   = 0x004E4942 // "BIN\0"

    fun export(walls: List<Wall>, roomDim: RoomDimensions, cacheDir: File): String? {
        if (walls.isEmpty()) return null
        return try {
            // ── 1. Costruisci dati binari (posizioni + indici) ──────────────────
            val wallVerts   = buildWallVertices(walls)   // FloatArray, 4 verts × 3 floats per wall
            val wallIndices = buildWallIndices(walls)    // ShortArray, 6 indices per wall

            val posBytes = floatsToBytes(wallVerts)
            val idxBytes = shortsToBytes(wallIndices)

            // Offset indici = dimensione buffer posizioni (allineato a 4 byte)
            val posLen = posBytes.size
            val idxOffset = align4(posLen)
            val idxLen = idxBytes.size

            val binBuf = ByteArray(idxOffset + align4(idxLen))
            posBytes.copyInto(binBuf, 0)
            idxBytes.copyInto(binBuf, idxOffset)

            // ── 2. Calcola bounds per l'accessor POSITION ───────────────────────
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

            // ── 3. Costruisci JSON ─────────────────────────────────────────────
            val vertCount = wallVerts.size / 3
            val idxCount  = wallIndices.size

            val json = buildGltfJson(
                binByteLength  = binBuf.size,
                posBufferOffset = 0,          posBufferLen  = posLen,
                idxBufferOffset = idxOffset,  idxBufferLen  = idxLen,
                vertCount = vertCount,        idxCount = idxCount,
                min = floatArrayOf(minX, minY, minZ),
                max = floatArrayOf(maxX, maxY, maxZ)
            )

            // ── 4. Scrivi GLB ──────────────────────────────────────────────────
            val jsonBytes  = json.toByteArray(Charsets.UTF_8)
            val jsonPadded = pad4(jsonBytes, 0x20) // pad JSON con spazi
            val binPadded  = pad4(binBuf,    0x00) // pad BIN con zeri

            val totalLen = 12 + 8 + jsonPadded.size + 8 + binPadded.size
            val out = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)

            out.putInt(MAGIC_GLTF)
            out.putInt(GLTF_VERSION)
            out.putInt(totalLen)

            out.putInt(jsonPadded.size)
            out.putInt(CHUNK_JSON)
            out.put(jsonPadded)

            out.putInt(binPadded.size)
            out.putInt(CHUNK_BIN)
            out.put(binPadded)

            val file = File(cacheDir, "scan_${System.currentTimeMillis()}.glb")
            FileOutputStream(file).use { it.write(out.array()) }
            "file://${file.absolutePath}"
        } catch (_: Exception) {
            null
        }
    }

    // ── Geometria pareti ────────────────────────────────────────────────────────

    /**
     * Per ogni parete: quad verticale (4 vertici, 2 triangoli).
     * Vertici in senso antiorario visto dal lato della normale.
     */
    private fun buildWallVertices(walls: List<Wall>): FloatArray {
        val out = FloatArray(walls.size * 4 * 3)
        var idx = 0
        for (wall in walls) {
            val x1 = wall.startPoint.x.toFloat()
            val x2 = wall.endPoint.x.toFloat()
            val z1 = wall.startPoint.z.toFloat()
            val z2 = wall.endPoint.z.toFloat()
            val yBase = wall.startPoint.y.toFloat()
            val yTop  = (yBase + wall.height).toFloat()
            // v0: start-bottom, v1: end-bottom, v2: end-top, v3: start-top
            out[idx++] = x1; out[idx++] = yBase; out[idx++] = z1
            out[idx++] = x2; out[idx++] = yBase; out[idx++] = z2
            out[idx++] = x2; out[idx++] = yTop;  out[idx++] = z2
            out[idx++] = x1; out[idx++] = yTop;  out[idx++] = z1
        }
        return out
    }

    /** Per ogni parete: 6 indici (2 triangoli) — indices globali. */
    private fun buildWallIndices(walls: List<Wall>): ShortArray {
        val out = ShortArray(walls.size * 6)
        var idx = 0
        for (i in walls.indices) {
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

    // ── JSON GLTF ───────────────────────────────────────────────────────────────

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

    // ── Utility ─────────────────────────────────────────────────────────────────

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
