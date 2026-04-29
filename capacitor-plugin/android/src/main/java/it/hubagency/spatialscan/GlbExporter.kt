package it.hubagency.spatialscan

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Genera un file GLB (binary GLTF 2.0) dalla geometria della stanza.
 *
 * Struttura del modello:
 *   Primitiva 0 — Muri (material "Wall", grigio chiaro)
 *     Ogni parete è suddivisa in quad attorno alle aperture (porte/finestre/portefinestre).
 *     Attributi: POSITION + NORMAL (normale perpendicolare al piano del muro).
 *   Primitiva 1 — Pavimento (material "Floor", grigio scuro)
 *     Fan triangulation del poligono stanza a Y=0.
 *     Attributi: POSITION + NORMAL (0,1,0).
 *
 * Layout buffer binario (non-interleaved):
 *   [wall positions] [wall normals] [wall indices] [floor positions] [floor normals] [floor indices]
 *   ogni sezione allineata a 4 byte.
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

            // ── Wall geometry ──────────────────────────────────────────────────
            val wallPos  = buildWallPositions(allQuads)
            val wallNorm = buildWallNormals(allQuads)
            val wallIdx  = buildWallIndices(allQuads.size)

            // ── Floor geometry ─────────────────────────────────────────────────
            val floorPolys = data.roomPolygons.map { (_, poly) -> poly }.filter { it.size >= 3 }
            val hasFloor   = floorPolys.isNotEmpty()
            val floorPos   = if (hasFloor) buildFloorPositions(floorPolys) else FloatArray(0)
            val floorNorm  = if (hasFloor) buildFloorNormals(floorPos.size / 3) else FloatArray(0)
            val floorIdx   = if (hasFloor) buildFloorIndices(floorPolys) else ShortArray(0)

            // ── Pack binary buffer ─────────────────────────────────────────────
            val wallPosB  = floatsToBytes(wallPos)
            val wallNormB = floatsToBytes(wallNorm)
            val wallIdxB  = shortsToBytes(wallIdx)
            val floorPosB = floatsToBytes(floorPos)
            val floorNormB= floatsToBytes(floorNorm)
            val floorIdxB = shortsToBytes(floorIdx)

            val off0 = 0
            val off1 = align4(off0 + wallPosB.size)
            val off2 = align4(off1 + wallNormB.size)
            val off3 = align4(off2 + wallIdxB.size)
            val off4 = align4(off3 + floorPosB.size)
            val off5 = align4(off4 + floorNormB.size)
            val binLen = align4(off5 + floorIdxB.size)

            val binBuf = ByteArray(binLen)
            wallPosB.copyInto(binBuf, off0)
            wallNormB.copyInto(binBuf, off1)
            wallIdxB.copyInto(binBuf, off2)
            if (hasFloor) {
                floorPosB.copyInto(binBuf, off3)
                floorNormB.copyInto(binBuf, off4)
                floorIdxB.copyInto(binBuf, off5)
            }

            // ── Bounding box (walls only, sufficiente per GLTF accessor) ──────
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            var i = 0
            while (i < wallPos.size) {
                val x = wallPos[i]; val y = wallPos[i + 1]; val z = wallPos[i + 2]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                i += 3
            }

            val json = buildGltfJson(
                binLen       = binLen,
                wallVerts    = wallPos.size / 3, wallIdxCount = wallIdx.size,
                wallPosOff   = off0, wallPosLen  = wallPosB.size,
                wallNormOff  = off1, wallNormLen  = wallNormB.size,
                wallIdxOff   = off2, wallIdxLen   = wallIdxB.size,
                hasFloor     = hasFloor,
                floorVerts   = floorPos.size / 3, floorIdxCount = floorIdx.size,
                floorPosOff  = off3, floorPosLen  = floorPosB.size,
                floorNormOff = off4, floorNormLen  = floorNormB.size,
                floorIdxOff  = off5, floorIdxLen   = floorIdxB.size,
                min = floatArrayOf(minX, minY, minZ),
                max = floatArrayOf(maxX, maxY, maxZ)
            )

            val jsonPadded = pad4(json.toByteArray(Charsets.UTF_8), 0x20)
            val binPadded  = pad4(binBuf, 0x00)

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

    // ── Wall geometry ─────────────────────────────────────────────────────────

    /** Quad su un piano verticale; (nx, nz) è la normale del muro nel piano XZ. */
    private data class Quad(
        val x0: Float, val z0: Float,
        val x1: Float, val z1: Float,
        val yBase: Float, val yTop: Float,
        val nx: Float, val nz: Float
    )

    /**
     * Divide un ExportWall in uno o più Quad, ritagliando le aperture.
     * Segmenti solidi → quad full-height.
     * Aperture → quad sotto (davanzale, solo finestre) + quad sopra (architrave).
     */
    private fun wallQuads(wall: ExportWall): List<Quad> {
        val quads   = mutableListOf<Quad>()
        val wallLen = wall.length
        val wallH   = wall.height

        if (wall.openings.isEmpty()) {
            quads.add(Quad(wall.startX, wall.startZ, wall.endX, wall.endZ, 0f, wallH, wall.normalX, wall.normalZ))
            return quads
        }

        val sorted = wall.openings.sortedBy { it.offsetAlongWall }
        var cursor = 0f

        for (o in sorted) {
            val t0 = o.offsetAlongWall.coerceIn(0f, wallLen)
            val t1 = (o.offsetAlongWall + o.width).coerceIn(0f, wallLen)

            if (t0 > cursor) quads.add(segmentQuad(wall, cursor, t0, 0f, wallH))

            val obBottom = o.bottom.coerceIn(0f, wallH)
            if (obBottom > 0f) quads.add(segmentQuad(wall, t0, t1, 0f, obBottom))

            val obTop = (o.bottom + o.height).coerceIn(0f, wallH)
            if (obTop < wallH) quads.add(segmentQuad(wall, t0, t1, obTop, wallH))

            cursor = maxOf(cursor, t1)
        }

        if (cursor < wallLen) quads.add(segmentQuad(wall, cursor, wallLen, 0f, wallH))
        return quads
    }

    private fun segmentQuad(wall: ExportWall, t0: Float, t1: Float, yBase: Float, yTop: Float): Quad {
        val x0 = wall.startX + wall.dirX * t0
        val z0 = wall.startZ + wall.dirZ * t0
        val x1 = wall.startX + wall.dirX * t1
        val z1 = wall.startZ + wall.dirZ * t1
        return Quad(x0, z0, x1, z1, yBase, yTop, wall.normalX, wall.normalZ)
    }

    /** 4 vertici per quad: v0=start-bottom, v1=end-bottom, v2=end-top, v3=start-top */
    private fun buildWallPositions(quads: List<Quad>): FloatArray {
        val out = FloatArray(quads.size * 4 * 3)
        var i = 0
        for (q in quads) {
            out[i++] = q.x0; out[i++] = q.yBase; out[i++] = q.z0
            out[i++] = q.x1; out[i++] = q.yBase; out[i++] = q.z1
            out[i++] = q.x1; out[i++] = q.yTop;  out[i++] = q.z1
            out[i++] = q.x0; out[i++] = q.yTop;  out[i++] = q.z0
        }
        return out
    }

    /** Normale (nx, 0, nz) identica per tutti i 4 vertici del quad. */
    private fun buildWallNormals(quads: List<Quad>): FloatArray {
        val out = FloatArray(quads.size * 4 * 3)
        var i = 0
        for (q in quads) repeat(4) { out[i++] = q.nx; out[i++] = 0f; out[i++] = q.nz }
        return out
    }

    /** 2 triangoli CCW per quad: (0,1,2) e (0,2,3). */
    private fun buildWallIndices(quadCount: Int): ShortArray {
        val out = ShortArray(quadCount * 6)
        var i = 0
        for (q in 0 until quadCount) {
            val b = (q * 4)
            out[i++] = b.toShort();       out[i++] = (b + 1).toShort(); out[i++] = (b + 2).toShort()
            out[i++] = b.toShort();       out[i++] = (b + 2).toShort(); out[i++] = (b + 3).toShort()
        }
        return out
    }

    // ── Floor geometry ────────────────────────────────────────────────────────

    private fun buildFloorPositions(polys: List<List<Pair<Float, Float>>>): FloatArray {
        val out = FloatArray(polys.sumOf { it.size } * 3)
        var i = 0
        for (poly in polys) for ((x, z) in poly) { out[i++] = x; out[i++] = 0f; out[i++] = z }
        return out
    }

    private fun buildFloorNormals(vertCount: Int): FloatArray {
        val out = FloatArray(vertCount * 3)
        var i = 0
        repeat(vertCount) { out[i++] = 0f; out[i++] = 1f; out[i++] = 0f }
        return out
    }

    /**
     * Ear-clipping triangulation per ogni poligono del pavimento.
     * Funziona correttamente per poligoni non convessi (stanze con rientranze).
     * Termina sempre per poligoni semplici (non auto-intersecanti).
     */
    private fun buildFloorIndices(polys: List<List<Pair<Float, Float>>>): ShortArray {
        val allTriangles = mutableListOf<Triple<Int, Int, Int>>()
        var base = 0
        for (poly in polys) {
            val n = poly.size
            if (n < 3) { base += n; continue }
            val local = earClip(poly)
            for ((a, b, c) in local) allTriangles.add(Triple(base + a, base + b, base + c))
            base += n
        }
        val out = ShortArray(allTriangles.size * 3)
        var i = 0
        for ((a, b, c) in allTriangles) { out[i++] = a.toShort(); out[i++] = b.toShort(); out[i++] = c.toShort() }
        return out
    }

    /**
     * Ear-clipping su un singolo poligono (XZ).
     * Restituisce indici locali 0..n-1 come Triple CCW rispetto a Y-up (normale (0,1,0)).
     */
    private fun earClip(poly: List<Pair<Float, Float>>): List<Triple<Int, Int, Int>> {
        val n = poly.size
        if (n == 3) return listOf(earWindingCorrect(poly, 0, 1, 2))

        // Shoelace: area positiva → poligono CCW in XZ (Y-up)
        var area = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += poly[i].first.toDouble()  * poly[j].second.toDouble()
            area -= poly[j].first.toDouble()  * poly[i].second.toDouble()
        }
        val polyCCW = area >= 0.0

        val idx = (0 until n).toMutableList()
        val result = mutableListOf<Triple<Int, Int, Int>>()

        while (idx.size > 3) {
            var earFound = false
            for (i in idx.indices) {
                val iPrev = (i - 1 + idx.size) % idx.size
                val iNext = (i + 1) % idx.size
                val a = idx[iPrev]; val b = idx[i]; val c = idx[iNext]

                val ax = poly[a].first; val az = poly[a].second
                val bx = poly[b].first; val bz = poly[b].second
                val cx = poly[c].first; val cz = poly[c].second

                // Il triangolo deve avere il winding corretto rispetto al poligono
                val cross = (bx - ax) * (cz - az) - (bz - az) * (cx - ax)
                val triCCW = cross > 0f
                if (triCCW != polyCCW) continue

                // Nessun altro vertice deve essere dentro il triangolo
                var inside = false
                for (j in idx.indices) {
                    if (j == iPrev || j == i || j == iNext) continue
                    val px = poly[idx[j]].first; val pz = poly[idx[j]].second
                    if (pointInTriangle(px, pz, ax, az, bx, bz, cx, cz)) { inside = true; break }
                }
                if (inside) continue

                result.add(earWindingCorrect(poly, a, b, c))
                idx.removeAt(i)
                earFound = true
                break
            }
            if (!earFound) break // poligono degenere, interrompi
        }

        if (idx.size == 3) result.add(earWindingCorrect(poly, idx[0], idx[1], idx[2]))
        return result
    }

    /** Garantisce winding CCW in XZ per normale Y-up = (0,1,0). */
    private fun earWindingCorrect(
        poly: List<Pair<Float, Float>>, a: Int, b: Int, c: Int
    ): Triple<Int, Int, Int> {
        val cross = (poly[b].first - poly[a].first) * (poly[c].second - poly[a].second) -
                    (poly[b].second - poly[a].second) * (poly[c].first - poly[a].first)
        return if (cross >= 0f) Triple(a, b, c) else Triple(a, c, b)
    }

    /** Test punto-in-triangolo (XZ plane). */
    private fun pointInTriangle(
        px: Float, pz: Float,
        ax: Float, az: Float,
        bx: Float, bz: Float,
        cx: Float, cz: Float
    ): Boolean {
        val d1 = triSign(px, pz, ax, az, bx, bz)
        val d2 = triSign(px, pz, bx, bz, cx, cz)
        val d3 = triSign(px, pz, cx, cz, ax, az)
        val hasNeg = d1 < 0f || d2 < 0f || d3 < 0f
        val hasPos = d1 > 0f || d2 > 0f || d3 > 0f
        return !(hasNeg && hasPos)
    }

    private fun triSign(p1x: Float, p1z: Float, p2x: Float, p2z: Float, p3x: Float, p3z: Float): Float =
        (p1x - p3x) * (p2z - p3z) - (p2x - p3x) * (p1z - p3z)

    // ── GLTF JSON ─────────────────────────────────────────────────────────────

    private fun buildGltfJson(
        binLen: Int,
        wallVerts: Int, wallIdxCount: Int,
        wallPosOff: Int, wallPosLen: Int,
        wallNormOff: Int, wallNormLen: Int,
        wallIdxOff: Int, wallIdxLen: Int,
        hasFloor: Boolean,
        floorVerts: Int, floorIdxCount: Int,
        floorPosOff: Int, floorPosLen: Int,
        floorNormOff: Int, floorNormLen: Int,
        floorIdxOff: Int, floorIdxLen: Int,
        min: FloatArray, max: FloatArray
    ): String {
        val minS = "[${min[0]},${min[1]},${min[2]}]"
        val maxS = "[${max[0]},${max[1]},${max[2]}]"

        val primitives = buildString {
            append("""{"attributes":{"POSITION":0,"NORMAL":1},"indices":2,"material":0,"mode":4}""")
            if (hasFloor) append(""",{"attributes":{"POSITION":3,"NORMAL":4},"indices":5,"material":1,"mode":4}""")
        }

        val materials = """[{"name":"Wall","pbrMetallicRoughness":{"baseColorFactor":[0.88,0.88,0.90,1.0],"metallicFactor":0.05,"roughnessFactor":0.85},"doubleSided":true},{"name":"Floor","pbrMetallicRoughness":{"baseColorFactor":[0.60,0.60,0.62,1.0],"metallicFactor":0.02,"roughnessFactor":0.95},"doubleSided":false}]"""

        val accessors = buildString {
            // Muri
            append("""{"bufferView":0,"byteOffset":0,"componentType":5126,"count":$wallVerts,"type":"VEC3","min":$minS,"max":$maxS}""")
            append(""",{"bufferView":1,"byteOffset":0,"componentType":5126,"count":$wallVerts,"type":"VEC3"}""")
            append(""",{"bufferView":2,"byteOffset":0,"componentType":5123,"count":$wallIdxCount,"type":"SCALAR"}""")
            // Pavimento
            if (hasFloor) {
                append(""",{"bufferView":3,"byteOffset":0,"componentType":5126,"count":$floorVerts,"type":"VEC3"}""")
                append(""",{"bufferView":4,"byteOffset":0,"componentType":5126,"count":$floorVerts,"type":"VEC3"}""")
                append(""",{"bufferView":5,"byteOffset":0,"componentType":5123,"count":$floorIdxCount,"type":"SCALAR"}""")
            }
        }

        val bufferViews = buildString {
            append("""{"buffer":0,"byteOffset":$wallPosOff,"byteLength":$wallPosLen,"target":34962}""")
            append(""",{"buffer":0,"byteOffset":$wallNormOff,"byteLength":$wallNormLen,"target":34962}""")
            append(""",{"buffer":0,"byteOffset":$wallIdxOff,"byteLength":$wallIdxLen,"target":34963}""")
            if (hasFloor) {
                append(""",{"buffer":0,"byteOffset":$floorPosOff,"byteLength":$floorPosLen,"target":34962}""")
                append(""",{"buffer":0,"byteOffset":$floorNormOff,"byteLength":$floorNormLen,"target":34962}""")
                append(""",{"buffer":0,"byteOffset":$floorIdxOff,"byteLength":$floorIdxLen,"target":34963}""")
            }
        }

        return """{"asset":{"version":"2.0","generator":"HubAgency ARCore v1.1"},"scene":0,"scenes":[{"name":"Room","nodes":[0]}],"nodes":[{"name":"Room","mesh":0}],"meshes":[{"name":"Room","primitives":[$primitives]}],"materials":$materials,"accessors":[$accessors],"bufferViews":[$bufferViews],"buffers":[{"byteLength":$binLen}]}"""
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun floatsToBytes(floats: FloatArray): ByteArray {
        if (floats.isEmpty()) return ByteArray(0)
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        if (shorts.isEmpty()) return ByteArray(0)
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
