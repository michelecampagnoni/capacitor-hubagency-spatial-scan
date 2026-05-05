import Foundation

/// Persistenza locale dello storico stanze.
/// Porta iOS di RoomHistoryManager.kt.
///
/// Formato hub_room_{id}.json:
///   { "success", "roomId", "name", "walls": [{id, startPoint:{x,z}, endPoint:{x,z}, height,
///     openings:[{id, kind, offsetAlongWall, width, height, bottom, isInternal,
///               connectionStatus, linkedRoomId, connectionLabel}]}],
///     "floor": {"vertices":[{x,z}], "area"},
///     "roomDimensions": {width, length, height, area, perimeter} }
///
/// Formato hub_rooms.json: [RoomRecord] (Codable array)
final class RoomHistoryManager {

    static let shared = RoomHistoryManager()
    private init() {}

    private let filename = "hub_rooms.json"

    private var docsDir: URL? {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
    }

    // MARK: - Save

    /// Salva la stanza: hub_room_{id}.json + aggiorna hub_rooms.json.
    /// roomData: dizionario con chiavi walls, floor, roomDimensions (formato Composer).
    func save(roomData: [String: Any], name: String) -> RoomRecord? {
        guard let docsDir = docsDir else { return nil }
        let id = UUID().uuidString

        let walls = roomData["walls"] as? [[String: Any]] ?? []
        let floor  = roomData["floor"] as? [String: Any]
        let dims   = roomData["roomDimensions"] as? [String: Any]

        var openingCount = 0
        var openingMetas = [OpeningMetadata]()
        for wall in walls {
            let wallId = wall["id"] as? String ?? ""
            let ops    = wall["openings"] as? [[String: Any]] ?? []
            openingCount += ops.count
            for op in ops {
                let statusStr  = op["connectionStatus"] as? String ?? "EXTERNAL"
                let status     = ConnectionStatus(rawValue: statusStr) ?? .external
                openingMetas.append(OpeningMetadata(
                    openingId:        op["id"] as? String ?? "",
                    wallId:           wallId,
                    isInternal:       op["isInternal"] as? Bool ?? false,
                    linkedRoomId:     (op["linkedRoomId"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                    connectionLabel:  (op["connectionLabel"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                    connectionStatus: status
                ))
            }
        }

        let area     = (dims?["area"]   as? Double) ?? (floor?["area"] as? Double) ?? 0.0
        let height   = (dims?["height"] as? Double) ?? 0.0
        let wallCnt  = walls.count

        let record = RoomRecord(
            id:            id,
            name:          name.trimmingCharacters(in: .whitespaces).isEmpty ? "Stanza" : name,
            timestamp:     Int64(Date().timeIntervalSince1970 * 1000),
            area:          area,
            height:        height,
            wallCount:     wallCnt,
            openingCount:  openingCount,
            floorPlanPath: roomData["floorPlanPath"] as? String,
            glbPath:       roomData["glbPath"] as? String,
            openings:      openingMetas
        )

        // hub_room_{id}.json
        var full = roomData
        full["roomId"] = id
        full["success"] = true
        if let data = try? JSONSerialization.data(withJSONObject: full) {
            try? data.write(to: docsDir.appendingPathComponent("hub_room_\(id).json"))
        }

        // hub_rooms.json (più recente in cima)
        var rooms = loadAll()
        rooms.insert(record, at: 0)
        if let data = try? JSONEncoder().encode(rooms) {
            try? data.write(to: docsDir.appendingPathComponent(filename))
        }

        return record
    }

    // MARK: - Load

    func loadAll() -> [RoomRecord] {
        guard let docsDir = docsDir,
              let data    = try? Data(contentsOf: docsDir.appendingPathComponent(filename)),
              let records = try? JSONDecoder().decode([RoomRecord].self, from: data)
        else { return [] }
        return records
    }

    func loadRoomData(id: String) -> [String: Any]? {
        guard let docsDir = docsDir,
              let data    = try? Data(contentsOf: docsDir.appendingPathComponent("hub_room_\(id).json")),
              let obj     = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return obj
    }

    // MARK: - Update opening metadata (bilateral link)

    func updateOpeningMetadata(roomId: String, openingId: String,
                               linkedRoomId: String, linkedRoomName: String) {
        guard let docsDir = docsDir else { return }

        // 1. Aggiorna hub_room_{id}.json
        if var roomData = loadRoomData(id: roomId),
           var walls = roomData["walls"] as? [[String: Any]] {
            for wi in 0..<walls.count {
                if var ops = walls[wi]["openings"] as? [[String: Any]] {
                    for oi in 0..<ops.count {
                        if ops[oi]["id"] as? String == openingId {
                            ops[oi]["isInternal"]       = true
                            ops[oi]["connectionStatus"] = ConnectionStatus.linked.rawValue
                            ops[oi]["linkedRoomId"]     = linkedRoomId
                            ops[oi]["connectionLabel"]  = linkedRoomName
                        }
                    }
                    walls[wi]["openings"] = ops
                }
            }
            roomData["walls"] = walls
            if let data = try? JSONSerialization.data(withJSONObject: roomData) {
                try? data.write(to: docsDir.appendingPathComponent("hub_room_\(roomId).json"))
            }
        }

        // 2. Aggiorna hub_rooms.json
        var rooms = loadAll()
        if let idx = rooms.firstIndex(where: { $0.id == roomId }) {
            var openings = rooms[idx].openings
            let wallId: String
            if let oidx = openings.firstIndex(where: { $0.openingId == openingId }) {
                wallId = openings[oidx].wallId
                openings[oidx] = OpeningMetadata(
                    openingId: openingId, wallId: wallId,
                    isInternal: true, linkedRoomId: linkedRoomId,
                    connectionLabel: linkedRoomName, connectionStatus: .linked)
            } else {
                wallId = ""
                openings.append(OpeningMetadata(
                    openingId: openingId, wallId: wallId,
                    isInternal: true, linkedRoomId: linkedRoomId,
                    connectionLabel: linkedRoomName, connectionStatus: .linked))
            }
            let r = rooms[idx]
            rooms[idx] = RoomRecord(id: r.id, name: r.name, timestamp: r.timestamp,
                                    area: r.area, height: r.height,
                                    wallCount: r.wallCount, openingCount: r.openingCount,
                                    floorPlanPath: r.floorPlanPath, glbPath: r.glbPath,
                                    openings: openings)
            if let data = try? JSONEncoder().encode(rooms) {
                try? data.write(to: docsDir.appendingPathComponent(filename))
            }
        }
    }

    // MARK: - Delete

    func delete(id: String) {
        guard let docsDir = docsDir else { return }
        let rooms = loadAll().filter { $0.id != id }
        if let data = try? JSONEncoder().encode(rooms) {
            try? data.write(to: docsDir.appendingPathComponent(filename))
        }
        try? FileManager.default.removeItem(at: docsDir.appendingPathComponent("hub_room_\(id).json"))
    }

    // MARK: - Clear session

    func clearAll() {
        guard let docsDir = docsDir else { return }
        let fm = FileManager.default
        if let items = try? fm.contentsOfDirectory(at: docsDir, includingPropertiesForKeys: nil) {
            for url in items {
                let name = url.lastPathComponent
                if name == "hub_rooms.json" || name == "hub_graph.json" ||
                   name == "hub_unlinked_openings.json" || name.hasPrefix("hub_room_") {
                    try? fm.removeItem(at: url)
                }
            }
        }
    }
}
