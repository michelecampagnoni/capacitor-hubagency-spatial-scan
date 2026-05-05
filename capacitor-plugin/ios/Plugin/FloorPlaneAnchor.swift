import ARKit

class FloorPlaneAnchor {

    private var samples       = [Float]()       // buffer max 60 campioni
    private var lockedFloorY: Float?
    private var trackingStreak = 0              // frame consecutivi TRACKING

    var isLocked: Bool { lockedFloorY != nil }

    func update(frame: ARFrame, isTracking: Bool) -> Float {
        let cameraY = frame.camera.transform.columns.3.y

        guard isTracking else {
            trackingStreak = 0
            return lockedFloorY ?? (cameraY - 1.2)
        }
        trackingStreak += 1

        let planes = frame.anchors.compactMap { $0 as? ARPlaneAnchor }
        let horizontalPlanes = planes.filter { $0.alignment == .horizontal }
        let floor: ARPlaneAnchor? = horizontalPlanes.max(by: {
            ($0.extent.x * $0.extent.z) < ($1.extent.x * $1.extent.z)
        })
        let raw: Float = floor.map { Float($0.transform.columns.3.y) } ?? (cameraY - 1.2)

        if trackingStreak > 5 {
            if samples.count >= 60 { samples.removeFirst() }
            samples.append(raw)
        }

        if lockedFloorY == nil && samples.count >= 30 && trackingStreak >= 30 {
            let med      = median(samples)
            let variance = samples.map { ($0 - med) * ($0 - med) }.reduce(0, +) / Float(samples.count)
            if variance < 0.0005 {
                lockedFloorY = med
            }
        }

        if let locked = lockedFloorY {
            let med = median(samples)
            if abs(med - locked) > 0.05 {
                lockedFloorY = locked * 0.998 + med * 0.002
            }
        }

        return lockedFloorY ?? raw
    }

    func reset() {
        samples.removeAll()
        lockedFloorY = nil
        trackingStreak = 0
    }

    private func median(_ list: [Float]) -> Float {
        let sorted = list.sorted()
        let n = sorted.count
        return n % 2 == 0 ? (sorted[n / 2 - 1] + sorted[n / 2]) / 2 : sorted[n / 2]
    }
}
