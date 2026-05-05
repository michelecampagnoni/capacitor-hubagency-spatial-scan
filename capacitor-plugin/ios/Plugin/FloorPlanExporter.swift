import UIKit
import Foundation

/**
 * Genera planimetrie PNG e PDF A3 (vista dall'alto) dai dati spaziali delle pareti.
 * Port 1:1 di FloorPlanExporter.kt.
 *
 * Entrambi i formati usano lo stesso metodo draw() su contesto UIKit (UIGraphicsImageRenderer
 * per PNG, UIGraphicsPDFRenderer per PDF). All'interno si usano UIBezierPath per i percorsi
 * e NSString/NSAttributedString per il testo, garantendo output identico nei due formati.
 */
enum FloorPlanExporter {

    private static let BMP_SIZE = 1200   // PNG quadrato
    private static let PDF_W    = 1748   // A3 portrait 150dpi
    private static let PDF_H    = 2480

    /** Contesto di rendering: dimensioni canvas + scala font/stroke. */
    private struct RC {
        let W: Int, H: Int
        let margin: CGFloat    // margine sx/dx/basso
        let marginTop: CGFloat // margine superiore (header)
        let fs: CGFloat        // scala font/stroke: 1.0 = base 1200px
    }

    private static func makeRC(_ W: Int, _ H: Int) -> RC {
        let base = CGFloat(min(W, H))
        let m    = max(base * 0.10, 80)
        return RC(W: W, H: H, margin: m, marginTop: m * 1.25, fs: base / 1200)
    }

    // MARK: – Public API

    static func export(data: RoomExportData, cacheDir: URL) -> String? {
        guard !data.walls.isEmpty else { return nil }
        let r = makeRC(BMP_SIZE, BMP_SIZE)
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: BMP_SIZE, height: BMP_SIZE))
        let image = renderer.image { _ in draw(r: r, data: data) }
        guard let pngData = image.pngData() else { return nil }
        let file = cacheDir.appendingPathComponent(
            "floorplan_\(Int(Date().timeIntervalSince1970 * 1000)).png")
        do { try pngData.write(to: file); return file.path } catch { return nil }
    }

    static func exportPdf(data: RoomExportData, cacheDir: URL) -> String? {
        guard !data.walls.isEmpty else { return nil }
        let r = makeRC(PDF_W, PDF_H)
        let pageRect = CGRect(x: 0, y: 0, width: PDF_W, height: PDF_H)
        let renderer = UIGraphicsPDFRenderer(bounds: pageRect)
        let file = cacheDir.appendingPathComponent(
            "floorplan_a3_\(Int(Date().timeIntervalSince1970 * 1000)).pdf")
        do {
            try renderer.writePDF(to: file) { ctx in
                ctx.beginPage()
                draw(r: r, data: data)
            }
            return file.path
        } catch { return nil }
    }

    // MARK: – Core draw (shared by PNG and PDF)

    private static func draw(r: RC, data: RoomExportData) {
        UIColor.white.setFill()
        UIBezierPath(rect: CGRect(x: 0, y: 0, width: r.W, height: r.H)).fill()

        let roomDim = data.dimensions

        // ── Auto-alignment rotation (min bounding box) ───────────────────────
        let alignRot = computeAlignmentRotation(data.walls)
        let ca = cos(alignRot); let sa = sin(alignRot)
        func rp(_ x: Float, _ z: Float) -> (Float, Float) {
            (x * ca - z * sa, x * sa + z * ca)
        }

        let walls: [ExportWall] = data.walls.map { w in
            let (sx, sz) = rp(w.startX, w.startZ)
            let (ex, ez) = rp(w.endX,   w.endZ)
            let dx = ex - sx; let dz = ez - sz
            let len = max(sqrt(dx*dx + dz*dz), 1e-6)
            return ExportWall(id: w.id,
                              startX: sx, startZ: sz, endX: ex, endZ: ez,
                              length: len, height: w.height,
                              normalX:  dz / len, normalZ: -(dx / len),
                              dirX:     dx / len, dirZ:     dz / len,
                              openings: w.openings)
        }
        let roomPolygons: [(String, [(Float, Float)])] = data.roomPolygons.map { (name, poly) in
            (name, poly.map { rp($0.0, $0.1) })
        }

        let allX  = walls.flatMap { [CGFloat($0.startX), CGFloat($0.endX)] }
        let allZ  = walls.flatMap { [CGFloat($0.startZ), CGFloat($0.endZ)] }
        let minX  = allX.min()!; let maxX = allX.max()!
        let minZ  = allZ.min()!; let maxZ = allZ.max()!
        let rangeX = max(maxX - minX, 0.1)
        let rangeZ = max(maxZ - minZ, 0.1)

        let drawAreaX = CGFloat(r.W) - r.margin * 2
        let drawAreaZ = CGFloat(r.H) - r.marginTop - r.margin
        let scale     = min(drawAreaX / rangeX, drawAreaZ / rangeZ)
        let originX   = r.margin    + (drawAreaX - rangeX * scale) / 2
        let originZ   = r.marginTop + (drawAreaZ - rangeZ * scale) / 2

        func toPixX(_ x: Float) -> CGFloat { originX + (CGFloat(x) - minX) * scale }
        func toPixZ(_ z: Float) -> CGFloat { originZ + (CGFloat(z) - minZ) * scale }

        let allPxX = walls.flatMap { [toPixX($0.startX), toPixX($0.endX)] }
        let allPxZ = walls.flatMap { [toPixZ($0.startZ), toPixZ($0.endZ)] }
        let centroidPx = CGPoint(
            x: allPxX.reduce(0, +) / CGFloat(allPxX.count),
            y: allPxZ.reduce(0, +) / CGFloat(allPxZ.count)
        )

        // Anti-collision list: pre-registra header e bottom bar
        var placed = [CGRect]()
        placed.append(CGRect(x: 0, y: 0, width: CGFloat(r.W), height: r.marginTop))
        placed.append(CGRect(x: 0, y: CGFloat(r.H) - r.margin, width: CGFloat(r.W), height: r.margin))

        drawGrid(walls: walls, r: r,
                 x0pix: originX, z0pix: originZ,
                 x1pix: originX + rangeX * scale, z1pix: originZ + rangeZ * scale,
                 minX: minX, maxX: maxX, minZ: minZ, maxZ: maxZ,
                 toPixX: toPixX, toPixZ: toPixZ)
        drawFloorFill(walls: walls, r: r, toPixX: toPixX, toPixZ: toPixZ)
        drawWalls(walls: walls, r: r, toPixX: toPixX, toPixZ: toPixZ)
        drawRoomLabels(roomPolygons: roomPolygons, placed: &placed, r: r,
                       toPixX: toPixX, toPixZ: toPixZ)
        drawWallQuotes(walls: walls, centroid: centroidPx, placed: &placed, r: r,
                       toPixX: toPixX, toPixZ: toPixZ)
        drawDimensionLabels(roomDim: roomDim, minX: minX, maxX: maxX, minZ: minZ, maxZ: maxZ,
                            r: r, toPixX: toPixX, toPixZ: toPixZ)
        drawHeader(walls: walls, roomDim: roomDim, r: r)
        drawScaleBar(scale: scale, r: r)
        drawBranding(r: r)
    }

    // MARK: – Alignment rotation

    private static func computeAlignmentRotation(_ walls: [ExportWall]) -> Float {
        // Approccio dominant-axis: media circolare ponderata per lunghezza dei muri,
        // con angoli ripiegati a [0, π/2) — perché muri perpendicolari hanno lo stesso asse.
        // Più robusto del bounding-box per composizioni multi-stanza con orientamenti diversi:
        // la stanza con più muri/più lunga determina l'asse di allineamento.
        guard !walls.isEmpty else { return 0 }
        var sumSin: Float = 0
        var sumCos: Float = 0
        for w in walls {
            let dx = w.endX - w.startX; let dz = w.endZ - w.startZ
            let len = sqrt(dx * dx + dz * dz)
            guard len > 0.05 else { continue }
            // Piega l'angolo in [0, π/2) — assi perpendicolari collassano sullo stesso valore
            var angle = atan2(dz, dx)
            angle = angle.truncatingRemainder(dividingBy: .pi / 2)
            if angle < 0 { angle += .pi / 2 }
            // Media circolare nel periodo [0, π/2) usando il trucco 4× angolo
            sumSin += sin(4 * angle) * len
            sumCos += cos(4 * angle) * len
        }
        guard sumSin != 0 || sumCos != 0 else { return 0 }
        // Angolo dominante in [0, π/2), poi negato per allineare il piano agli assi
        var dominant = atan2(sumSin, sumCos) / 4
        if dominant < 0 { dominant += .pi / 2 }
        return -dominant
    }

    // MARK: – Drawing helpers

    private static func drawGrid(walls: [ExportWall], r: RC,
                                 x0pix: CGFloat, z0pix: CGFloat,
                                 x1pix: CGFloat, z1pix: CGFloat,
                                 minX: CGFloat, maxX: CGFloat,
                                 minZ: CGFloat, maxZ: CGFloat,
                                 toPixX: (Float) -> CGFloat,
                                 toPixZ: (Float) -> CGFloat) {
        let color = UIColor(red: 0, green: 60/255, blue: 200/255, alpha: 35/255)
        color.setStroke()
        let pattern: [CGFloat] = [6, 8]

        var gx = floor(Double(minX))
        while CGFloat(gx) <= maxX + 0.01 {
            let px = toPixX(Float(gx))
            if px >= x0pix && px <= x1pix {
                let path = UIBezierPath()
                path.move(to: CGPoint(x: px, y: z0pix))
                path.addLine(to: CGPoint(x: px, y: z1pix))
                path.lineWidth = r.fs
                path.setLineDash(pattern, count: pattern.count, phase: 0)
                path.stroke()
            }
            gx += 1
        }
        var gz = floor(Double(minZ))
        while CGFloat(gz) <= maxZ + 0.01 {
            let pz = toPixZ(Float(gz))
            if pz >= z0pix && pz <= z1pix {
                let path = UIBezierPath()
                path.move(to: CGPoint(x: x0pix, y: pz))
                path.addLine(to: CGPoint(x: x1pix, y: pz))
                path.lineWidth = r.fs
                path.setLineDash(pattern, count: pattern.count, phase: 0)
                path.stroke()
            }
            gz += 1
        }
    }

    private static func drawFloorFill(walls: [ExportWall], r: RC,
                                      toPixX: (Float) -> CGFloat,
                                      toPixZ: (Float) -> CGFloat) {
        var pts = [CGPoint]()
        for w in walls {
            pts.append(CGPoint(x: toPixX(w.startX), y: toPixZ(w.startZ)))
            pts.append(CGPoint(x: toPixX(w.endX),   y: toPixZ(w.endZ)))
        }
        guard pts.count >= 3 else { return }
        let hull = convexHull(pts)
        guard hull.count >= 3 else { return }

        let path = UIBezierPath()
        path.move(to: hull[0])
        for pt in hull.dropFirst() { path.addLine(to: pt) }
        path.close()

        UIColor(red: 80/255, green: 140/255, blue: 255/255, alpha: 30/255).setFill()
        path.fill()

        UIColor(red: 40/255, green: 100/255, blue: 220/255, alpha: 80/255).setStroke()
        path.lineWidth = 1.5 * r.fs
        let dashPat: [CGFloat] = [10, 6]
        path.setLineDash(dashPat, count: dashPat.count, phase: 0)
        path.stroke()
    }

    private static func drawWalls(walls: [ExportWall], r: RC,
                                  toPixX: (Float) -> CGFloat,
                                  toPixZ: (Float) -> CGFloat) {
        let wallColor   = UIColor(red: 28/255, green: 28/255, blue: 55/255, alpha: 1)
        let openColor   = UIColor(red: 100/255, green: 170/255, blue: 255/255, alpha: 200/255)
        let dashPat: [CGFloat] = [4, 6]

        for wall in walls {
            // Solid segments
            wallColor.setStroke()
            for (t0, t1) in solidWallIntervals(wall) {
                let path = UIBezierPath()
                path.move(to: CGPoint(
                    x: toPixX(wall.startX + wall.dirX * t0),
                    y: toPixZ(wall.startZ + wall.dirZ * t0)))
                path.addLine(to: CGPoint(
                    x: toPixX(wall.startX + wall.dirX * t1),
                    y: toPixZ(wall.startZ + wall.dirZ * t1)))
                path.lineWidth = 16 * r.fs
                path.lineCapStyle = .round
                path.stroke()
            }
            // Openings (dashed)
            openColor.setStroke()
            for o in wall.openings {
                let path = UIBezierPath()
                path.move(to: CGPoint(
                    x: toPixX(wall.startX + wall.dirX * o.offsetAlongWall),
                    y: toPixZ(wall.startZ + wall.dirZ * o.offsetAlongWall)))
                path.addLine(to: CGPoint(
                    x: toPixX(wall.startX + wall.dirX * (o.offsetAlongWall + o.width)),
                    y: toPixZ(wall.startZ + wall.dirZ * (o.offsetAlongWall + o.width))))
                path.lineWidth = 5 * r.fs
                path.lineCapStyle = .round
                path.setLineDash(dashPat, count: dashPat.count, phase: 0)
                path.stroke()
            }
        }
    }

    private static func solidWallIntervals(_ wall: ExportWall) -> [(Float, Float)] {
        let sorted = wall.openings.sorted { $0.offsetAlongWall < $1.offsetAlongWall }
        var out = [(Float, Float)](); var cursor: Float = 0
        for o in sorted {
            let gs = max(0, min(o.offsetAlongWall,           wall.length))
            let ge = max(0, min(o.offsetAlongWall + o.width, wall.length))
            if gs > cursor { out.append((cursor, gs)) }
            cursor = max(cursor, ge)
        }
        if cursor < wall.length { out.append((cursor, wall.length)) }
        return out
    }

    private static func drawRoomLabels(roomPolygons: [(String, [(Float, Float)])],
                                       placed: inout [CGRect], r: RC,
                                       toPixX: (Float) -> CGFloat,
                                       toPixZ: (Float) -> CGFloat) {
        guard !roomPolygons.isEmpty else { return }
        let font    = UIFont.boldSystemFont(ofSize: 38 * r.fs)
        let textColor = UIColor(red: 18/255, green: 30/255, blue: 90/255, alpha: 240/255)
        let bgColor   = UIColor(white: 1, alpha: 210/255)
        let pad       = 12 * r.fs

        for (name, poly) in roomPolygons {
            guard !poly.isEmpty else { continue }
            let cx = poly.map { toPixX($0.0) }.reduce(0, +) / CGFloat(poly.count)
            let cz = poly.map { toPixZ($0.1) }.reduce(0, +) / CGFloat(poly.count)
            let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: textColor]
            let tw = (name as NSString).size(withAttributes: attrs).width
            let th = font.lineHeight
            let rect = CGRect(x: cx - tw/2 - pad, y: cz - th,
                              width: tw + pad*2, height: th + 10 * r.fs)
            if placed.contains(where: { $0.intersects(rect) }) { continue }
            placed.append(rect)
            let bgPath = UIBezierPath(roundedRect: rect, cornerRadius: 8 * r.fs)
            bgColor.setFill(); bgPath.fill()
            (name as NSString).draw(at: CGPoint(x: cx - tw/2, y: cz - th), withAttributes: attrs)
        }
    }

    private static func drawWallQuotes(walls: [ExportWall], centroid: CGPoint,
                                       placed: inout [CGRect], r: RC,
                                       toPixX: (Float) -> CGFloat,
                                       toPixZ: (Float) -> CGFloat) {
        let MIN_PX = 70 * r.fs
        let OFF    = 48 * r.fs
        let TICK   = 9  * r.fs

        let lineColor = UIColor(red: 25/255, green: 55/255, blue: 145/255, alpha: 190/255)
        let textColor = UIColor(red: 18/255, green: 48/255, blue: 130/255, alpha: 230/255)
        let bgColor   = UIColor(white: 1, alpha: 215/255)
        let font      = UIFont.boldSystemFont(ofSize: 24 * r.fs)
        let pad       = 14 * r.fs

        for wall in walls.sorted(by: { $0.length > $1.length }) {
            let p0x = toPixX(wall.startX); let p0z = toPixZ(wall.startZ)
            let p1x = toPixX(wall.endX);   let p1z = toPixZ(wall.endZ)
            let pxLen = sqrt((p1x-p0x)*(p1x-p0x) + (p1z-p0z)*(p1z-p0z))
            guard pxLen >= MIN_PX else { continue }

            let wdx = (p1x - p0x) / pxLen; let wdz = (p1z - p0z) / pxLen
            let ndx = -wdz; let ndz = wdx
            let midPx = (p0x + p1x) / 2; let midPz = (p0z + p1z) / 2
            let dot = ndx * (midPx - centroid.x) + ndz * (midPz - centroid.y)
            let ox = dot >= 0 ? ndx : -ndx; let oz = dot >= 0 ? ndz : -ndz

            let d0x = p0x + ox * OFF; let d0z = p0z + oz * OFF
            let d1x = p1x + ox * OFF; let d1z = p1z + oz * OFF
            let dmx = (d0x + d1x) / 2; let dmz = (d0z + d1z) / 2

            guard d0x >= pad, d0x <= CGFloat(r.W) - pad,
                  d0z >= pad, d0z <= CGFloat(r.H) - pad,
                  d1x >= pad, d1x <= CGFloat(r.W) - pad,
                  d1z >= pad, d1z <= CGFloat(r.H) - pad else { continue }

            let label = String(format: "%.2fm", wall.length)
            let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: textColor]
            let tw = (label as NSString).size(withAttributes: attrs).width
            let th = font.lineHeight
            let lpad = 7 * r.fs
            let lr = CGRect(x: dmx - tw/2 - lpad, y: dmz - th,
                            width: tw + lpad*2, height: th + 6 * r.fs)
            if placed.contains(where: { $0.intersects(lr) }) { continue }
            placed.append(lr)

            // Leader lines + ticks
            lineColor.setStroke()
            func stroke(_ ax: CGFloat, _ ay: CGFloat, _ bx: CGFloat, _ by: CGFloat) {
                let p = UIBezierPath()
                p.move(to: CGPoint(x: ax, y: ay))
                p.addLine(to: CGPoint(x: bx, y: by))
                p.lineWidth = 1.8 * r.fs
                p.stroke()
            }
            stroke(p0x, p0z, d0x, d0z); stroke(p1x, p1z, d1x, d1z)
            stroke(d0x, d0z, d1x, d1z)
            stroke(d0x - wdx*TICK, d0z - wdz*TICK, d0x + wdx*TICK, d0z + wdz*TICK)
            stroke(d1x - wdx*TICK, d1z - wdz*TICK, d1x + wdx*TICK, d1z + wdz*TICK)

            // Label background + text
            let bgPath = UIBezierPath(roundedRect: lr, cornerRadius: 4 * r.fs)
            bgColor.setFill(); bgPath.fill()
            (label as NSString).draw(at: CGPoint(x: dmx - tw/2, y: dmz - th),
                                     withAttributes: attrs)
        }
    }

    private static func drawDimensionLabels(roomDim: RoomDimensions,
                                            minX: CGFloat, maxX: CGFloat,
                                            minZ: CGFloat, maxZ: CGFloat,
                                            r: RC,
                                            toPixX: (Float) -> CGFloat,
                                            toPixZ: (Float) -> CGFloat) {
        let textColor  = UIColor(red: 20/255, green: 80/255, blue: 200/255, alpha: 220/255)
        let arrowColor = UIColor(red: 20/255, green: 80/255, blue: 200/255, alpha: 180/255)
        let font       = UIFont.boldSystemFont(ofSize: 34 * r.fs)
        let lpad       = 8 * r.fs

        func drawDimLabel(_ text: String, _ cx: CGFloat, _ cy: CGFloat, rotated: Bool) {
            let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: textColor]
            let tw = (text as NSString).size(withAttributes: attrs).width
            let th = font.lineHeight
            let bg = CGRect(x: cx - tw/2 - lpad, y: cy - th,
                            width: tw + lpad*2, height: th + 6*r.fs)
            guard let ctx = UIGraphicsGetCurrentContext() else { return }
            if rotated {
                ctx.saveGState()
                ctx.translateBy(x: cx, y: cy)
                ctx.rotate(by: .pi / 2)
                ctx.translateBy(x: -cx, y: -cy)
            }
            UIColor.white.setFill()
            UIBezierPath(rect: bg).fill()
            (text as NSString).draw(at: CGPoint(x: cx - tw/2, y: cy - th), withAttributes: attrs)
            if rotated { ctx.restoreGState() }
        }

        // Width (bottom)
        let midPx  = (toPixX(Float(minX)) + toPixX(Float(maxX))) / 2
        let labelY = CGFloat(r.H) - r.margin + 55 * r.fs
        let arrowY = CGFloat(r.H) - r.margin + 20 * r.fs
        arrowColor.setStroke()
        let arrowPath = UIBezierPath()
        arrowPath.move(to: CGPoint(x: toPixX(Float(minX)), y: arrowY))
        arrowPath.addLine(to: CGPoint(x: toPixX(Float(maxX)), y: arrowY))
        arrowPath.lineWidth = 1.5 * r.fs
        arrowPath.stroke()
        drawDimLabel(String(format: "%.1fm", roomDim.width), midPx, labelY, rotated: false)

        // Length (right, rotated 90°)
        let rightX = CGFloat(r.W) - r.margin + 40 * r.fs
        let midPz  = (toPixZ(Float(minZ)) + toPixZ(Float(maxZ))) / 2
        drawDimLabel(String(format: "%.1fm", roomDim.length), rightX, midPz, rotated: true)
    }

    private static func drawHeader(walls: [ExportWall], roomDim: RoomDimensions, r: RC) {
        let openings = walls.reduce(0) { $0 + $1.openings.count }
        let titleFont = UIFont.boldSystemFont(ofSize: 38 * r.fs)
        let subFont   = UIFont.systemFont(ofSize: 27 * r.fs)
        let titleColor = UIColor(red: 20/255, green: 20/255, blue: 65/255, alpha: 210/255)
        let subColor   = UIColor(red: 60/255, green: 60/255, blue: 130/255, alpha: 160/255)

        ("Ultimo Rilievo" as NSString).draw(
            at: CGPoint(x: r.margin, y: 50 * r.fs),
            withAttributes: [.font: titleFont, .foregroundColor: titleColor])

        var sub = String(format: "%d pareti · %.1f m² · alt. %.1fm",
                         walls.count, roomDim.area, roomDim.height)
        if openings > 0 { sub += " · \(openings) aperture" }
        (sub as NSString).draw(
            at: CGPoint(x: r.margin, y: 84 * r.fs),
            withAttributes: [.font: subFont, .foregroundColor: subColor])
    }

    private static func drawScaleBar(scale: CGFloat, r: RC) {
        guard scale >= 10 else { return }
        let barX = r.margin
        let barY = CGFloat(r.H) - r.margin + 20 * r.fs
        let color = UIColor(white: 40/255, alpha: 160/255)
        color.setStroke()

        let path = UIBezierPath()
        path.move(to: CGPoint(x: barX,         y: barY))
        path.addLine(to: CGPoint(x: barX + scale, y: barY))
        path.move(to: CGPoint(x: barX,         y: barY - 6*r.fs))
        path.addLine(to: CGPoint(x: barX,         y: barY + 6*r.fs))
        path.move(to: CGPoint(x: barX + scale, y: barY - 6*r.fs))
        path.addLine(to: CGPoint(x: barX + scale, y: barY + 6*r.fs))
        path.lineWidth = 3 * r.fs
        path.stroke()

        ("1 m" as NSString).draw(
            at: CGPoint(x: barX + scale/2 - 15*r.fs, y: barY - (10 + 22)*r.fs),
            withAttributes: [
                .font: UIFont.systemFont(ofSize: 22 * r.fs),
                .foregroundColor: UIColor(white: 40/255, alpha: 160/255)
            ])
    }

    private static func drawBranding(r: RC) {
        let text  = "Powered by HubAgency"
        let font  = UIFont.systemFont(ofSize: 22 * r.fs)
        let attrs: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: UIColor(red: 60/255, green: 60/255, blue: 160/255, alpha: 110/255)
        ]
        let tw = (text as NSString).size(withAttributes: attrs).width
        (text as NSString).draw(
            at: CGPoint(x: CGFloat(r.W) - 24*r.fs - tw, y: CGFloat(r.H) - 24*r.fs - font.lineHeight),
            withAttributes: attrs)
    }

    // MARK: – Geometry utilities

    private static func convexHull(_ pts: [CGPoint]) -> [CGPoint] {
        guard pts.count > 2 else { return pts }
        let s = pts.sorted { $0.x != $1.x ? $0.x < $1.x : $0.y < $1.y }
        func cross(_ o: CGPoint, _ a: CGPoint, _ b: CGPoint) -> CGFloat {
            (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        }
        var lo = [CGPoint]()
        for p in s {
            while lo.count >= 2 && cross(lo[lo.count-2], lo.last!, p) <= 0 { lo.removeLast() }
            lo.append(p)
        }
        var up = [CGPoint]()
        for p in s.reversed() {
            while up.count >= 2 && cross(up[up.count-2], up.last!, p) <= 0 { up.removeLast() }
            up.append(p)
        }
        lo.removeLast(); up.removeLast()
        return lo + up
    }
}
