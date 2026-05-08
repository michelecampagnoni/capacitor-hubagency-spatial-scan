import Foundation

/**
 * Genera un file GLB (binary GLTF 2.0) dalla geometria della stanza.
 * Port 1:1 di GlbExporter.kt.
 *
 * Struttura del modello:
 *   Primitiva 0 — Muri (material "Wall", grigio chiaro [0.88,0.88,0.90])
 *     Ogni parete è suddivisa in quad attorno alle aperture.
 *     Attributi: POSITION + NORMAL (normale perpendicolare al piano del muro).
 *   Primitiva 1 — Pavimento (material "Floor", grigio scuro [0.60,0.60,0.62])
 *     Ear-clipping del poligono stanza a Y=0.
 *     Attributi: POSITION + NORMAL (0,1,0).
 *
 * Layout buffer binario (non-interleaved):
 *   [wall positions][wall normals][wall indices][floor positions][floor normals][floor indices]
 *   ogni sezione allineata a 4 byte.
 *
 * Guard UInt16: se i vertici totali superano UInt16.max (65535) la funzione fallisce
 * restituendo nil in modo esplicito.
 */
enum GlbExporter {

    private static let MAGIC_GLTF:   UInt32 = 0x46546C67
    private static let GLTF_VERSION: UInt32 = 2
    private static let CHUNK_JSON:   UInt32 = 0x4E4F534A
    private static let CHUNK_BIN:    UInt32 = 0x004E4942

    // MARK: – Public API

    static func export(data: RoomExportData, cacheDir: URL) -> String? {
        guard !data.walls.isEmpty else { return nil }

        let allQuads = data.walls.flatMap { wallQuads($0) }
        guard !allQuads.isEmpty else { return nil }

        // ── Wall geometry ──────────────────────────────────────────────────────
        let wallPos  = buildWallPositions(allQuads)
        let wallNorm = buildWallNormals(allQuads)
        let wallIdx  = buildWallIndices(allQuads.count)

        // ── Floor geometry ─────────────────────────────────────────────────────
        let floorPolys = data.roomPolygons.map { $0.1 }.filter { $0.count >= 3 }
        let hasFloor   = !floorPolys.isEmpty
        let floorPos   = hasFloor ? buildFloorPositions(floorPolys) : [Float]()
        let floorNorm  = hasFloor ? buildFloorNormals(floorPos.count / 3) : [Float]()

        // Guard UInt16 vertex limit
        let totalVerts = wallPos.count / 3 + floorPos.count / 3
        guard totalVerts <= Int(UInt16.max) else { return nil }

        let floorIdx: [UInt16]
        if hasFloor {
            guard let idx = buildFloorIndices(floorPolys) else { return nil }
            floorIdx = idx
        } else {
            floorIdx = []
        }

        // ── Pack binary buffer ─────────────────────────────────────────────────
        let wallPosB   = floatsToBytes(wallPos)
        let wallNormB  = floatsToBytes(wallNorm)
        let wallIdxB   = shortsToBytes(wallIdx)
        let floorPosB  = floatsToBytes(floorPos)
        let floorNormB = floatsToBytes(floorNorm)
        let floorIdxB  = shortsToBytes(floorIdx)

        let off0 = 0
        let off1 = align4(off0 + wallPosB.count)
        let off2 = align4(off1 + wallNormB.count)
        let off3 = align4(off2 + wallIdxB.count)
        let off4 = align4(off3 + floorPosB.count)
        let off5 = align4(off4 + floorNormB.count)
        let binLen = align4(off5 + floorIdxB.count)

        var binBuf = [UInt8](repeating: 0, count: binLen)
        wallPosB.enumerated().forEach  { binBuf[off0 + $0.offset] = $0.element }
        wallNormB.enumerated().forEach { binBuf[off1 + $0.offset] = $0.element }
        wallIdxB.enumerated().forEach  { binBuf[off2 + $0.offset] = $0.element }
        if hasFloor {
            floorPosB.enumerated().forEach  { binBuf[off3 + $0.offset] = $0.element }
            floorNormB.enumerated().forEach { binBuf[off4 + $0.offset] = $0.element }
            floorIdxB.enumerated().forEach  { binBuf[off5 + $0.offset] = $0.element }
        }

        // ── Bounding box (walls only) ──────────────────────────────────────────
        var minX = Float.greatestFiniteMagnitude
        var minY = Float.greatestFiniteMagnitude
        var minZ = Float.greatestFiniteMagnitude
        var maxX = -Float.greatestFiniteMagnitude
        var maxY = -Float.greatestFiniteMagnitude
        var maxZ = -Float.greatestFiniteMagnitude
        var i = 0
        while i < wallPos.count {
            let x = wallPos[i]; let y = wallPos[i+1]; let z = wallPos[i+2]
            if x < minX { minX = x }; if x > maxX { maxX = x }
            if y < minY { minY = y }; if y > maxY { maxY = y }
            if z < minZ { minZ = z }; if z > maxZ { maxZ = z }
            i += 3
        }

        let json = buildGltfJson(
            binLen:       binLen,
            wallVerts:    wallPos.count / 3, wallIdxCount: wallIdx.count,
            wallPosOff:   off0, wallPosLen:   wallPosB.count,
            wallNormOff:  off1, wallNormLen:  wallNormB.count,
            wallIdxOff:   off2, wallIdxLen:   wallIdxB.count,
            hasFloor:     hasFloor,
            floorVerts:   floorPos.count / 3, floorIdxCount: floorIdx.count,
            floorPosOff:  off3, floorPosLen:  floorPosB.count,
            floorNormOff: off4, floorNormLen: floorNormB.count,
            floorIdxOff:  off5, floorIdxLen:  floorIdxB.count,
            min: [minX, minY, minZ], max: [maxX, maxY, maxZ]
        )

        let jsonPadded = pad4(Array(json.utf8), padByte: 0x20)
        let binPadded  = pad4(binBuf,           padByte: 0x00)

        let totalLen = 12 + 8 + jsonPadded.count + 8 + binPadded.count
        var out = [UInt8](repeating: 0, count: totalLen)
        var cursor = 0

        func writeU32(_ v: UInt32) {
            out[cursor]   = UInt8(v & 0xFF)
            out[cursor+1] = UInt8((v >> 8)  & 0xFF)
            out[cursor+2] = UInt8((v >> 16) & 0xFF)
            out[cursor+3] = UInt8((v >> 24) & 0xFF)
            cursor += 4
        }
        func writeBytes(_ b: [UInt8]) { for byte in b { out[cursor] = byte; cursor += 1 } }

        writeU32(MAGIC_GLTF);  writeU32(GLTF_VERSION); writeU32(UInt32(totalLen))
        writeU32(UInt32(jsonPadded.count)); writeU32(CHUNK_JSON); writeBytes(jsonPadded)
        writeU32(UInt32(binPadded.count));  writeU32(CHUNK_BIN);  writeBytes(binPadded)

        let file = cacheDir.appendingPathComponent(
            "scan_\(Int(Date().timeIntervalSince1970 * 1000)).glb")
        do { try Data(out).write(to: file); return file.path } catch { return nil }
    }

    // MARK: – Wall geometry

    private struct Quad {
        let x0: Float, z0: Float, x1: Float, z1: Float
        let yBase: Float, yTop: Float
        let nx: Float, nz: Float
    }

    /**
     * Divide un ExportWall in quad, ritagliando le aperture.
     * Segmenti solidi → quad full-height.
     * Aperture → davanzale (finestre) + architrave sopra.
     */
    private static func wallQuads(_ wall: ExportWall) -> [Quad] {
        var quads = [Quad]()
        let wallLen = wall.length
        let wallH   = wall.height

        if wall.openings.isEmpty {
            quads.append(Quad(x0: wall.startX, z0: wall.startZ,
                              x1: wall.endX,   z1: wall.endZ,
                              yBase: 0, yTop: wallH,
                              nx: wall.normalX, nz: wall.normalZ))
            return quads
        }

        let sorted = wall.openings.sorted { $0.offsetAlongWall < $1.offsetAlongWall }
        var cursor: Float = 0

        for o in sorted {
            let t0 = max(0, min(o.offsetAlongWall,           wallLen))
            let t1 = max(0, min(o.offsetAlongWall + o.width, wallLen))
            if t0 > cursor { quads.append(segmentQuad(wall, cursor, t0, 0, wallH)) }
            let obBottom = max(0, min(o.bottom,            wallH))
            let obTop    = max(0, min(o.bottom + o.height, wallH))
            if obBottom > 0   { quads.append(segmentQuad(wall, t0, t1, 0,        obBottom)) }
            if obTop < wallH  { quads.append(segmentQuad(wall, t0, t1, obTop,    wallH))    }
            cursor = max(cursor, t1)
        }
        if cursor < wallLen { quads.append(segmentQuad(wall, cursor, wallLen, 0, wallH)) }
        return quads
    }

    private static func segmentQuad(_ wall: ExportWall,
                                     _ t0: Float, _ t1: Float,
                                     _ yBase: Float, _ yTop: Float) -> Quad {
        Quad(x0: wall.startX + wall.dirX * t0, z0: wall.startZ + wall.dirZ * t0,
             x1: wall.startX + wall.dirX * t1, z1: wall.startZ + wall.dirZ * t1,
             yBase: yBase, yTop: yTop,
             nx: wall.normalX, nz: wall.normalZ)
    }

    /** 4 vertici per quad: v0=start-bottom, v1=end-bottom, v2=end-top, v3=start-top */
    private static func buildWallPositions(_ quads: [Quad]) -> [Float] {
        var out = [Float](); out.reserveCapacity(quads.count * 12)
        for q in quads {
            out += [q.x0, q.yBase, q.z0,
                    q.x1, q.yBase, q.z1,
                    q.x1, q.yTop,  q.z1,
                    q.x0, q.yTop,  q.z0]
        }
        return out
    }

    /** Normale (nx, 0, nz) identica per tutti i 4 vertici del quad. */
    private static func buildWallNormals(_ quads: [Quad]) -> [Float] {
        var out = [Float](); out.reserveCapacity(quads.count * 12)
        for q in quads { for _ in 0..<4 { out += [q.nx, 0, q.nz] } }
        return out
    }

    /** 2 triangoli CCW per quad: (0,1,2) e (0,2,3). */
    private static func buildWallIndices(_ quadCount: Int) -> [UInt16] {
        var out = [UInt16](); out.reserveCapacity(quadCount * 6)
        for q in 0..<quadCount {
            let b = UInt16(q * 4)
            out += [b, b+1, b+2, b, b+2, b+3]
        }
        return out
    }

    // MARK: – Floor geometry

    private static func buildFloorPositions(_ polys: [[(Float, Float)]]) -> [Float] {
        var out = [Float]()
        for poly in polys { for (x, z) in poly { out += [x, 0, z] } }
        return out
    }

    private static func buildFloorNormals(_ vertCount: Int) -> [Float] {
        var out = [Float](); out.reserveCapacity(vertCount * 3)
        for _ in 0..<vertCount { out += [0, 1, 0] }
        return out
    }

    /**
     * Ear-clipping triangulation per ogni poligono del pavimento.
     * Restituisce nil se un qualsiasi indice supera UInt16.max.
     */
    private static func buildFloorIndices(_ polys: [[(Float, Float)]]) -> [UInt16]? {
        var allTriangles = [(Int, Int, Int)]()
        var base = 0
        for poly in polys {
            if poly.count < 3 { base += poly.count; continue }
            let local = earClip(poly)
            for (a, b, c) in local { allTriangles.append((base + a, base + b, base + c)) }
            base += poly.count
        }
        let maxIdx = allTriangles.flatMap { [$0.0, $0.1, $0.2] }.max() ?? 0
        guard maxIdx <= Int(UInt16.max) else { return nil }
        var out = [UInt16]()
        for (a, b, c) in allTriangles { out += [UInt16(a), UInt16(b), UInt16(c)] }
        return out
    }

    /**
     * Ear-clipping su un singolo poligono (XZ).
     * Restituisce indici locali 0..n-1 come Triple CCW rispetto a Y-up (normale (0,1,0)).
     */
    private static func earClip(_ poly: [(Float, Float)]) -> [(Int, Int, Int)] {
        let n = poly.count
        if n == 3 { return [earWindingCorrect(poly, 0, 1, 2)] }

        // Shoelace: area positiva → poligono CCW in XZ (Y-up)
        var area: Double = 0
        for i in 0..<n {
            let j = (i + 1) % n
            area += Double(poly[i].0) * Double(poly[j].1)
            area -= Double(poly[j].0) * Double(poly[i].1)
        }
        let polyCCW = area >= 0

        var idx = Array(0..<n)
        var result = [(Int, Int, Int)]()

        while idx.count > 3 {
            var earFound = false
            for i in 0..<idx.count {
                let iPrev = (i - 1 + idx.count) % idx.count
                let iNext = (i + 1) % idx.count
                let a = idx[iPrev]; let b = idx[i]; let c = idx[iNext]

                let ax = poly[a].0; let az = poly[a].1
                let bx = poly[b].0; let bz = poly[b].1
                let cx = poly[c].0; let cz = poly[c].1

                let cross = (bx - ax) * (cz - az) - (bz - az) * (cx - ax)
                let triCCW = cross > 0
                if triCCW != polyCCW { continue }

                var inside = false
                for j in 0..<idx.count {
                    if j == iPrev || j == i || j == iNext { continue }
                    let px = poly[idx[j]].0; let pz = poly[idx[j]].1
                    if pointInTriangle(px, pz, ax, az, bx, bz, cx, cz) { inside = true; break }
                }
                if inside { continue }

                result.append(earWindingCorrect(poly, a, b, c))
                idx.remove(at: i)
                earFound = true
                break
            }
            if !earFound { break }
        }
        if idx.count == 3 { result.append(earWindingCorrect(poly, idx[0], idx[1], idx[2])) }
        return result
    }

    /** Garantisce winding CW in XZ = front face dall'alto per normale Y-up = (0,1,0).
     *  In GLTF Y-up: CW in XZ → prodotto vettoriale 3D punta verso +Y → fronte visibile
     *  quando la camera è sopra il pavimento (doubleSided: false). */
    private static func earWindingCorrect(_ poly: [(Float, Float)],
                                          _ a: Int, _ b: Int, _ c: Int) -> (Int, Int, Int) {
        let cross = (poly[b].0 - poly[a].0) * (poly[c].1 - poly[a].1)
                  - (poly[b].1 - poly[a].1) * (poly[c].0 - poly[a].0)
        return cross >= 0 ? (a, c, b) : (a, b, c)
    }

    /** Test punto-in-triangolo (piano XZ). */
    private static func pointInTriangle(_ px: Float, _ pz: Float,
                                        _ ax: Float, _ az: Float,
                                        _ bx: Float, _ bz: Float,
                                        _ cx: Float, _ cz: Float) -> Bool {
        func triSign(_ p1x: Float, _ p1z: Float,
                     _ p2x: Float, _ p2z: Float,
                     _ p3x: Float, _ p3z: Float) -> Float {
            (p1x - p3x) * (p2z - p3z) - (p2x - p3x) * (p1z - p3z)
        }
        let d1 = triSign(px, pz, ax, az, bx, bz)
        let d2 = triSign(px, pz, bx, bz, cx, cz)
        let d3 = triSign(px, pz, cx, cz, ax, az)
        let hasNeg = d1 < 0 || d2 < 0 || d3 < 0
        let hasPos = d1 > 0 || d2 > 0 || d3 > 0
        return !(hasNeg && hasPos)
    }

    // MARK: – GLTF JSON

    private static func buildGltfJson(
        binLen: Int,
        wallVerts: Int, wallIdxCount: Int,
        wallPosOff: Int, wallPosLen: Int,
        wallNormOff: Int, wallNormLen: Int,
        wallIdxOff: Int, wallIdxLen: Int,
        hasFloor: Bool,
        floorVerts: Int, floorIdxCount: Int,
        floorPosOff: Int, floorPosLen: Int,
        floorNormOff: Int, floorNormLen: Int,
        floorIdxOff: Int, floorIdxLen: Int,
        min: [Float], max: [Float]
    ) -> String {
        let minS = "[\(min[0]),\(min[1]),\(min[2])]"
        let maxS = "[\(max[0]),\(max[1]),\(max[2])]"

        var primitives = """
            {"attributes":{"POSITION":0,"NORMAL":1},"indices":2,"material":0,"mode":4}
            """
        if hasFloor {
            primitives += ",{\"attributes\":{\"POSITION\":3,\"NORMAL\":4},\"indices\":5,\"material\":1,\"mode\":4}"
        }

        let materials =
            "[{\"name\":\"Wall\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[0.88,0.88,0.90,1.0],\"metallicFactor\":0.05,\"roughnessFactor\":0.85},\"doubleSided\":true}," +
            "{\"name\":\"Floor\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[0.60,0.60,0.62,1.0],\"metallicFactor\":0.02,\"roughnessFactor\":0.95},\"doubleSided\":false}]"

        var accessors =
            "{\"bufferView\":0,\"byteOffset\":0,\"componentType\":5126,\"count\":\(wallVerts),\"type\":\"VEC3\",\"min\":\(minS),\"max\":\(maxS)}," +
            "{\"bufferView\":1,\"byteOffset\":0,\"componentType\":5126,\"count\":\(wallVerts),\"type\":\"VEC3\"}," +
            "{\"bufferView\":2,\"byteOffset\":0,\"componentType\":5123,\"count\":\(wallIdxCount),\"type\":\"SCALAR\"}"
        if hasFloor {
            accessors +=
                ",{\"bufferView\":3,\"byteOffset\":0,\"componentType\":5126,\"count\":\(floorVerts),\"type\":\"VEC3\"}" +
                ",{\"bufferView\":4,\"byteOffset\":0,\"componentType\":5126,\"count\":\(floorVerts),\"type\":\"VEC3\"}" +
                ",{\"bufferView\":5,\"byteOffset\":0,\"componentType\":5123,\"count\":\(floorIdxCount),\"type\":\"SCALAR\"}"
        }

        var bufferViews =
            "{\"buffer\":0,\"byteOffset\":\(wallPosOff),\"byteLength\":\(wallPosLen),\"target\":34962}," +
            "{\"buffer\":0,\"byteOffset\":\(wallNormOff),\"byteLength\":\(wallNormLen),\"target\":34962}," +
            "{\"buffer\":0,\"byteOffset\":\(wallIdxOff),\"byteLength\":\(wallIdxLen),\"target\":34963}"
        if hasFloor {
            bufferViews +=
                ",{\"buffer\":0,\"byteOffset\":\(floorPosOff),\"byteLength\":\(floorPosLen),\"target\":34962}" +
                ",{\"buffer\":0,\"byteOffset\":\(floorNormOff),\"byteLength\":\(floorNormLen),\"target\":34962}" +
                ",{\"buffer\":0,\"byteOffset\":\(floorIdxOff),\"byteLength\":\(floorIdxLen),\"target\":34963}"
        }

        return
            "{\"asset\":{\"version\":\"2.0\",\"generator\":\"HubAgency ARKit v1.1\"}," +
            "\"scene\":0,\"scenes\":[{\"name\":\"Room\",\"nodes\":[0]}]," +
            "\"nodes\":[{\"name\":\"Room\",\"mesh\":0}]," +
            "\"meshes\":[{\"name\":\"Room\",\"primitives\":[\(primitives)]}]," +
            "\"materials\":\(materials)," +
            "\"accessors\":[\(accessors)]," +
            "\"bufferViews\":[\(bufferViews)]," +
            "\"buffers\":[{\"byteLength\":\(binLen)}]}"
    }

    // MARK: – Binary utilities

    private static func floatsToBytes(_ floats: [Float]) -> [UInt8] {
        guard !floats.isEmpty else { return [] }
        var out = [UInt8](repeating: 0, count: floats.count * 4)
        for (i, f) in floats.enumerated() {
            let bits = f.bitPattern
            out[i*4]   = UInt8(bits & 0xFF)
            out[i*4+1] = UInt8((bits >> 8)  & 0xFF)
            out[i*4+2] = UInt8((bits >> 16) & 0xFF)
            out[i*4+3] = UInt8((bits >> 24) & 0xFF)
        }
        return out
    }

    private static func shortsToBytes(_ shorts: [UInt16]) -> [UInt8] {
        guard !shorts.isEmpty else { return [] }
        var out = [UInt8](repeating: 0, count: shorts.count * 2)
        for (i, s) in shorts.enumerated() {
            out[i*2]   = UInt8(s & 0xFF)
            out[i*2+1] = UInt8((s >> 8) & 0xFF)
        }
        return out
    }

    private static func align4(_ n: Int) -> Int { (n + 3) & ~3 }

    private static func pad4(_ data: [UInt8], padByte: UInt8) -> [UInt8] {
        let padded = align4(data.count)
        if padded == data.count { return data }
        return data + [UInt8](repeating: padByte, count: padded - data.count)
    }
}
