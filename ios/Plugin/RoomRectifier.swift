import Foundation
import simd

enum RoomRectifier {

    // ── Costanti (invarianti assolute — identiche al Kotlin) ──────
    static let snapHardDeg: Float = 5.0
    static let minEdgeM:    Float = 0.20

    struct Result {
        let polygon:      [SIMD3<Float>]
        let changed:      Bool
        let snappedCount: Int
        let totalEdges:   Int
    }

    static func rectify(_ polygon: [SIMD3<Float>]) -> Result {
        let n = polygon.count
        guard n >= 3 else { return Result(polygon: polygon, changed: false, snappedCount: 0, totalEdges: 0) }

        // ── 1. Edge geometry ──────────────────────────────────────
        var angles  = [Float](repeating: 0, count: n)
        var lengths = [Float](repeating: 0, count: n)
        for i in 0 ..< n {
            let a = polygon[i]; let b = polygon[(i + 1) % n]
            let dx = b.x - a.x; let dz = b.z - a.z
            lengths[i] = sqrt(dx * dx + dz * dz)
            angles[i]  = atan2(dz, dx)
        }

        // ── 2. Dominant axis = longest edge ───────────────────────
        let longestIdx   = lengths.indices.max(by: { lengths[$0] < lengths[$1] }) ?? 0
        let dominantAxis = angles[longestIdx]

        // ── 3. Snap eligible edges ────────────────────────────────
        let snapRad      = snapHardDeg * .pi / 180
        var snappedAngles = angles
        var snappedCount  = 0

        for i in 0 ..< n {
            guard lengths[i] >= minEdgeM else { continue }
            let nearest = nearestAxisAngle(angles[i], dominantAxis: dominantAxis)
            let dev     = angularDeviation(angles[i], nearest)
            if dev <= snapRad {
                snappedAngles[i] = nearest
                if dev > 1e-5 { snappedCount += 1 }
            }
        }

        guard snappedCount > 0 else {
            return Result(polygon: polygon, changed: false, snappedCount: 0, totalEdges: n)
        }

        // ── 4. Reconstruct from v[0] ──────────────────────────────
        var newX = [Float](repeating: 0, count: n)
        var newZ = [Float](repeating: 0, count: n)
        newX[0] = polygon[0].x; newZ[0] = polygon[0].z
        for i in 0 ..< n - 1 {
            newX[i + 1] = newX[i] + cos(snappedAngles[i]) * lengths[i]
            newZ[i + 1] = newZ[i] + sin(snappedAngles[i]) * lengths[i]
        }

        // ── 5. Distribute closure error linearly ──────────────────
        let closeX = newX[n - 1] + cos(snappedAngles[n - 1]) * lengths[n - 1]
        let closeZ = newZ[n - 1] + sin(snappedAngles[n - 1]) * lengths[n - 1]
        let errX   = polygon[0].x - closeX
        let errZ   = polygon[0].z - closeZ
        for i in 0 ..< n {
            let t = Float(i) / Float(n)
            newX[i] += errX * t
            newZ[i] += errZ * t
        }

        let rectified = (0 ..< n).map { i in
            SIMD3<Float>(newX[i], polygon[i].y, newZ[i])
        }
        return Result(polygon: rectified, changed: true, snappedCount: snappedCount, totalEdges: n)
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static func nearestAxisAngle(_ raw: Float, dominantAxis: Float) -> Float {
        let quarterPi = Float.pi / 4
        let candidates = (0 ..< 8).map { k -> Float in
            var a = dominantAxis + Float(k) * quarterPi
            while a >  Float.pi { a -= 2 * .pi }
            while a < -Float.pi { a += 2 * .pi }
            return a
        }
        return candidates.min(by: { angularDeviation(raw, $0) < angularDeviation(raw, $1) }) ?? raw
    }

    private static func angularDeviation(_ a: Float, _ b: Float) -> Float {
        var d = abs(a - b)
        if d > Float.pi { d = 2 * .pi - d }
        return d
    }
}
