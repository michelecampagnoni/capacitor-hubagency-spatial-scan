import Foundation

/// World transform di una stanza nel sistema di riferimento della root della sua componente.
/// La root non ha entry nel grafo — il suo transform è identità (0, 0, 0).
/// Porta iOS di RoomWorldTransform / CompositionGraph.kt.
struct RoomWorldTransform {
    let roomId:       String
    let parentId:     String
    let worldOffsetX: Float
    let worldOffsetZ: Float
    let worldRotRad:  Float
    let confirmedAt:  Int64

    func toDict() -> [String: Any] {
        ["roomId":       roomId,
         "parentId":     parentId,
         "worldOffsetX": worldOffsetX,
         "worldOffsetZ": worldOffsetZ,
         "worldRotRad":  worldRotRad,
         "confirmedAt":  confirmedAt]
    }

    static func fromDict(_ d: [String: Any]) -> RoomWorldTransform? {
        guard let roomId   = d["roomId"]   as? String,
              let parentId = d["parentId"] as? String else { return nil }
        return RoomWorldTransform(
            roomId:       roomId,
            parentId:     parentId,
            worldOffsetX: (d["worldOffsetX"] as? NSNumber)?.floatValue ?? 0,
            worldOffsetZ: (d["worldOffsetZ"] as? NSNumber)?.floatValue ?? 0,
            worldRotRad:  (d["worldRotRad"]  as? NSNumber)?.floatValue ?? 0,
            confirmedAt:  (d["confirmedAt"]  as? NSNumber)?.int64Value ?? 0
        )
    }
}

/// Grafo di composizione planimetrica multi-stanza.
/// Struttura: foresta di alberi (parentId → childId).
/// La root di ogni albero è il primo ambiente scansionato nella componente.
/// Persistenza: Documents/hub_graph.json
final class CompositionGraph {

    static let shared = CompositionGraph()
    private init() {}

    private let filename = "hub_graph.json"

    // MARK: - API pubblica

    func addTransform(_ t: RoomWorldTransform) {
        var list = loadAll()
        if let idx = list.firstIndex(where: { $0.roomId == t.roomId }) {
            list[idx] = t
        } else {
            list.append(t)
        }
        writeAll(list)
    }

    func getTransform(roomId: String) -> RoomWorldTransform? {
        loadAll().first { $0.roomId == roomId }
    }

    func getRootId(roomId: String) -> String {
        let byRoom = Dictionary(uniqueKeysWithValues: loadAll().map { ($0.roomId, $0) })
        var current = roomId
        var visited = Set<String>()
        while true {
            guard visited.insert(current).inserted else { break }
            guard let parent = byRoom[current]?.parentId else { break }
            current = parent
        }
        return current
    }

    /// BFS bidirezionale — restituisce tutti i roomId nella stessa componente connessa.
    func getComponentRoomIds(roomId: String) -> [String] {
        let all = loadAll()
        var adj = [String: [String]]()
        for t in all {
            adj[t.parentId, default: []].append(t.roomId)
            adj[t.roomId, default: []].append(t.parentId)
        }
        var visited = Set<String>()
        var queue   = [roomId]
        visited.insert(roomId)
        var i = 0
        while i < queue.count {
            let cur = queue[i]; i += 1
            for neighbor in adj[cur] ?? [] {
                if visited.insert(neighbor).inserted { queue.append(neighbor) }
            }
        }
        return Array(visited)
    }

    func removeTransform(roomId: String) {
        let list = loadAll().filter { $0.roomId != roomId }
        writeAll(list)
    }

    func getChildIds(parentId: String) -> [String] {
        loadAll().filter { $0.parentId == parentId }.map { $0.roomId }
    }

    func loadAll() -> [RoomWorldTransform] {
        guard let url  = fileURL,
              let data = try? Data(contentsOf: url),
              let obj  = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let nodes = obj["nodes"] as? [[String: Any]]
        else { return [] }
        return nodes.compactMap { RoomWorldTransform.fromDict($0) }
    }

    func clear() {
        writeAll([])
    }

    // MARK: - Internals

    private func writeAll(_ list: [RoomWorldTransform]) {
        guard let url  = fileURL else { return }
        let obj: [String: Any] = ["nodes": list.map { $0.toDict() }]
        if let data = try? JSONSerialization.data(withJSONObject: obj) {
            try? data.write(to: url)
        }
    }

    private var fileURL: URL? {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?
            .appendingPathComponent(filename)
    }
}
