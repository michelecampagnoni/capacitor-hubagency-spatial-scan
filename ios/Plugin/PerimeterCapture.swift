import Foundation
import simd

class PerimeterCapture {

    enum State { case idle, capturing, closed }

    enum CapturePhase {
        case awaitFirstFloor
        case awaitHeight
        case awaitSecondFloor
        case floorOnly
    }

    // ── Costanti (invarianti assolute — identiche al Kotlin) ──────
    static let snapDeg:          Float = 90.0
    static let snapRad:          Float = snapDeg * .pi / 180
    static let snapThresholdDeg: Float = 0.0    // snap disabilitato
    static let snapThresholdRad: Float = snapThresholdDeg * .pi / 180
    static let lenQuantM:        Float = 0.05
    static let minSegM:          Float = 0.15
    static let minHeightM:       Float = 1.50

    // ── Stato ─────────────────────────────────────────────────────
    private(set) var state: State = .idle
    private(set) var capturedHeight: Float?
    private var confirmed = [SIMD3<Float>]()

    var capturePhase: CapturePhase {
        switch confirmed.count {
        case 0:  return .awaitFirstFloor
        case 1 where capturedHeight == nil: return .awaitHeight
        case 1:  return .awaitSecondFloor
        default: return .floorOnly
        }
    }

    var pointCount: Int     { confirmed.count }
    var canClose:   Bool    { confirmed.count >= 3 && state == .capturing }
    var canUndo:    Bool    { !confirmed.isEmpty && state == .capturing }

    // ── API pubblica ──────────────────────────────────────────────

    @discardableResult
    func addPoint(_ raw: SIMD3<Float>) -> Bool {
        guard state != .closed else { return false }

        switch capturePhase {
        case .awaitFirstFloor:
            confirmed.append(raw)
            state = .capturing
            return true

        case .awaitHeight:
            capturedHeight = max(raw.y, Self.minHeightM)
            return true

        case .awaitSecondFloor, .floorOnly:
            let prev    = confirmed.last!
            let snapped = snapFull(raw, prev: prev)
            let dist    = distXZ(snapped, prev)
            guard dist >= Self.minSegM else { return false }
            confirmed.append(snapped)
            return true
        }
    }

    func livePreview(_ raw: SIMD3<Float>) -> SIMD3<Float>? {
        guard !confirmed.isEmpty, capturePhase != .awaitHeight else { return nil }
        return snapAngleOnly(raw, prev: confirmed.last!)
    }

    @discardableResult
    func close() -> Bool {
        guard canClose else { return false }
        state = .closed
        return true
    }

    func undo() {
        guard canUndo else { return }
        if capturedHeight != nil && confirmed.count == 1 {
            capturedHeight = nil
        } else {
            confirmed.removeLast()
            if confirmed.isEmpty { state = .idle }
        }
    }

    func reset() {
        confirmed.removeAll()
        capturedHeight = nil
        state = .idle
    }

    func getPolygon() -> [SIMD3<Float>] { confirmed }

    func axisDirections() -> (base: SIMD2<Float>, wallDir: SIMD2<Float>, perpDir: SIMD2<Float>)? {
        guard confirmed.count >= 2 else { return nil }
        let a  = confirmed[confirmed.count - 2]
        let b  = confirmed.last!
        let dx = b.x - a.x
        let dz = b.z - a.z
        let len = sqrt(dx * dx + dz * dz)
        guard len >= 0.01 else { return nil }
        let nx = dx / len; let nz = dz / len
        return (
            base:    SIMD2(b.x, b.z),
            wallDir: SIMD2(nx, nz),
            perpDir: SIMD2(-nz, nx)
        )
    }

    func lastSegmentLength() -> Float? {
        guard confirmed.count >= 2 else { return nil }
        return distXZ(confirmed[confirmed.count - 2], confirmed.last!)
    }

    // ── Snap logic ────────────────────────────────────────────────

    private func snapFull(_ raw: SIMD3<Float>, prev: SIMD3<Float>) -> SIMD3<Float> {
        let angle  = computeSnappedAngle(raw, prev: prev)
        let rawLen = distXZ(raw, prev)
        let len    = max(rawLen, Self.minSegM)
        return SIMD3(prev.x + cos(angle) * len, raw.y, prev.z + sin(angle) * len)
    }

    private func snapAngleOnly(_ raw: SIMD3<Float>, prev: SIMD3<Float>) -> SIMD3<Float> {
        let angle  = computeSnappedAngle(raw, prev: prev)
        let rawLen = distXZ(raw, prev)
        return SIMD3(prev.x + cos(angle) * rawLen, raw.y, prev.z + sin(angle) * rawLen)
    }

    private func computeSnappedAngle(_ raw: SIMD3<Float>, prev: SIMD3<Float>) -> Float {
        let rawAngle = atan2(raw.z - prev.z, raw.x - prev.x)

        if confirmed.count >= 2 {
            let a = confirmed[confirmed.count - 2]
            let b = confirmed.last!
            let prevSeg  = atan2(b.z - a.z, b.x - a.x)
            let relAngle = normalizeAngle(rawAngle - prevSeg)
            let nearest  = roundTo(relAngle, step: Self.snapRad)
            let diff     = abs(normalizeAngle(relAngle - nearest))
            return diff < Self.snapThresholdRad ? prevSeg + nearest : rawAngle
        } else {
            let nearest = roundTo(rawAngle, step: Self.snapRad)
            let diff    = abs(normalizeAngle(rawAngle - nearest))
            return diff < Self.snapThresholdRad ? nearest : rawAngle
        }
    }

    // ── Utility ───────────────────────────────────────────────────

    private func distXZ(_ a: SIMD3<Float>, _ b: SIMD3<Float>) -> Float {
        let dx = b.x - a.x; let dz = b.z - a.z
        return sqrt(dx * dx + dz * dz)
    }

    private func roundTo(_ value: Float, step: Float) -> Float {
        (value / step).rounded() * step
    }

    private func normalizeAngle(_ a: Float) -> Float {
        var r = a
        while r >  Float.pi { r -= 2 * .pi }
        while r < -Float.pi { r += 2 * .pi }
        return r
    }
}
