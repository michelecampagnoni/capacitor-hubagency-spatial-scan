import Foundation

/// Registro persistente delle aperture interne non ancora collegate bilateralmente.
/// Porta iOS di UnlinkedOpeningStore.kt.
/// Persistenza: Documents/hub_unlinked_openings.json
struct UnlinkedOpening {
    let id:             String   // UUID entry nel registro
    var sourceRoomId:   String   // UUID del RoomRecord sorgente (popolato dopo il save)
    let sourceRoomName: String   // nome leggibile ("Corridoio")
    let openingId:      String   // OpeningModel.id nell'ambiente sorgente
    let kind:           OpeningKind
    let width:          Float
    let height:         Float
    let bottom:         Float
    let wallIndex:      Int
    let customLabel:    String

    func withSourceRoomId(_ newId: String) -> UnlinkedOpening {
        UnlinkedOpening(id: id, sourceRoomId: newId, sourceRoomName: sourceRoomName,
                        openingId: openingId, kind: kind, width: width, height: height,
                        bottom: bottom, wallIndex: wallIndex, customLabel: customLabel)
    }

    func toDict() -> [String: Any] {
        ["id": id, "sourceRoomId": sourceRoomId, "sourceRoomName": sourceRoomName,
         "openingId": openingId, "kind": kind.rawValue,
         "width": width, "height": height, "bottom": bottom,
         "wallIndex": wallIndex, "customLabel": customLabel]
    }

    static func fromDict(_ d: [String: Any]) -> UnlinkedOpening? {
        guard let id            = d["id"]             as? String,
              let sourceRoomId  = d["sourceRoomId"]   as? String,
              let sourceRoomName = d["sourceRoomName"] as? String,
              let openingId     = d["openingId"]       as? String,
              let kindStr       = d["kind"]            as? String,
              let kind          = OpeningKind(rawValue: kindStr) else { return nil }
        return UnlinkedOpening(
            id:             id,
            sourceRoomId:   sourceRoomId,
            sourceRoomName: sourceRoomName,
            openingId:      openingId,
            kind:           kind,
            width:          (d["width"]       as? NSNumber)?.floatValue ?? 0.80,
            height:         (d["height"]      as? NSNumber)?.floatValue ?? 2.10,
            bottom:         (d["bottom"]      as? NSNumber)?.floatValue ?? 0.00,
            wallIndex:      (d["wallIndex"]   as? Int) ?? 0,
            customLabel:    (d["customLabel"] as? String) ?? ""
        )
    }
}

final class UnlinkedOpeningStore {

    static let shared = UnlinkedOpeningStore()
    private init() {}

    private let filename = "hub_unlinked_openings.json"

    func add(_ entry: UnlinkedOpening) {
        var list = loadAll()
        list.append(entry)
        writeAll(list)
    }

    func remove(id: String) {
        let list = loadAll().filter { $0.id != id }
        writeAll(list)
    }

    func loadAll() -> [UnlinkedOpening] {
        guard let url  = fileURL,
              let data = try? Data(contentsOf: url),
              let arr  = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else { return [] }
        return arr.compactMap { UnlinkedOpening.fromDict($0) }
    }

    func clear() {
        writeAll([])
    }

    private func writeAll(_ list: [UnlinkedOpening]) {
        guard let url  = fileURL,
              let data = try? JSONSerialization.data(withJSONObject: list.map { $0.toDict() })
        else { return }
        try? data.write(to: url)
    }

    private var fileURL: URL? {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?
            .appendingPathComponent(filename)
    }
}
