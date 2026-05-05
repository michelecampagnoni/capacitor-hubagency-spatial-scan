import Foundation

struct LinkedOpeningSpec {
    let sourceRoomId:   String
    let sourceRoomName: String
    let kind:           OpeningKind
    let width:          Float
    let height:         Float
    let bottom:         Float

    func toDictionary() -> [String: Any] {
        [
            "sourceRoomId":   sourceRoomId,
            "sourceRoomName": sourceRoomName,
            "kind":           kind.rawValue,
            "width":          width,
            "height":         height,
            "bottom":         bottom
        ]
    }

    static func fromDictionary(_ d: [String: Any]) -> LinkedOpeningSpec? {
        guard
            let id    = d["sourceRoomId"]   as? String,
            let name  = d["sourceRoomName"] as? String,
            let kStr  = d["kind"]           as? String,
            let kind  = OpeningKind(rawValue: kStr),
            let w     = d["width"]          as? Float,
            let h     = d["height"]         as? Float,
            let b     = d["bottom"]         as? Float
        else { return nil }
        return LinkedOpeningSpec(sourceRoomId: id, sourceRoomName: name,
                                 kind: kind, width: w, height: h, bottom: b)
    }
}
