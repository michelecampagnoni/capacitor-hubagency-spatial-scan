import UIKit
import Foundation

// MARK: – RoomComposerView

/// Custom UIView che disegna la planimetria composita (ambienti fissi + nuovo) via CoreGraphics.
/// Porta iOS di RoomComposerView.kt.
final class RoomComposerView: UIView {

    // Ambienti già consolidati nel grafo — (nome, vertici) in world space
    var fixedPolygons:     [(String, [(Float, Float)])] = [] { didSet { setNeedsDisplay() } }
    // Nuovo ambiente — vertici in spazio locale
    var newRoomPolygon:    [(Float, Float)]              = [] { didSet { setNeedsDisplay() } }
    // Punto di aggancio fisso (world space)
    var fixedLinkCenter:   (Float, Float)?               = nil { didSet { setNeedsDisplay() } }
    // Punto di aggancio del nuovo ambiente (spazio locale)
    var newRoomLinkCenter: (Float, Float)?               = nil { didSet { setNeedsDisplay() } }
    // World transform del nuovo ambiente
    var offsetX:     Float = 0 { didSet { setNeedsDisplay() } }
    var offsetZ:     Float = 0 { didSet { setNeedsDisplay() } }
    var rotationRad: Float = 0 { didSet { setNeedsDisplay() } }
    // Indice del poligono fisso selezionato (-1 = nessuno)
    var selectedFixedIndex: Int = -1 { didSet { setNeedsDisplay() } }
    var onFixedRoomTapped: ((Int) -> Void)?

    // Parametri ultimo draw — per hit-test
    private var lastMinX:  CGFloat = 0; private var lastMinZ:  CGFloat = 0
    private var lastScale: CGFloat = 1; private let margin: CGFloat = 60

    // Pan / zoom
    private var zoomScale: CGFloat = 1; private var panX: CGFloat = 0; private var panY: CGFloat = 0
    private var prevPinchDist: CGFloat = 0
    private var isDragging = false; private var lastTouchPt = CGPoint.zero

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = UIColor(red: 18/255, green: 18/255, blue: 28/255, alpha: 1)
        isMultipleTouchEnabled = true
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        addGestureRecognizer(tap)
    }
    required init?(coder: NSCoder) { super.init(coder: coder) }

    // MARK: – Transform helpers

    func applyTransform(_ x: Float, _ z: Float) -> (Float, Float) {
        let c = cos(rotationRad); let s = sin(rotationRad)
        return (x * c - z * s + offsetX, x * s + z * c + offsetZ)
    }

    // MARK: – Draw

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        ctx.setFillColor(UIColor(red: 18/255, green: 18/255, blue: 28/255, alpha: 1).cgColor)
        ctx.fill(rect)

        let transformedNew = newRoomPolygon.map { applyTransform($0.0, $0.1) }
        let allVerts = fixedPolygons.flatMap { $0.1 } + transformedNew
        guard !allVerts.isEmpty else { return }

        let minX = CGFloat(allVerts.map { $0.0 }.min()!)
        let maxX = CGFloat(allVerts.map { $0.0 }.max()!)
        let minZ = CGFloat(allVerts.map { $0.1 }.min()!)
        let maxZ = CGFloat(allVerts.map { $0.1 }.max()!)

        let w = bounds.width - margin * 2
        let h = bounds.height - margin * 2
        let scaleX = w / max(maxX - minX, 0.5)
        let scaleZ = h / max(maxZ - minZ, 0.5)
        let scale  = min(scaleX, scaleZ)
        lastMinX = minX; lastMinZ = minZ; lastScale = scale

        ctx.saveGState()
        let cx = bounds.midX; let cy = bounds.midY
        ctx.translateBy(x: panX + cx, y: panY + cy)
        ctx.scaleBy(x: zoomScale, y: zoomScale)
        ctx.translateBy(x: -cx, y: -cy)

        func wx(_ x: Float) -> CGFloat { margin + (CGFloat(x) - minX) * scale }
        func wz(_ z: Float) -> CGFloat { margin + (CGFloat(z) - minZ) * scale }

        // Fixed polygons
        for (idx, entry) in fixedPolygons.enumerated() {
            let (name, poly) = entry
            guard poly.count >= 3 else { continue }
            let isSelected = idx == selectedFixedIndex
            let path = CGMutablePath()
            path.move(to: CGPoint(x: wx(poly[0].0), y: wz(poly[0].1)))
            for i in 1..<poly.count { path.addLine(to: CGPoint(x: wx(poly[i].0), y: wz(poly[i].1))) }
            path.closeSubpath()
            ctx.addPath(path)
            ctx.setFillColor(isSelected
                ? UIColor(red: 220/255, green: 60/255,  blue: 60/255, alpha: 0.55).cgColor
                : UIColor(red: 30/255,  green: 140/255, blue: 255/255, alpha: 0.35).cgColor)
            ctx.fillPath()
            ctx.addPath(path)
            ctx.setStrokeColor(isSelected
                ? UIColor(red: 1, green: 0.31, blue: 0.31, alpha: 0.9).cgColor
                : UIColor(red: 0.08, green: 0.39, blue: 0.82, alpha: 0.9).cgColor)
            ctx.setLineWidth(isSelected ? 5 : 4)
            ctx.strokePath()
            let centX = poly.map { wx($0.0) }.reduce(0, +) / CGFloat(poly.count)
            let centZ = poly.map { wz($0.1) }.reduce(0, +) / CGFloat(poly.count)
            let attrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.boldSystemFont(ofSize: 13),
                .foregroundColor: UIColor(red: 0.39, green: 0.71, blue: 1, alpha: 0.85)
            ]
            let ns = name as NSString
            let sz = ns.size(withAttributes: attrs)
            ns.draw(at: CGPoint(x: centX - sz.width / 2, y: centZ - sz.height / 2), withAttributes: attrs)
        }

        // New room polygon
        if transformedNew.count >= 3 {
            let path = CGMutablePath()
            path.move(to: CGPoint(x: wx(transformedNew[0].0), y: wz(transformedNew[0].1)))
            for i in 1..<transformedNew.count {
                path.addLine(to: CGPoint(x: wx(transformedNew[i].0), y: wz(transformedNew[i].1)))
            }
            path.closeSubpath()
            ctx.addPath(path)
            ctx.setFillColor(UIColor(red: 50/255, green: 200/255, blue: 80/255, alpha: 0.35).cgColor)
            ctx.fillPath()
            ctx.addPath(path)
            ctx.setStrokeColor(UIColor(red: 0.12, green: 0.63, blue: 0.24, alpha: 0.9).cgColor)
            ctx.setLineWidth(4)
            ctx.strokePath()
            let centX = transformedNew.map { wx($0.0) }.reduce(0, +) / CGFloat(transformedNew.count)
            let centZ = transformedNew.map { wz($0.1) }.reduce(0, +) / CGFloat(transformedNew.count)
            let attrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.boldSystemFont(ofSize: 13),
                .foregroundColor: UIColor(red: 0.31, green: 0.78, blue: 0.39, alpha: 0.85)
            ]
            ("NUOVO" as NSString).draw(at: CGPoint(x: centX - 28, y: centZ - 8), withAttributes: attrs)
        }

        // Link markers
        ctx.setFillColor(UIColor(red: 1, green: 0.63, blue: 0, alpha: 1).cgColor)
        if let cF = fixedLinkCenter {
            ctx.fillEllipse(in: CGRect(x: wx(cF.0) - 10, y: wz(cF.1) - 10, width: 20, height: 20))
        }
        if let cN = newRoomLinkCenter {
            let (tnx, tnz) = applyTransform(cN.0, cN.1)
            ctx.fillEllipse(in: CGRect(x: wx(tnx) - 10, y: wz(tnz) - 10, width: 20, height: 20))
            if let cF = fixedLinkCenter {
                ctx.setStrokeColor(UIColor(red: 1, green: 0.63, blue: 0, alpha: 0.7).cgColor)
                ctx.setLineWidth(2)
                ctx.setLineDash(phase: 0, lengths: [10, 6])
                ctx.move(to: CGPoint(x: wx(cF.0), y: wz(cF.1)))
                ctx.addLine(to: CGPoint(x: wx(tnx), y: wz(tnz)))
                ctx.strokePath()
                ctx.setLineDash(phase: 0, lengths: [])
            }
        }
        ctx.restoreGState()
    }

    // MARK: – Touch

    @objc private func handleTap(_ gr: UITapGestureRecognizer) {
        let pt = gr.location(in: self)
        let cx = bounds.midX; let cy = bounds.midY
        let canvasX = (pt.x - panX - cx) / zoomScale + cx
        let canvasZ = (pt.y - panY - cy) / zoomScale + cy
        let wx = (canvasX - margin) / lastScale + lastMinX
        let wz = (canvasZ - margin) / lastScale + lastMinZ

        for (idx, entry) in fixedPolygons.enumerated() {
            if pointInPolygon(px: Float(wx), pz: Float(wz), poly: entry.1) {
                let newIdx = selectedFixedIndex == idx ? -1 : idx
                selectedFixedIndex = newIdx
                onFixedRoomTapped?(newIdx)
                return
            }
        }
        if selectedFixedIndex != -1 { selectedFixedIndex = -1; onFixedRoomTapped?(-1) }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let all = event?.allTouches, all.count >= 2 {
            let arr = Array(all)
            let p1 = arr[0].location(in: self); let p2 = arr[1].location(in: self)
            let dist = hypot(p1.x - p2.x, p1.y - p2.y)
            if prevPinchDist > 0 {
                zoomScale = min(max(zoomScale * dist / prevPinchDist, 0.3), 8)
                setNeedsDisplay()
            }
            prevPinchDist = dist
        } else if let t = touches.first {
            let cur = t.location(in: self)
            let prev = t.previousLocation(in: self)
            panX += cur.x - prev.x; panY += cur.y - prev.y
            setNeedsDisplay()
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        prevPinchDist = 0
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        prevPinchDist = 0
    }

    private func pointInPolygon(px: Float, pz: Float, poly: [(Float, Float)]) -> Bool {
        var inside = false; var j = poly.count - 1
        for i in poly.indices {
            let xi = poly[i].0; let zi = poly[i].1
            let xj = poly[j].0; let zj = poly[j].1
            if (zi > pz) != (zj > pz) && px < (xj - xi) * (pz - zi) / (zj - zi) + xi {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}

// MARK: – RoomComposerViewController

/// Editor di composizione planimetrica multi-stanza.
/// Porta iOS di RoomComposerActivity.kt.
final class RoomComposerViewController: UIViewController {

    // MARK: – Input (impostati prima della presentazione)
    var parentRoomId:        String = ""
    var newRoomId:           String = ""
    var newRoomName:         String = ""
    var linkKind:            String = ""
    var linkWidth:           Float  = 0
    var linkParentOpeningId: String = ""
    var linkNewOpeningId:    String = ""
    weak var manager: SpatialScanManager?

    // MARK: – State
    /// true se l'utente ha premuto Conferma e l'export combinato è partito/completato.
    /// Usato in viewDidDisappear per rilevare dismiss non-conferma e resettare composerPending.
    private var didConfirmSave = false
    private var worldOx:  Float = 0; private var worldOz:  Float = 0; private var worldRot: Float = 0
    private var undoStack = [(Float, Float, Float)]()
    private var fixedPolygons = [(String, [(Float, Float)])]()
    private var newRoomPolygon = [(Float, Float)]()
    private var fixedLinkPt:   (Float, Float)? = nil
    private var newRoomLinkPt: (Float, Float)? = nil
    private var fixedRoomIds   = [String]()

    // MARK: – UI
    private let composerView    = RoomComposerView()
    private let deleteRoomBtn   = UIButton(type: .custom)

    // MARK: – Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard loadCompositionData() else {
            let alert = UIAlertController(title: "Dati non disponibili",
                                          message: "Non è stato possibile caricare i dati geometrici delle stanze.",
                                          preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in self?.dismiss(animated: true) })
            present(alert, animated: true)
            return
        }
        buildLayout()
        syncViewTransform()
    }

    /// Rileva dismiss senza conferma (tasto Annulla, swipe-down, loadCompositionData failure, ecc.)
    /// e resetta composerPending in modo che il flag non resti bloccato.
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if !didConfirmSave {
            manager?.composerDidCancel()
        }
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .portrait }

    // MARK: – Data loading

    private func loadCompositionData() -> Bool {
        let componentIds = CompositionGraph.shared.getComponentRoomIds(roomId: parentRoomId)
        let roomRecords  = Dictionary(uniqueKeysWithValues: RoomHistoryManager.shared.loadAll().map { ($0.id, $0) })

        var accFixedPolys  = [(String, [(Float, Float)])]()
        var fixedLinkPtOut: (Float, Float)? = nil
        var parentWorldPoly = [(Float, Float)]()
        fixedRoomIds.removeAll()

        for roomId in componentIds {
            fixedRoomIds.append(roomId)
            guard let json  = RoomHistoryManager.shared.loadRoomData(id: roomId) else { continue }
            let worldT = CompositionGraph.shared.getTransform(roomId: roomId)
            let rawPoly = parsePolygon(json)
            let worldPoly: [(Float, Float)]
            if let wt = worldT {
                worldPoly = rawPoly.map { tp($0.0, $0.1, wt.worldOffsetX, wt.worldOffsetZ, wt.worldRotRad) }
            } else { worldPoly = rawPoly }

            let name = roomRecords[roomId]?.name ?? String(roomId.prefix(6))
            accFixedPolys.append((name, worldPoly))

            if roomId == parentRoomId {
                parentWorldPoly = worldPoly
                let (wP, oP) = findLinkOpening(json, kind: linkKind, width: linkWidth, openingId: linkParentOpeningId)
                if let wPv = wP, let oPv = oP {
                    let localCenter = openingCenter(wall: wPv, open: oPv)
                    if let wt = worldT {
                        fixedLinkPtOut = tp(localCenter.0, localCenter.1, wt.worldOffsetX, wt.worldOffsetZ, wt.worldRotRad)
                    } else {
                        fixedLinkPtOut = localCenter
                    }
                }
            }
        }

        fixedPolygons = accFixedPolys
        fixedLinkPt   = fixedLinkPtOut

        // Nuovo ambiente
        guard let jsonNew = RoomHistoryManager.shared.loadRoomData(id: newRoomId) else { return false }
        newRoomPolygon = parsePolygon(jsonNew)

        let (wN, oN) = findLinkOpening(jsonNew, kind: linkKind, width: linkWidth, openingId: linkNewOpeningId)
        if let wNv = wN, let oNv = oN {
            let newLocalLink = openingCenter(wall: wNv, open: oNv)
            newRoomLinkPt = newLocalLink

            if let jsonParent = RoomHistoryManager.shared.loadRoomData(id: parentRoomId) {
                let (wP, oP) = findLinkOpening(jsonParent, kind: linkKind, width: linkWidth, openingId: linkParentOpeningId)
                if let wPv = wP, let oPv = oP {
                    let parentWorldT = CompositionGraph.shared.getTransform(roomId: parentRoomId)
                    let (ox, oz, rot) = computeInitialWorldAlignment(
                        wallParent: wPv, openParent: oPv, parentWorldT: parentWorldT,
                        wallNew: wNv, openNew: oNv)
                    worldOx = ox; worldOz = oz; worldRot = rot

                    // Anti-overlap
                    if newRoomPolygon.count >= 3 && parentWorldPoly.count >= 3 {
                        let pCentX = parentWorldPoly.map { $0.0 }.reduce(0, +) / Float(parentWorldPoly.count)
                        let pCentZ = parentWorldPoly.map { $0.1 }.reduce(0, +) / Float(parentWorldPoly.count)
                        let nLocX  = newRoomPolygon.map { $0.0 }.reduce(0, +) / Float(newRoomPolygon.count)
                        let nLocZ  = newRoomPolygon.map { $0.1 }.reduce(0, +) / Float(newRoomPolygon.count)

                        let (nWx1, nWz1) = tp(nLocX, nLocZ, worldOx, worldOz, worldRot)
                        let dist1 = pow(nWx1 - pCentX, 2) + pow(nWz1 - pCentZ, 2)

                        let rotF = worldRot + Float.pi
                        let cosF = cos(rotF); let sinF = sin(rotF)
                        let nlx = newLocalLink.0; let nlz = newLocalLink.1
                        let linkW = fixedLinkPt ?? (0, 0)
                        let oxF = linkW.0 - (nlx * cosF - nlz * sinF)
                        let ozF = linkW.1 - (nlx * sinF + nlz * cosF)
                        let (nWx2, nWz2) = tp(nLocX, nLocZ, oxF, ozF, rotF)
                        let dist2 = pow(nWx2 - pCentX, 2) + pow(nWz2 - pCentZ, 2)

                        if dist2 > dist1 { worldRot = rotF; worldOx = oxF; worldOz = ozF }
                    }
                }
            }
        } else if !fixedPolygons.isEmpty {
            // Fallback: posiziona a destra
            let allX = fixedPolygons.flatMap { $0.1.map { $0.0 } }
            let allZ = fixedPolygons.flatMap { $0.1.map { $0.1 } }
            worldOx = (allX.max() ?? 0) + 3
            worldOz = ((allZ.min() ?? 0) + (allZ.max() ?? 0)) / 2
            worldRot = 0
        }

        return true
    }

    // MARK: – Layout

    private func buildLayout() {
        composerView.translatesAutoresizingMaskIntoConstraints = false
        composerView.fixedPolygons    = fixedPolygons
        composerView.newRoomPolygon   = newRoomPolygon
        composerView.fixedLinkCenter  = fixedLinkPt
        composerView.newRoomLinkCenter = newRoomLinkPt
        composerView.onFixedRoomTapped = { [weak self] idx in
            self?.deleteRoomBtn.isHidden = (idx < 0)
        }
        view.addSubview(composerView)

        let panel = UIView()
        panel.backgroundColor = UIColor(red: 12/255, green: 14/255, blue: 22/255, alpha: 0.93)
        panel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(panel)

        NSLayoutConstraint.activate([
            panel.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            panel.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            panel.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            panel.heightAnchor.constraint(equalToConstant: 280),
            composerView.topAnchor.constraint(equalTo: view.topAnchor),
            composerView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            composerView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            composerView.bottomAnchor.constraint(equalTo: panel.topAnchor)
        ])

        let stack = UIStackView()
        stack.axis = .vertical; stack.spacing = 6; stack.alignment = .fill
        stack.translatesAutoresizingMaskIntoConstraints = false
        panel.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: panel.topAnchor, constant: 10),
            stack.leadingAnchor.constraint(equalTo: panel.leadingAnchor, constant: 12),
            stack.trailingAnchor.constraint(equalTo: panel.trailingAnchor, constant: -12),
            stack.bottomAnchor.constraint(lessThanOrEqualTo: panel.safeAreaLayoutGuide.bottomAnchor, constant: -8)
        ])

        let title = UILabel()
        title.text = "Composizione planimetrica"; title.textAlignment = .center
        title.textColor = UIColor(red: 0.71, green: 0.78, blue: 1, alpha: 0.8)
        title.font = .boldSystemFont(ofSize: 13)
        stack.addArrangedSubview(title)

        // Sposta
        stack.addArrangedSubview(sectionLabel("Sposta nuovo ambiente"))
        stack.addArrangedSubview(buttonRow([
            makeBtn("←") { [weak self] in self?.moveNew(-0.05, 0) },
            makeBtn("→") { [weak self] in self?.moveNew( 0.05, 0) },
            makeBtn("↑") { [weak self] in self?.moveNew(0, -0.05) },
            makeBtn("↓") { [weak self] in self?.moveNew(0,  0.05) }
        ]))

        // Ruota
        stack.addArrangedSubview(sectionLabel("Ruota nuovo ambiente"))
        stack.addArrangedSubview(buttonRow([
            makeBtn("↺ 1°")  { [weak self] in self?.rotateNew(-1) },
            makeBtn("↻ 1°")  { [weak self] in self?.rotateNew( 1) },
            makeBtn("↺ 5°")  { [weak self] in self?.rotateNew(-5) },
            makeBtn("↻ 5°")  { [weak self] in self?.rotateNew( 5) },
            makeBtn("Snap 90°") { [weak self] in self?.snap90() }
        ]))

        // Undo
        stack.addArrangedSubview(buttonRow([
            makeBtn("↩ Annulla", bg: UIColor(red: 0.24, green: 0.16, blue: 0.08, alpha: 1)) { [weak self] in
                self?.undoLast()
            }
        ]))

        // Delete room
        styleBtn(deleteRoomBtn, title: "🗑 Elimina stanza",
                 bg: UIColor(red: 0.55, green: 0.08, blue: 0.08, alpha: 1))
        deleteRoomBtn.isHidden = true
        deleteRoomBtn.addTarget(self, action: #selector(confirmDeleteRoom), for: .touchUpInside)
        deleteRoomBtn.heightAnchor.constraint(equalToConstant: 40).isActive = true
        stack.addArrangedSubview(deleteRoomBtn)

        // Action row
        let actionRow = UIStackView()
        actionRow.axis = .horizontal; actionRow.spacing = 8; actionRow.distribution = .fillEqually
        actionRow.heightAnchor.constraint(equalToConstant: 44).isActive = true
        let cancelBtn = makeBtn("Annulla", bg: UIColor(red: 0.31, green: 0.12, blue: 0.12, alpha: 1)) { [weak self] in
            self?.confirmCancel()
        }
        let confirmBtn = makeBtn("Conferma", bg: UIColor(red: 0.07, green: 0.35, blue: 0.20, alpha: 1)) { [weak self] in
            self?.confirmSave()
        }
        actionRow.addArrangedSubview(cancelBtn)
        actionRow.addArrangedSubview(confirmBtn)
        stack.addArrangedSubview(actionRow)
    }

    // MARK: – Actions

    private func pushUndo() {
        undoStack.append((worldOx, worldOz, worldRot))
        if undoStack.count > 50 { undoStack.removeFirst() }
    }
    private func undoLast() {
        guard let prev = undoStack.popLast() else { return }
        worldOx = prev.0; worldOz = prev.1; worldRot = prev.2; syncViewTransform()
    }
    private func moveNew(_ dx: Float, _ dz: Float) {
        pushUndo(); worldOx += dx; worldOz += dz; syncViewTransform()
    }
    private func rotateNew(_ deg: Float) {
        pushUndo(); worldRot += deg * Float.pi / 180; syncViewTransform()
    }
    private func snap90() {
        pushUndo()
        let deg = worldRot * 180 / Float.pi
        worldRot = Float(round(Double(deg) / 90) * 90) * Float.pi / 180
        syncViewTransform()
    }
    private func syncViewTransform() {
        composerView.offsetX     = worldOx
        composerView.offsetZ     = worldOz
        composerView.rotationRad = worldRot
    }
    private func confirmCancel() {
        // Reset immediato: viewDidDisappear lo chiamerebbe comunque, ma meglio esplicito
        manager?.composerDidCancel()
        showContinueScanDialog()
    }

    private func confirmSave() {
        // Mark save intent PRIMA di qualunque dismiss/presentazione asincrona,
        // così viewDidDisappear non chiamerà composerDidCancel() per errore.
        didConfirmSave = true

        // Salva world transform nel grafo
        let transform = RoomWorldTransform(
            roomId:       newRoomId,
            parentId:     parentRoomId,
            worldOffsetX: worldOx,
            worldOffsetZ: worldOz,
            worldRotRad:  worldRot,
            confirmedAt:  Int64(Date().timeIntervalSince1970 * 1000)
        )
        CompositionGraph.shared.addTransform(transform)
        NSLog("[HUB_DIAG] Composer confirmSave: savedTransform newRoomId=%@ parentRoomId=%@ ox=%.3f oz=%.3f rot=%.3f",
              newRoomId, parentRoomId, worldOx, worldOz, worldRot)

        // Export combinata: BFS esplicita da parentRoomId (anchor fisso = world origin)
        let capturedNewRoomId   = newRoomId
        let capturedParentId    = parentRoomId
        let capturedManager     = manager
        let cacheDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory

        DispatchQueue.global(qos: .userInitiated).async {
            guard let exportData = RoomDataLoader.buildExportData(anchorRoomId: capturedParentId) else {
                NSLog("[HUB_DIAG] Composer confirmSave: buildExportData returned nil — cancelling")
                DispatchQueue.main.async {
                    capturedManager?.composerDidCancel()
                    self.showContinueScanDialog()
                }
                return
            }

            let pngPath = FloorPlanExporter.export(data: exportData, cacheDir: cacheDir)
            let glbPath = GlbExporter.export(data: exportData, cacheDir: cacheDir)
            let pdfPath = FloorPlanExporter.exportPdf(data: exportData, cacheDir: cacheDir)
            NSLog("[HUB_DIAG] Composer confirmSave: pngPath=%@ glbPath=%@ pdfPath=%@",
                  pngPath ?? "nil", glbPath ?? "nil", pdfPath ?? "nil")

            // Guard: entrambi i path devono essere non-nil (export non fallita)
            guard let png = pngPath, let glb = glbPath else {
                NSLog("[HUB_DIAG] Composer confirmSave: export fallito (pngPath o glbPath nil) — cancelling")
                DispatchQueue.main.async {
                    capturedManager?.composerDidCancel()
                    self.showContinueScanDialog()
                }
                return
            }

            if let p = pdfPath {
                UserDefaults.standard.set(p, forKey: "hub_lastPdfPath")
            }

            let combinedResult: [String: Any] = [
                "success":       true,
                "roomId":        capturedNewRoomId,
                "area":          exportData.dimensions.area,
                "wallCount":     exportData.walls.count,
                "floorPlanPath": png,
                "glbPath":       glb
            ]
            NSLog("[HUB_DIAG] Composer confirmSave: firing composerDidConfirm roomId=%@ walls=%d area=%.1f",
                  capturedNewRoomId, exportData.walls.count, exportData.dimensions.area)
            DispatchQueue.main.async {
                capturedManager?.composerDidConfirm(combinedResult)
                self.showContinueScanDialog()
            }
        }
    }

    @objc private func confirmDeleteRoom() {
        let idx = composerView.selectedFixedIndex
        guard idx >= 0 && idx < fixedRoomIds.count else { return }
        let roomId   = fixedRoomIds[idx]
        let roomName = fixedPolygons[safe: idx]?.0 ?? String(roomId.prefix(6))
        let alert = UIAlertController(title: "Elimina stanza",
                                      message: "Elimini \"\(roomName)\" e tutti gli ambienti collegati? Le porte abbinate torneranno disponibili.",
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Elimina", style: .destructive) { [weak self] _ in
            self?.deleteRoomCascade(rootId: roomId)
        })
        alert.addAction(UIAlertAction(title: "Annulla", style: .cancel))
        present(alert, animated: true)
    }

    private func deleteRoomCascade(rootId: String) {
        let toDelete  = collectSubtree(rootId)
        let roomRecs  = Dictionary(uniqueKeysWithValues: RoomHistoryManager.shared.loadAll().map { ($0.id, $0) })
        for roomId in toDelete {
            if let parentId = CompositionGraph.shared.getTransform(roomId: roomId)?.parentId {
                let parentName = roomRecs[parentId]?.name ?? String(parentId.prefix(6))
                restoreParentOpening(parentId: parentId, parentName: parentName, deletedRoomId: roomId)
            }
            RoomHistoryManager.shared.delete(id: roomId)
            CompositionGraph.shared.removeTransform(roomId: roomId)
        }
        if toDelete.contains(parentRoomId) { dismiss(animated: true); return }
        composerView.selectedFixedIndex = -1
        deleteRoomBtn.isHidden = true
        reloadFixedData()
    }

    private func collectSubtree(_ rootId: String) -> [String] {
        var result = [String](); var queue = [rootId]
        while !queue.isEmpty {
            let id = queue.removeFirst()
            result.append(id)
            queue.append(contentsOf: CompositionGraph.shared.getChildIds(parentId: id))
        }
        return result
    }

    private func restoreParentOpening(parentId: String, parentName: String, deletedRoomId: String) {
        guard var json = RoomHistoryManager.shared.loadRoomData(id: parentId),
              var walls = json["walls"] as? [[String: Any]] else { return }
        var changed = false
        for wi in 0..<walls.count {
            if var ops = walls[wi]["openings"] as? [[String: Any]] {
                for oi in 0..<ops.count {
                    guard ops[oi]["linkedRoomId"] as? String == deletedRoomId else { continue }
                    let uop = UnlinkedOpening(
                        id:             "uop_\(UUID().uuidString)",
                        sourceRoomId:   parentId,
                        sourceRoomName: parentName,
                        openingId:      ops[oi]["id"] as? String ?? "",
                        kind:           OpeningKind(rawValue: ops[oi]["kind"] as? String ?? "") ?? .door,
                        width:          (ops[oi]["width"]  as? NSNumber)?.floatValue ?? 0.80,
                        height:         (ops[oi]["height"] as? NSNumber)?.floatValue ?? 2.10,
                        bottom:         (ops[oi]["bottom"] as? NSNumber)?.floatValue ?? 0.00,
                        wallIndex:      wi,
                        customLabel:    ops[oi]["connectionLabel"] as? String ?? ""
                    )
                    UnlinkedOpeningStore.shared.add(uop)
                    ops[oi]["connectionStatus"] = ConnectionStatus.pending.rawValue
                    ops[oi]["linkedRoomId"]     = ""
                    ops[oi]["connectionLabel"]  = ""
                    changed = true
                }
                walls[wi]["openings"] = ops
            }
        }
        if changed {
            json["walls"] = walls
            if let docsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first,
               let data = try? JSONSerialization.data(withJSONObject: json) {
                try? data.write(to: docsDir.appendingPathComponent("hub_room_\(parentId).json"))
            }
        }
    }

    private func reloadFixedData() {
        let componentIds = CompositionGraph.shared.getComponentRoomIds(roomId: parentRoomId)
        let roomRecs = Dictionary(uniqueKeysWithValues: RoomHistoryManager.shared.loadAll().map { ($0.id, $0) })
        var newPolys = [(String, [(Float, Float)])]()
        fixedRoomIds.removeAll()
        for roomId in componentIds {
            fixedRoomIds.append(roomId)
            guard let json = RoomHistoryManager.shared.loadRoomData(id: roomId) else { continue }
            let worldT = CompositionGraph.shared.getTransform(roomId: roomId)
            let rawPoly = parsePolygon(json)
            let worldPoly: [(Float, Float)]
            if let wt = worldT {
                worldPoly = rawPoly.map { tp($0.0, $0.1, wt.worldOffsetX, wt.worldOffsetZ, wt.worldRotRad) }
            } else { worldPoly = rawPoly }
            let name = roomRecs[roomId]?.name ?? String(roomId.prefix(6))
            newPolys.append((name, worldPoly))
        }
        fixedPolygons = newPolys
        composerView.fixedPolygons = newPolys
    }

    private func showContinueScanDialog() {
        let alert = UIAlertController(title: "Scan completata",
                                      message: "Vuoi scansionare un altro ambiente?",
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Sì", style: .default) { [weak self] _ in
            self?.dismiss(animated: true) { self?.manager?.startContinuationScan() }
        })
        alert.addAction(UIAlertAction(title: "No", style: .cancel) { [weak self] _ in
            UserDefaults.standard.set(true, forKey: "hub_sessionEnded")
            self?.dismiss(animated: true)
        })
        present(alert, animated: true)
    }

    // MARK: – Geometry helpers

    private func tp(_ x: Float, _ z: Float, _ ox: Float, _ oz: Float, _ rot: Float) -> (Float, Float) {
        let c = cos(rot); let s = sin(rot)
        return (x * c - z * s + ox, x * s + z * c + oz)
    }

    private func openingCenter(wall: (Float, Float, Float, Float), open: (Float, Float)) -> (Float, Float) {
        let dx = wall.2 - wall.0; let dz = wall.3 - wall.1
        let len = max(sqrt(dx * dx + dz * dz), 1e-6)
        let t = open.0 + open.1 / 2
        return (wall.0 + (dx / len) * t, wall.1 + (dz / len) * t)
    }

    private func normalOf(wall: (Float, Float, Float, Float)) -> (Float, Float) {
        let dx = wall.2 - wall.0; let dz = wall.3 - wall.1
        let len = max(sqrt(dx * dx + dz * dz), 1e-6)
        return (dz / len, -(dx / len))
    }

    /// Calcola il world transform iniziale del nuovo ambiente allineando le aperture di collegamento.
    private func computeInitialWorldAlignment(
        wallParent: (Float, Float, Float, Float), openParent: (Float, Float),
        parentWorldT: RoomWorldTransform?,
        wallNew: (Float, Float, Float, Float), openNew: (Float, Float)
    ) -> (Float, Float, Float) {
        let pOx  = parentWorldT?.worldOffsetX ?? 0
        let pOz  = parentWorldT?.worldOffsetZ ?? 0
        let pRot = parentWorldT?.worldRotRad  ?? 0

        let (cPxL, cPzL) = openingCenter(wall: wallParent, open: openParent)
        let (cPxW, cPzW) = tp(cPxL, cPzL, pOx, pOz, pRot)
        let (nPxL, nPzL) = normalOf(wall: wallParent)
        let cosP = cos(pRot); let sinP = sin(pRot)
        let nPxW = nPxL * cosP - nPzL * sinP
        let nPzW = nPxL * sinP + nPzL * cosP

        let (nNx, nNz) = normalOf(wall: wallNew)
        let targetAngle  = atan2(-nPzW, -nPxW)
        let currentAngle = atan2(nNz, nNx)
        let theta        = targetAngle - currentAngle

        let (cNxL, cNzL) = openingCenter(wall: wallNew, open: openNew)
        let cosT = cos(theta); let sinT = sin(theta)
        let cNrotX = cNxL * cosT - cNzL * sinT
        let cNrotZ = cNxL * sinT + cNzL * cosT

        return (cPxW - cNrotX, cPzW - cNrotZ, theta)
    }

    // MARK: – JSON parsing

    private func parsePolygon(_ json: [String: Any]) -> [(Float, Float)] {
        guard let floor    = json["floor"]    as? [String: Any],
              let vertices = floor["vertices"] as? [[String: Any]] else { return [] }
        return vertices.compactMap { v -> (Float, Float)? in
            guard let x = (v["x"] as? NSNumber)?.floatValue,
                  let z = (v["z"] as? NSNumber)?.floatValue else { return nil }
            return (x, z)
        }
    }

    /// Cerca l'apertura di collegamento nel JSON della stanza.
    /// Restituisce (wall(sx,sz,ex,ez), opening(offset,width)) o (nil,nil).
    private func findLinkOpening(_ json: [String: Any], kind: String, width: Float, openingId: String)
        -> ((Float, Float, Float, Float)?, (Float, Float)?)
    {
        guard let walls = json["walls"] as? [[String: Any]] else { return (nil, nil) }

        // Prima passata: match per ID esatto
        if !openingId.isEmpty {
            for wall in walls {
                let sp = wall["startPoint"] as? [String: Any]
                let ep = wall["endPoint"]   as? [String: Any]
                guard let sx = (sp?["x"] as? NSNumber)?.floatValue,
                      let sz = (sp?["z"] as? NSNumber)?.floatValue,
                      let ex = (ep?["x"] as? NSNumber)?.floatValue,
                      let ez = (ep?["z"] as? NSNumber)?.floatValue,
                      let ops = wall["openings"] as? [[String: Any]] else { continue }
                for op in ops {
                    if op["id"] as? String == openingId {
                        let off = (op["offsetAlongWall"] as? NSNumber)?.floatValue ?? 0
                        let w   = (op["width"]           as? NSNumber)?.floatValue ?? 0
                        return ((sx, sz, ex, ez), (off, w))
                    }
                }
            }
        }

        // Fallback: match per kind + width
        for wall in walls {
            let sp = wall["startPoint"] as? [String: Any]
            let ep = wall["endPoint"]   as? [String: Any]
            guard let sx = (sp?["x"] as? NSNumber)?.floatValue,
                  let sz = (sp?["z"] as? NSNumber)?.floatValue,
                  let ex = (ep?["x"] as? NSNumber)?.floatValue,
                  let ez = (ep?["z"] as? NSNumber)?.floatValue,
                  let ops = wall["openings"] as? [[String: Any]] else { continue }
            for op in ops {
                if op["kind"] as? String == kind,
                   let w = (op["width"] as? NSNumber)?.floatValue, abs(w - width) < 0.12 {
                    let off = (op["offsetAlongWall"] as? NSNumber)?.floatValue ?? 0
                    return ((sx, sz, ex, ez), (off, w))
                }
            }
        }
        return (nil, nil)
    }

    // MARK: – UI builders

    private func sectionLabel(_ text: String) -> UILabel {
        let l = UILabel()
        l.text = text; l.font = .systemFont(ofSize: 10)
        l.textColor = UIColor(red: 0.63, green: 0.71, blue: 0.86, alpha: 0.55)
        return l
    }

    private func buttonRow(_ buttons: [UIButton]) -> UIStackView {
        let row = UIStackView(arrangedSubviews: buttons)
        row.axis = .horizontal; row.spacing = 4; row.distribution = .fillEqually
        row.heightAnchor.constraint(equalToConstant: 36).isActive = true
        return row
    }

    @discardableResult
    private func makeBtn(_ title: String, bg: UIColor = UIColor(red: 0.12, green: 0.16, blue: 0.24, alpha: 1),
                         action: @escaping () -> Void) -> UIButton {
        let btn = UIButton(type: .custom)
        styleBtn(btn, title: title, bg: bg)
        btn.addAction(UIAction { _ in action() }, for: .touchUpInside)
        return btn
    }

    private func styleBtn(_ btn: UIButton, title: String, bg: UIColor) {
        btn.setTitle(title, for: .normal)
        btn.setTitleColor(.white, for: .normal)
        btn.titleLabel?.font = .systemFont(ofSize: 11)
        btn.backgroundColor = bg
        btn.layer.cornerRadius = 6
        btn.clipsToBounds = true
    }
}

// MARK: – Safe subscript

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
