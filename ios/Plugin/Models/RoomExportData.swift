import Foundation

struct RoomDimensions {
    let width:     Double
    let length:    Double
    let height:    Double
    let area:      Double
    let perimeter: Double
}

struct ExportOpening {
    let kind:            OpeningKind
    let offsetAlongWall: Float
    let width:           Float
    let bottom:          Float
    let height:          Float
}

struct ExportWall {
    let id:       String
    let startX:   Float
    let startZ:   Float
    let endX:     Float
    let endZ:     Float
    let length:   Float
    let height:   Float
    let normalX:  Float
    let normalZ:  Float
    let dirX:     Float
    let dirZ:     Float
    let openings: [ExportOpening]
}

struct RoomExportData {
    let walls:        [ExportWall]
    let dimensions:   RoomDimensions
    let roomPolygons: [(String, [(Float, Float)])]

    init(walls: [ExportWall], dimensions: RoomDimensions,
         roomPolygons: [(String, [(Float, Float)])] = []) {
        self.walls = walls
        self.dimensions = dimensions
        self.roomPolygons = roomPolygons
    }

    static func fromRoomModel(_ rm: RoomModel) -> RoomExportData {
        let exportWalls = rm.walls.map { wm -> ExportWall in
            ExportWall(
                id: wm.id,
                startX: wm.start.x, startZ: wm.start.z,
                endX:   wm.end.x,   endZ:   wm.end.z,
                length: wm.length, height: wm.height,
                normalX: wm.dirZ, normalZ: -wm.dirX,
                dirX: wm.dirX, dirZ: wm.dirZ,
                openings: wm.openings.map { o in
                    ExportOpening(kind: o.kind, offsetAlongWall: o.offsetAlongWall,
                                  width: o.width, bottom: o.bottom, height: o.height)
                }
            )
        }
        let dims = computeDimensions(exportWalls, wallHeight: rm.wallHeight)
        return RoomExportData(walls: exportWalls, dimensions: dims)
    }

    private static func computeDimensions(_ walls: [ExportWall], wallHeight: Float) -> RoomDimensions {
        guard !walls.isEmpty else {
            return RoomDimensions(width: 0, length: 0, height: Double(wallHeight), area: 0, perimeter: 0)
        }
        let allX = walls.flatMap { [Double($0.startX), Double($0.endX)] }
        let allZ = walls.flatMap { [Double($0.startZ), Double($0.endZ)] }
        let width     = max(allX.max()! - allX.min()!, 0)
        let length    = max(allZ.max()! - allZ.min()!, 0)
        let perimeter = walls.reduce(0.0) { $0 + Double($1.length) } * 0.5
        let area      = shoelace(walls)
        return RoomDimensions(width: width, length: length,
                              height: Double(wallHeight), area: area, perimeter: perimeter)
    }

    private static func shoelace(_ walls: [ExportWall]) -> Double {
        walls.reduce(0.0) { sum, w in
            sum + Double(w.startX) * Double(w.endZ) - Double(w.endX) * Double(w.startZ)
        }.magnitude / 2.0
    }
}
