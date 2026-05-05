import Foundation

enum ConnectionStatus: String, Codable {
    case external = "EXTERNAL"
    case pending  = "PENDING"
    case linked   = "LINKED"
}

struct OpeningMetadata: Codable {
    let openingId:        String
    let wallId:           String
    let isInternal:       Bool
    let linkedRoomId:     String?
    let connectionLabel:  String?
    let connectionStatus: ConnectionStatus

    init(openingId: String, wallId: String, isInternal: Bool,
         linkedRoomId: String?, connectionLabel: String?,
         connectionStatus: ConnectionStatus? = nil) {
        self.openingId       = openingId
        self.wallId          = wallId
        self.isInternal      = isInternal
        self.linkedRoomId    = linkedRoomId
        self.connectionLabel = connectionLabel
        self.connectionStatus = connectionStatus ?? {
            if linkedRoomId != nil { return .linked }
            if isInternal          { return .pending }
            return .external
        }()
    }
}
