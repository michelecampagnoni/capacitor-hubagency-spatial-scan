import Foundation

struct RoomRecord: Codable {
    let id:            String
    let name:          String
    let timestamp:     Int64
    let area:          Double
    let height:        Double
    let wallCount:     Int
    let openingCount:  Int
    let floorPlanPath: String?
    let glbPath:       String?
    let openings:      [OpeningMetadata]

    init(id: String, name: String, timestamp: Int64, area: Double, height: Double,
         wallCount: Int, openingCount: Int, floorPlanPath: String?, glbPath: String?,
         openings: [OpeningMetadata] = []) {
        self.id = id; self.name = name; self.timestamp = timestamp
        self.area = area; self.height = height
        self.wallCount = wallCount; self.openingCount = openingCount
        self.floorPlanPath = floorPlanPath; self.glbPath = glbPath
        self.openings = openings
    }
}
