import Foundation

enum OpeningKind: String, Codable {
    case door        = "DOOR"
    case window      = "WINDOW"
    case frenchDoor  = "FRENCH_DOOR"

    var defaultWidth:  Float { switch self { case .door: 0.80; case .window: 1.20; case .frenchDoor: 1.20 } }
    var defaultBottom: Float { switch self { case .door: 0.00; case .window: 0.90; case .frenchDoor: 0.00 } }
    var defaultHeight: Float { switch self { case .door: 2.10; case .window: 1.20; case .frenchDoor: 2.20 } }
    var label:         String { switch self { case .door: "Porta"; case .window: "Finestra"; case .frenchDoor: "Portafinestra" } }
}

struct OpeningModel: Identifiable {
    let id:    String
    let wallId: String
    let kind:  OpeningKind
    var offsetAlongWall: Float
    var width:  Float
    var bottom: Float
    var height: Float
}

struct WallModel: Identifiable {
    let id:        String
    let start:     SIMD3<Float>
    let end:       SIMD3<Float>
    let thickness: Float
    let height:    Float
    var openings:  [OpeningModel]

    init(id: String, start: SIMD3<Float>, end: SIMD3<Float>, height: Float,
         thickness: Float = 0.20, openings: [OpeningModel] = []) {
        self.id = id; self.start = start; self.end = end
        self.height = height; self.thickness = thickness; self.openings = openings
    }

    var length: Float {
        let d = end - start
        return sqrt(d.x * d.x + d.z * d.z)
    }
    var dirX: Float { let l = length; return l > 1e-6 ? (end.x - start.x) / l : 0 }
    var dirZ: Float { let l = length; return l > 1e-6 ? (end.z - start.z) / l : 0 }

    func openingCenter(_ o: OpeningModel) -> SIMD3<Float> {
        let t = o.offsetAlongWall + o.width / 2
        return SIMD3(start.x + dirX * t, 0, start.z + dirZ * t)
    }

    mutating func clampOpening(_ o: inout OpeningModel, minMargin: Float = 0.10) {
        let maxOffset = max(length - o.width - minMargin, minMargin)
        o.offsetAlongWall = min(max(o.offsetAlongWall, minMargin), maxOffset)
    }
}

struct RoomModel {
    let floorPolygon: [SIMD3<Float>]
    let wallHeight:   Float
    var walls:        [WallModel]

    static func fromPolygon(_ polygon: [SIMD3<Float>], wallHeight: Float) -> RoomModel {
        let walls = polygon.indices.map { i -> WallModel in
            let a = polygon[i]
            let b = polygon[(i + 1) % polygon.count]
            return WallModel(
                id: "w\(i)",
                start: SIMD3(a.x, 0, a.z),
                end:   SIMD3(b.x, 0, b.z),
                height: wallHeight
            )
        }
        return RoomModel(floorPolygon: polygon, wallHeight: wallHeight, walls: walls)
    }
}
