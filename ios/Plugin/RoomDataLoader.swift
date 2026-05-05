import Foundation

/**
 * Ricostruisce RoomExportData dai file JSON salvati nel device.
 * Port 1:1 di RoomDataLoader.kt.
 *
 * Legge hub_rooms.json + hub_room_{id}.json, applica i world transforms
 * dal CompositionGraph e produce RoomExportData in world space.
 * Usato da SpatialScanPlugin.exportPdf() senza bisogno di un ViewController attivo.
 */
enum RoomDataLoader {

    // MARK: – Public entry points

    /// Ricostruisce RoomExportData usando `anchorRoomId` come radice della BFS.
    /// Usa sempre il componente connesso dell'ancora, non il primo record in assoluto.
    static func buildExportData(anchorRoomId: String) -> RoomExportData? {
        let roomRecords = RoomHistoryManager.shared.loadAll()
        guard !roomRecords.isEmpty else { return nil }

        let componentIds = CompositionGraph.shared.getComponentRoomIds(roomId: anchorRoomId)
        // Se nessun edge nel grafo ancora, esporta solo questa stanza
        let ids     = componentIds.isEmpty ? [anchorRoomId] : componentIds
        let roomMap = Dictionary(uniqueKeysWithValues: roomRecords.map { ($0.id, $0) })
        NSLog("[HUB_DIAG] RoomDataLoader anchorRoomId=%@ componentIds=[%@]",
              anchorRoomId, ids.joined(separator: ", "))

        var allWalls    = [ExportWall]()
        var allPolygons = [(String, [(Float, Float)])]()

        for roomId in ids {
            guard let json = RoomHistoryManager.shared.loadRoomData(id: roomId) else {
                NSLog("[HUB_DIAG] RoomDataLoader: no JSON for roomId=%@ — skipped", roomId)
                continue
            }
            let worldT = CompositionGraph.shared.getTransform(roomId: roomId)
            let walls  = parseWalls(json)
            if let wt = worldT {
                allWalls.append(contentsOf: transformWalls(walls,
                    ox: wt.worldOffsetX, oz: wt.worldOffsetZ, rot: wt.worldRotRad))
            } else {
                allWalls.append(contentsOf: walls)
            }

            let name     = roomMap[roomId]?.name ?? String(roomId.prefix(6))
            let rawPoly  = parsePolygon(json)
            let worldPoly: [(Float, Float)]
            if let wt = worldT {
                let c = cos(wt.worldRotRad); let s = sin(wt.worldRotRad)
                worldPoly = rawPoly.map { (x, z) in
                    (x * c - z * s + wt.worldOffsetX, x * s + z * c + wt.worldOffsetZ)
                }
            } else {
                worldPoly = rawPoly
            }
            allPolygons.append((name, worldPoly))
        }

        guard !allWalls.isEmpty else {
            NSLog("[HUB_DIAG] RoomDataLoader: no walls found for anchor=%@", anchorRoomId)
            return nil
        }

        let relevantRecords = ids.compactMap { roomMap[$0] }
        let totalArea = relevantRecords.reduce(0.0) { $0 + $1.area }
        let allX = allWalls.flatMap { [Double($0.startX), Double($0.endX)] }
        let allZ = allWalls.flatMap { [Double($0.startZ), Double($0.endZ)] }
        let dims = RoomDimensions(
            width:     max((allX.max() ?? 0) - (allX.min() ?? 0), 0),
            length:    max((allZ.max() ?? 0) - (allZ.min() ?? 0), 0),
            height:    Double(allWalls.map { $0.height }.max() ?? 2.5),
            area:      totalArea,
            perimeter: 0
        )
        NSLog("[HUB_DIAG] RoomDataLoader: built — walls=%d rooms=%d area=%.1f",
              allWalls.count, allPolygons.count, totalArea)
        return RoomExportData(walls: allWalls, dimensions: dims, roomPolygons: allPolygons)
    }

    /// Wrapper: usa il primo record salvato come ancora (chiamata da exportPdf senza contesto Composer).
    static func buildExportData() -> RoomExportData? {
        let roomRecords = RoomHistoryManager.shared.loadAll()
        guard !roomRecords.isEmpty else { return nil }
        return buildExportData(anchorRoomId: roomRecords.first!.id)
    }

    // MARK: – Parsing

    private static func parseWalls(_ json: [String: Any]) -> [ExportWall] {
        guard let wallsArr = json["walls"] as? [[String: Any]] else { return [] }
        return wallsArr.enumerated().compactMap { (i, w) in
            guard let sp = w["startPoint"] as? [String: Any],
                  let ep = w["endPoint"]   as? [String: Any] else { return nil }
            let sx = (sp["x"] as? NSNumber)?.floatValue ?? 0
            let sz = (sp["z"] as? NSNumber)?.floatValue ?? 0
            let ex = (ep["x"] as? NSNumber)?.floatValue ?? 0
            let ez = (ep["z"] as? NSNumber)?.floatValue ?? 0
            let dx = ex - sx; let dz = ez - sz
            let len = max(sqrt(dx*dx + dz*dz), 1e-6)
            let h   = (w["height"] as? NSNumber)?.floatValue ?? 2.5

            let openings: [ExportOpening]
            if let opsArr = w["openings"] as? [[String: Any]] {
                openings = opsArr.compactMap { o in
                    let kind = OpeningKind(rawValue: (o["kind"] as? String) ?? "") ?? .door
                    return ExportOpening(
                        kind:            kind,
                        offsetAlongWall: (o["offsetAlongWall"] as? NSNumber)?.floatValue ?? 0,
                        width:           (o["width"]           as? NSNumber)?.floatValue ?? 0.8,
                        bottom:          (o["bottom"]          as? NSNumber)?.floatValue ?? 0,
                        height:          (o["height"]          as? NSNumber)?.floatValue ?? 2.1
                    )
                }
            } else {
                openings = []
            }

            let wid = (w["id"] as? String) ?? "w\(i)"
            return ExportWall(
                id:      wid,
                startX:  sx, startZ: sz, endX: ex, endZ: ez,
                length:  len, height: h,
                normalX: dz / len, normalZ: -(dx / len),
                dirX:    dx / len, dirZ:     dz / len,
                openings: openings
            )
        }
    }

    private static func parsePolygon(_ json: [String: Any]) -> [(Float, Float)] {
        guard let floorDict = json["floor"] as? [String: Any],
              let verts = floorDict["vertices"] as? [[String: Any]] else { return [] }
        return verts.compactMap { v in
            guard let x = (v["x"] as? NSNumber)?.floatValue,
                  let z = (v["z"] as? NSNumber)?.floatValue else { return nil }
            return (x, z)
        }
    }

    private static func transformWalls(_ walls: [ExportWall],
                                       ox: Float, oz: Float, rot: Float) -> [ExportWall] {
        let c = cos(rot); let s = sin(rot)
        return walls.map { w in
            let sx = w.startX * c - w.startZ * s + ox
            let sz = w.startX * s + w.startZ * c + oz
            let ex = w.endX   * c - w.endZ   * s + ox
            let ez = w.endX   * s + w.endZ   * c + oz
            let dx = ex - sx; let dz = ez - sz
            let len = max(sqrt(dx*dx + dz*dz), 1e-6)
            return ExportWall(
                id:      w.id,
                startX:  sx, startZ: sz, endX: ex, endZ: ez,
                length:  len, height: w.height,
                normalX: dz / len, normalZ: -(dx / len),
                dirX:    dx / len, dirZ:     dz / len,
                openings: w.openings
            )
        }
    }
}
