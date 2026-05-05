import UIKit
import ARKit
import GLKit
import simd

/// Port di ScanningActivity.kt — Guided Perimeter Capture + Opening Placement.
///
/// Flusso: floor lock → TAP angoli → chiudi poligono → imposta altezza
///         → aggiungi aperture → esporta.
///
/// Rendering: ARSCNView (camera background, ARKit) + GLKView trasparente sopra (GLES 2.0).
class ScanningViewController: UIViewController, ARSessionDelegate, GLKViewDelegate {

    // MARK: – Callback verso SpatialScanManager

    var onScanFinished:  (([String: Any]) -> Void)?
    var onScanCancelled: (() -> Void)?
    weak var manager: SpatialScanManager?

    // MARK: – AR + GL

    private let arView      = ARSCNView()
    private var glContext:  EAGLContext!
    private var glView:     GLKView!
    private var displayLink: CADisplayLink?

    // MARK: – Renderers

    private let perimeterRenderer = PerimeterRenderer()
    private let openingRenderer   = OpeningRenderer()

    // MARK: – Capture state

    private let perimeterCapture = PerimeterCapture()
    private let floorAnchor      = FloorPlaneAnchor()
    private var lastFloorY: Float = 0
    private var wallHeightPreview: Float = 2.50

    // MARK: – Reticle smoothing (8 floor, 4 height — invariante)

    private var reticleXBuf = [Float](repeating: 0, count: 8)
    private var reticleZBuf = [Float](repeating: 0, count: 8)
    private var reticleBufIdx  = 0; private var reticleBufFill = 0

    private var heightXBuf = [Float](repeating: 0, count: 4)
    private var heightYBuf = [Float](repeating: 0, count: 4)
    private var heightZBuf = [Float](repeating: 0, count: 4)
    private var heightBufIdx  = 0; private var heightBufFill  = 0

    // MARK: – Reticle state

    private var lastReticleWorld:     SIMD3<Float>?
    private var lastLivePreview:      SIMD3<Float>?
    private var lastReticleWorldFree: SIMD3<Float>?

    // MARK: – Goniometer snap (invariante assoluta: ±65°, isteresi 0.20/0.10)

    private var lastSnappedReticle:    SIMD3<Float>?
    private var reticleIsSnapped       = false
    private var reticleIsRealHit       = false
    private var goniometerCenterPt:    SIMD3<Float>?
    private var goniometerCurrentAngle: Float     = 0
    private var goniometerSnapAngle:   Float?

    // MARK: – TOP mode (auto da tilt camera — isteresi 0.20/0.10)

    private var reticleTopMode      = false
    private var lastTopCursorWorld: SIMD3<Float>?

    // MARK: – Freeze at close

    private var frozenPolygon: [SIMD3<Float>]?
    private var frozenFloorY:  Float?

    // MARK: – Opening mode

    private var roomModel:      RoomModel?
    private var hoveredWallId:  String?
    private var selectedWallId: String?
    private var editingOpening: OpeningModel?
    private var openingMode     = false
    private var openingMetadataMap = [String: OpeningMetadata]()
    private var currentRoomName    = "Stanza"

    // MARK: – Multi-room state (M5)

    var isContinuation: Bool = false
    // openingId → (status, unlinkedTarget, wallIndex)
    private var pendingClassification = [String: (ConnectionStatus, UnlinkedOpening?, Int)]()
    // (openingId, UnlinkedOpening con sourceRoomId ancora vuoto)
    private var pendingUnlinkedOpenings = [(String, UnlinkedOpening)]()
    // Aperture da aggiornare bilateralmente nella stanza sorgente dopo il salvataggio
    private var pendingLinkUpdates = [UnlinkedOpening]()
    private var pendingComposerRoomId: String?
    private var pendingComposerLinkKind: String = ""
    private var pendingComposerLinkWidth: Float = 0
    private var pendingComposerParentOpeningId: String = ""
    private var pendingComposerNewOpeningId: String = ""
    private var pendingLabels = [String: String]()

    // MARK: – Timing

    private var scanStartTime: Date = Date()
    private var lastUIUpdateTime: TimeInterval = 0

    // MARK: – UI elements

    private let guidanceHeadline  = UILabel()
    private let guidanceSubtext   = UILabel()
    private let sideBadge         = UILabel()
    private let timerLabel        = UILabel()
    private let confirmBtn        = UIButton(type: .custom)
    private let cancelBtn         = UIButton(type: .custom)
    private let actionBtn         = UIButton(type: .custom)
    private let undoBtn           = UIButton(type: .custom)
    private let heightControlView = UIView()
    private let heightValueLabel  = UILabel()
    private let heightBanner      = UILabel()
    private let distanceLabel     = UILabel()
    private let openingPhaseView  = UIView()
    private let openingTypeRow    = UIStackView()
    private let openingEditPanel  = UIView()
    private let openingEditTitle  = UILabel()
    private let openingPosLabel   = UILabel()
    private let openingWidthLabel = UILabel()
    private let openingHeightLbl  = UILabel()
    private let openingBottomView = UIView()
    private let openingBottomLbl  = UILabel()
    private let reticleLayer       = CAShapeLayer()
    private let bottomStack        = UIStackView()
    private let openingPhaseStack  = UIStackView()
    private let openingEditStack   = UIStackView()
    private let openingHeader      = UILabel()

    // MARK: – Lifecycle

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .portrait }
    override var preferredInterfaceOrientationForPresentation: UIInterfaceOrientation { .portrait }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        setupARView()
        setupGLView()
        buildUI()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if !isContinuation {
            let prefs = UserDefaults.standard
            if prefs.bool(forKey: "hub_sessionEnded") {
                clearSessionData()
                prefs.set(false, forKey: "hub_sessionEnded")
            }
        }
        showRoomNameAlert()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        let config = ARWorldTrackingConfiguration()
        config.planeDetection = [.horizontal]
        arView.session.run(config, options: [.resetTracking, .removeExistingAnchors])
        displayLink = CADisplayLink(target: self, selector: #selector(renderFrame))
        displayLink?.add(to: .main, forMode: .common)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        displayLink?.invalidate()
        displayLink = nil
        arView.session.pause()
    }

    @objc private func renderFrame() {
        glView?.display()
    }

    // MARK: – Setup AR

    private func setupARView() {
        arView.frame = view.bounds
        arView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        arView.session.delegate = self
        arView.automaticallyUpdatesLighting = true
        view.addSubview(arView)
    }

    // MARK: – Setup GL

    private func setupGLView() {
        guard let ctx = EAGLContext(api: .openGLES2) else {
            print("[ScanningVC] EAGLContext creation failed — GLES overlay unavailable")
            return
        }
        glContext = ctx
        EAGLContext.setCurrent(glContext)

        glView = GLKView(frame: view.bounds, context: glContext)
        glView.autoresizingMask  = [.flexibleWidth, .flexibleHeight]
        glView.delegate          = self
        glView.isOpaque          = false
        glView.backgroundColor   = .clear
        glView.layer.isOpaque    = false
        glView.drawableDepthFormat  = .formatNone
        glView.enableSetNeedsDisplay = false
        view.addSubview(glView)

        perimeterRenderer.setup()
        openingRenderer.setup()
    }

    // MARK: – GLKViewDelegate (render loop)

    func glkView(_ view: GLKView, drawIn rect: CGRect) {
        guard let frame = arView.session.currentFrame else { return }
        EAGLContext.setCurrent(glContext)

        glClearColor(0, 0, 0, 0)
        glClear(GLbitfield(GL_COLOR_BUFFER_BIT))
        glViewport(0, 0, GLsizei(view.drawableWidth), GLsizei(view.drawableHeight))

        let camera    = frame.camera
        let isTracking: Bool = { if case .normal = camera.trackingState { return true }; return false }()
        let viewMtx   = camera.viewMatrix(for: .portrait)
        let projMtx   = camera.projectionMatrix(
            for: .portrait, viewportSize: view.bounds.size, zNear: 0.05, zFar: 100)

        // ── Floor Y update
        lastFloorY = floorAnchor.update(frame: frame, isTracking: isTracking)

        // ── Auto-switch TOP/FLOOR (invariante: isteresi 0.20/0.10)
        let camFwd = SIMD3<Float>(
            frame.camera.transform.columns.2.x * -1,
            frame.camera.transform.columns.2.y * -1,
            frame.camera.transform.columns.2.z * -1)
        let phase   = perimeterCapture.capturePhase
        let canTop  = !openingMode &&
            (phase == .awaitSecondFloor || phase == .floorOnly)
        if canTop {
            if camFwd.y > 0.20 && !reticleTopMode {
                reticleTopMode = true
                reticleBufIdx = 0; reticleBufFill = 0
            } else if camFwd.y < 0.10 && reticleTopMode {
                reticleTopMode = false
                reticleBufIdx = 0; reticleBufFill = 0
                lastTopCursorWorld = nil
            }
        } else if reticleTopMode {
            reticleTopMode = false; lastTopCursorWorld = nil
        }
        let topModeActive = reticleTopMode && canTop

        // ── Reticle update (floor or top)
        if topModeActive {
            if let topHit = screenToWorldTopPlane(camera: camera) {
                reticleIsRealHit = true
                reticleXBuf[reticleBufIdx] = topHit.x
                reticleZBuf[reticleBufIdx] = topHit.z
                reticleBufIdx = (reticleBufIdx + 1) % reticleXBuf.count
                if reticleBufFill < reticleXBuf.count { reticleBufFill += 1 }
                var sumX: Float = 0; var sumZ: Float = 0
                for k in 0..<reticleBufFill { sumX += reticleXBuf[k]; sumZ += reticleZBuf[k] }
                let norm = SIMD3<Float>(sumX / Float(reticleBufFill), lastFloorY, sumZ / Float(reticleBufFill))
                lastReticleWorld = norm
                lastLivePreview  = perimeterCapture.livePreview(norm)
                lastTopCursorWorld = SIMD3<Float>(norm.x, lastFloorY + wallHeightPreview, norm.z)
            }
        } else {
            lastTopCursorWorld = nil
            let rawRw: SIMD3<Float>? = floorAnchor.isLocked
                ? screenToWorldFloorPlane(camera: camera)
                : screenToWorldFallback(frame: frame, camera: camera)
            reticleIsRealHit = rawRw != nil
            if let rw = rawRw {
                let projY: Float = floorAnchor.isLocked ? lastFloorY : rw.y
                reticleXBuf[reticleBufIdx] = rw.x
                reticleZBuf[reticleBufIdx] = rw.z
                reticleBufIdx = (reticleBufIdx + 1) % reticleXBuf.count
                if reticleBufFill < reticleXBuf.count { reticleBufFill += 1 }
                var sumX: Float = 0; var sumZ: Float = 0
                for k in 0..<reticleBufFill { sumX += reticleXBuf[k]; sumZ += reticleZBuf[k] }
                let norm = SIMD3<Float>(sumX / Float(reticleBufFill), projY, sumZ / Float(reticleBufFill))
                lastReticleWorld = norm
                lastLivePreview  = perimeterCapture.livePreview(norm)
            }
        }

        // ── Goniometer snap (invariante assoluta)
        let gonioCenterPt = perimeterCapture.getPolygon().last
        let camPos = SIMD3<Float>(
            frame.camera.transform.columns.3.x,
            frame.camera.transform.columns.3.y,
            frame.camera.transform.columns.3.z)
        let dy2 = camFwd.y
        let geoFloor: SIMD3<Float> = {
            if dy2 < -0.005 {
                let t = (lastFloorY - camPos.y) / dy2
                if t >= 0 && t <= 15 {
                    return SIMD3(camPos.x + t * camFwd.x, lastFloorY, camPos.z + t * camFwd.z)
                }
            }
            let xzL = sqrt(camFwd.x * camFwd.x + camFwd.z * camFwd.z)
            if xzL > 0.01 {
                return SIMD3(camPos.x + camFwd.x / xzL * 3, lastFloorY, camPos.z + camFwd.z / xzL * 3)
            }
            return lastReticleWorld ?? SIMD3(camPos.x, lastFloorY, camPos.z)
        }()
        let baseReticle = topModeActive ? (lastReticleWorld ?? geoFloor) : geoFloor
        goniometerCenterPt = gonioCenterPt
        let fwdXZLen = sqrt(camFwd.x * camFwd.x + camFwd.z * camFwd.z)
        if fwdXZLen > 0.01 { goniometerCurrentAngle = atan2(camFwd.z, camFwd.x) }

        if let gc = gonioCenterPt {
            let vx  = baseReticle.x - gc.x; let vz = baseReticle.z - gc.z
            let dist = sqrt(vx*vx + vz*vz)
            if dist > 0.20 {
                let rawAngle  = atan2(vz, vx)
                let STEP_RAD: Float = .pi / 180      // snap ogni 1°
                let snapAngle = (rawAngle / STEP_RAD).rounded() * STEP_RAD
                let perpDist  = dist * abs(sin(rawAngle - snapAngle))
                let snapRadius: Float = reticleIsRealHit ? 0.10 : 0.30
                if perpDist < snapRadius {
                    let sx = gc.x + cos(snapAngle) * dist
                    let sz = gc.z + sin(snapAngle) * dist
                    lastSnappedReticle  = SIMD3(sx, lastFloorY, sz)
                    reticleIsSnapped    = true
                    goniometerSnapAngle = snapAngle
                    lastLivePreview     = lastSnappedReticle
                } else {
                    lastSnappedReticle  = baseReticle
                    reticleIsSnapped    = false
                    goniometerSnapAngle = nil
                }
            } else {
                lastSnappedReticle = baseReticle; reticleIsSnapped = false; goniometerSnapAngle = nil
            }
        } else {
            lastSnappedReticle = baseReticle; reticleIsSnapped = false; goniometerSnapAngle = nil
        }
        if gonioCenterPt != nil, let sr = lastSnappedReticle { lastLivePreview = sr }

        // ── Reticle libero AWAIT_HEIGHT
        if phase == .awaitHeight {
            if let rawFree = screenToWorldFallback(frame: frame, camera: camera) {
                heightXBuf[heightBufIdx] = rawFree.x
                heightYBuf[heightBufIdx] = rawFree.y
                heightZBuf[heightBufIdx] = rawFree.z
                heightBufIdx = (heightBufIdx + 1) % heightXBuf.count
                if heightBufFill < heightXBuf.count { heightBufFill += 1 }
                var hX: Float = 0; var hY: Float = 0; var hZ: Float = 0
                for k in 0..<heightBufFill { hX += heightXBuf[k]; hY += heightYBuf[k]; hZ += heightZBuf[k] }
                lastReticleWorldFree = SIMD3(hX / Float(heightBufFill),
                                             hY / Float(heightBufFill),
                                             hZ / Float(heightBufFill))
            }
        } else {
            heightBufIdx = 0; heightBufFill = 0; lastReticleWorldFree = nil
        }

        // ── Live height for preview
        let liveHeightM: Float? = (phase == .awaitHeight) ? wallHeightPreview : nil

        // ── Render perimeter
        let currentWorldPts  = frozenPolygon ?? perimeterCapture.getPolygon()
        let isClosed         = perimeterCapture.state == .closed
        let showGoniometer   = !openingMode && phase != .awaitHeight && !isClosed

        perimeterRenderer.draw(
            confirmedPts:        currentWorldPts,
            livePoint:           (openingMode || phase == .awaitHeight) ? nil : lastLivePreview,
            isClosed:            isClosed,
            canClose:            perimeterCapture.canClose,
            wallHeight:          wallHeightPreview,
            capturePhase:        phase,
            liveHeightM:         liveHeightM,
            viewMatrix:          viewMtx,
            projMatrix:          projMtx,
            reticleSnapped:      reticleIsSnapped,
            goniometerCenter:    showGoniometer ? goniometerCenterPt : nil,
            goniometerAngle:     goniometerCurrentAngle,
            goniometerSnapAngle: goniometerSnapAngle,
            currentFloorY:       lastFloorY,
            topCursorPoint:      lastTopCursorWorld
        )

        // ── Render openings
        if openingMode, let rm = roomModel {
            if let rw = lastReticleWorld { hoveredWallId = pickWall(reticle: rw, rm: rm) }
            let baseY = currentWorldPts.first?.y ?? frozenFloorY ?? lastFloorY
            openingRenderer.draw(
                roomModel:      rm,
                baseY:          baseY,
                hoveredWallId:  hoveredWallId,
                selectedWallId: selectedWallId,
                viewMatrix:     viewMtx,
                projMatrix:     projMtx
            )
        }

        // ── Reticle dot (2D UIKit layer updated from main thread, separate from GL)

        // ── UI throttled update (300ms)
        let now = Date().timeIntervalSince1970
        if now - lastUIUpdateTime >= 0.3 {
            lastUIUpdateTime = now
            let elapsed  = Int(Date().timeIntervalSince(scanStartTime))
            let floorLk  = floorAnchor.isLocked
            let ptCount  = perimeterCapture.pointCount
            let state    = perimeterCapture.state
            let lastLen  = perimeterCapture.lastSegmentLength()
            let liveDistM: Float? = {
                guard !openingMode && ptCount > 0, let rw = lastReticleWorld,
                      phase != .awaitHeight else { return nil }
                let pts = perimeterCapture.getPolygon()
                guard let lastPt = pts.last else { return nil }
                let dx = rw.x - lastPt.x; let dz = rw.z - lastPt.z
                return sqrt(dx*dx + dz*dz)
            }()
            DispatchQueue.main.async { [weak self] in
                guard let s = self else { return }
                s.updateUI(trackingOk: isTracking, floorLocked: floorLk,
                           state: state, phase: phase, ptCount: ptCount,
                           elapsed: elapsed, lastLen: lastLen, liveDistM: liveDistM,
                           liveHeightM: liveHeightM)
            }
        }
    }

    // MARK: – ARSessionDelegate

    func session(_ session: ARSession, didFailWithError error: Error) {
        DispatchQueue.main.async { [weak self] in
            self?.guidanceHeadline.text = "Errore AR: \(error.localizedDescription)"
        }
    }

    func session(_ session: ARSession, cameraDidChangeTrackingState camera: ARCamera) {
        let state: String
        switch camera.trackingState {
        case .normal:                       state = "TRACKING"
        case .limited(let reason):
            switch reason {
            case .initializing:             state = "INITIALIZING"
            case .excessiveMotion:          state = "EXCESSIVE_MOTION"
            case .insufficientFeatures:     state = "INSUFFICIENT_FEATURES"
            case .relocalizing:             state = "RELOCALIZING"
            @unknown default:               state = "LIMITED"
            }
        case .notAvailable:                 state = "NOT_AVAILABLE"
        @unknown default:                   state = "UNKNOWN"
        }
        manager?.onTrackingStateChanged?(state, nil)
    }

    // MARK: – screenToWorldFloorPlane (invariante assoluta — formula esatta dal piano)

    private func screenToWorldFloorPlane(camera: ARCamera) -> SIMD3<Float>? {
        guard floorAnchor.isLocked else { return nil }
        let t4  = camera.transform.columns.3
        let camPos = SIMD3<Float>(t4.x, t4.y, t4.z)
        let fwd4 = camera.transform * SIMD4<Float>(0, 0, -1, 0)
        let camFwd = SIMD3<Float>(fwd4.x, fwd4.y, fwd4.z)
        let dy = camFwd.y
        guard dy < -0.01 else { return nil }
        let t = (lastFloorY - camPos.y) / dy
        guard t >= 0.05 && t <= 15 else { return nil }
        return SIMD3(camPos.x + t * camFwd.x, lastFloorY, camPos.z + t * camFwd.z)
    }

    // MARK: – screenToWorldTopPlane (invariante assoluta — formula esatta dal piano)

    private func screenToWorldTopPlane(camera: ARCamera) -> SIMD3<Float>? {
        guard floorAnchor.isLocked else { return nil }
        let wallTopY = lastFloorY + wallHeightPreview
        let t4   = camera.transform.columns.3
        let camPos = SIMD3<Float>(t4.x, t4.y, t4.z)
        let fwd4 = camera.transform * SIMD4<Float>(0, 0, -1, 0)
        let camFwd = SIMD3<Float>(fwd4.x, fwd4.y, fwd4.z)
        let dy = camFwd.y
        guard dy > 0.01 else { return nil }
        let t = (wallTopY - camPos.y) / dy
        guard t >= 0.05 && t <= 15 else { return nil }
        return SIMD3(camPos.x + t * camFwd.x, lastFloorY, camPos.z + t * camFwd.z)
    }

    // MARK: – Fallback (pre-lock, AWAIT_HEIGHT)

    private func screenToWorldFallback(frame: ARFrame, camera: ARCamera) -> SIMD3<Float>? {
        if floorAnchor.isLocked { return screenToWorldFloorPlane(camera: camera) }
        // Pre-lock: 2m forward fallback
        let t4 = camera.transform.columns.3
        let fwd4 = camera.transform * SIMD4<Float>(0, 0, -2, 1)
        return SIMD3(fwd4.x, fwd4.y, fwd4.z)
    }

    // MARK: – Wall picking

    private func pickWall(reticle: SIMD3<Float>, rm: RoomModel) -> String? {
        let rx = reticle.x; let rz = reticle.z
        var bestId: String?; var bestDist: Float = 1.5
        for wall in rm.walls {
            let d = pointToSegmentDist(rx, rz,
                                       wall.start.x, wall.start.z,
                                       wall.end.x,   wall.end.z)
            if d < bestDist { bestDist = d; bestId = wall.id }
        }
        return bestId
    }

    private func pointToSegmentDist(_ px: Float, _ pz: Float,
                                     _ ax: Float, _ az: Float,
                                     _ bx: Float, _ bz: Float) -> Float {
        let dx = bx - ax; let dz = bz - az
        let lenSq = dx*dx + dz*dz
        if lenSq < 1e-10 { return sqrt((px-ax)*(px-ax) + (pz-az)*(pz-az)) }
        let t  = min(max(((px-ax)*dx + (pz-az)*dz) / lenSq, 0), 1)
        let cx = ax + t * dx; let cz = az + t * dz
        return sqrt((px-cx)*(px-cx) + (pz-cz)*(pz-cz))
    }

    // MARK: – Perimeter tap

    @objc private func handleViewTap(_ gesture: UITapGestureRecognizer) {
        guard gesture.state == .ended else { return }
        if openingMode {
            handleOpeningTap()
        } else {
            handlePerimeterTap()
        }
    }

    private func handlePerimeterTap() {
        if perimeterCapture.capturePhase == .awaitHeight {
            perimeterCapture.addPoint(SIMD3(0, wallHeightPreview, 0))
            updateCaptureUI()
            return
        }
        let rw = lastSnappedReticle ?? {
            if reticleTopMode { return screenToWorldTopPlane(camera: arView.session.currentFrame!.camera) }
            return screenToWorldFloorPlane(camera: arView.session.currentFrame!.camera)
        }()
        guard let pt = rw else { return }
        perimeterCapture.addPoint(pt)
        updateCaptureUI()
    }

    // MARK: – Opening mode

    private func handleOpeningTap() {
        guard let wid = hoveredWallId else { return }
        selectedWallId = wid
        DispatchQueue.main.async { self.showOpeningTypeDialog() }
    }

    private func buildRoomModel() {
        let poly      = frozenPolygon ?? perimeterCapture.getPolygon()
        let rectified = RoomRectifier.rectify(poly).polygon
        roomModel     = RoomModel.fromPolygon(rectified, wallHeight: wallHeightPreview)
    }

    private func enterOpeningMode() {
        if roomModel == nil { buildRoomModel() }
        openingMode    = true
        selectedWallId = nil
        editingOpening = nil
        confirmBtn.isHidden       = true
        heightControlView.isHidden = true
        openingPhaseView.isHidden = false
        openingTypeRow.isHidden   = false
        openingEditPanel.isHidden = true
        actionBtn.setTitle("ESPORTA", for: .normal)
        actionBtn.backgroundColor = UIColor(red: 0.10, green: 0.35, blue: 0.90, alpha: 0.92)
        actionBtn.layer.borderColor = UIColor(red: 0.30, green: 0.55, blue: 1.0, alpha: 0.6).cgColor
        actionBtn.isEnabled = true
        actionBtn.isHidden  = false
        undoBtn.isHidden    = true
        guidanceHeadline.text = "Aggiungi aperture"
        guidanceSubtext.text  = "Punta un muro e toccalo per selezionarlo"
    }

    private func showOpeningTypeDialog() {
        struct Entry { let label: String; let action: () -> Void }
        var entries = [Entry]()

        let unlinked = UnlinkedOpeningStore.shared.loadAll()
        for u in unlinked {
            let dest = u.customLabel.isEmpty ? u.sourceRoomName : u.customLabel
            let lbl  = "↔ Collega a \"\(dest)\" (\(String(format: "%.2f", u.width))m)"
            entries.append(Entry(label: lbl) { [weak self] in
                self?.spawnWithClassification(kind: u.kind, status: .linked, target: u)
            })
        }
        entries.append(Entry(label: "Porta d'ingresso (esterna)") { [weak self] in
            self?.spawnWithClassification(kind: .door, status: .external, target: nil)
        })
        entries.append(Entry(label: "Finestra") { [weak self] in
            self?.spawnWithClassification(kind: .window, status: .external, target: nil)
        })
        entries.append(Entry(label: "Porta interna — nuova stanza") { [weak self] in
            self?.showPendingLabelDialog(kind: .door)
        })
        entries.append(Entry(label: "Portafinestra interna — nuova stanza") { [weak self] in
            self?.showPendingLabelDialog(kind: .frenchDoor)
        })

        let title = unlinked.isEmpty ? "Tipo apertura" : "Collega apertura"
        let alert = UIAlertController(title: title, message: nil, preferredStyle: .actionSheet)
        for e in entries {
            alert.addAction(UIAlertAction(title: e.label, style: .default) { _ in e.action() })
        }
        alert.addAction(UIAlertAction(title: "Annulla", style: .cancel) { [weak self] _ in
            self?.selectedWallId = nil
        })
        if let pop = alert.popoverPresentationController {
            pop.sourceView = view
            pop.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.maxY - 100, width: 0, height: 0)
        }
        present(alert, animated: true)
    }

    private func showPendingLabelDialog(kind: OpeningKind) {
        let alert = UIAlertController(title: "Verso quale ambiente porta?",
                                      message: nil, preferredStyle: .alert)
        alert.addTextField { tf in tf.placeholder = "es. cucina, salotto, bagno" }
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self, weak alert] _ in
            let label = alert?.textFields?.first?.text?.trimmingCharacters(in: .whitespaces) ?? ""
            self?.spawnWithClassification(kind: kind, status: .pending, target: nil, customLabel: label)
        })
        alert.addAction(UIAlertAction(title: "Salta", style: .cancel) { [weak self] _ in
            self?.spawnWithClassification(kind: kind, status: .pending, target: nil)
        })
        present(alert, animated: true)
    }

    private func spawnWithClassification(kind: OpeningKind, status: ConnectionStatus,
                                         target: UnlinkedOpening?, customLabel: String = "") {
        guard let wid  = selectedWallId,
              var rm   = roomModel,
              let wIdx = rm.walls.firstIndex(where: { $0.id == wid }) else { return }
        var wall = rm.walls[wIdx]

        let width  = target?.width  ?? kind.defaultWidth
        let height = target?.height ?? kind.defaultHeight
        let bottom = target?.bottom ?? kind.defaultBottom

        let o_id = "op_\(Int(Date().timeIntervalSince1970 * 1000))"
        var opening = OpeningModel(
            id:              o_id,
            wallId:          wid,
            kind:            kind,
            offsetAlongWall: max((wall.length - width) / 2, 0.10),
            width:           width,
            bottom:          bottom,
            height:          height
        )
        wall.clampOpening(&opening)
        wall.openings.append(opening)
        rm.walls[wIdx] = wall
        roomModel      = rm
        editingOpening = opening

        pendingClassification[opening.id] = (status, target, wIdx)
        if !customLabel.isEmpty { pendingLabels[opening.id] = customLabel }

        DispatchQueue.main.async { self.showOpeningEditPanel(opening) }
    }

    private func showOpeningEditPanel(_ o: OpeningModel) {
        openingEditTitle.text = o.kind.label
        openingBottomView.isHidden = (o.kind != .window)
        openingEditPanel.isHidden  = false
        openingTypeRow.isHidden    = true
        refreshOpeningValues(o)
        guidanceSubtext.text = "Regola la posizione e le dimensioni"
    }

    private func refreshOpeningValues(_ o: OpeningModel) {
        openingPosLabel.text   = String(format: "%.2fm", o.offsetAlongWall)
        openingWidthLabel.text = String(format: "%.2fm", o.width)
        openingHeightLbl.text  = String(format: "%.2fm", o.height)
        openingBottomLbl.text  = String(format: "%.2fm", o.bottom)
    }

    @objc private func nudgeOpening(_ sender: UIButton) {
        guard var rm  = roomModel,
              let eid = editingOpening?.id,
              let wIdx = rm.walls.firstIndex(where: { $0.openings.contains(where: { $0.id == eid }) }),
              let oIdx = rm.walls[wIdx].openings.firstIndex(where: { $0.id == eid }) else { return }
        let delta: Float = sender.tag == 1 ? +0.05 : -0.05
        rm.walls[wIdx].openings[oIdx].offsetAlongWall += delta
        var o = rm.walls[wIdx].openings[oIdx]
        rm.walls[wIdx].clampOpening(&o)
        rm.walls[wIdx].openings[oIdx] = o
        roomModel = rm; editingOpening = o
        refreshOpeningValues(o)
    }

    @objc private func adjustOpeningWidth(_ sender: UIButton) {
        adjustOpening(dW: sender.tag == 1 ? +0.05 : -0.05)
    }
    @objc private func adjustOpeningHeight(_ sender: UIButton) {
        adjustOpening(dH: sender.tag == 1 ? +0.05 : -0.05)
    }
    @objc private func adjustOpeningBottom(_ sender: UIButton) {
        adjustOpening(dB: sender.tag == 1 ? +0.05 : -0.05)
    }

    private func adjustOpening(dW: Float = 0, dH: Float = 0, dB: Float = 0) {
        guard var rm  = roomModel,
              let eid = editingOpening?.id,
              let wIdx = rm.walls.firstIndex(where: { $0.openings.contains(where: { $0.id == eid }) }),
              let oIdx = rm.walls[wIdx].openings.firstIndex(where: { $0.id == eid }) else { return }
        var o = rm.walls[wIdx].openings[oIdx]
        if dW != 0 {
            let center   = o.offsetAlongWall + o.width / 2
            let wall     = rm.walls[wIdx]
            o.width           = min(max(o.width + dW, 0.30), wall.length - 0.20)
            o.offsetAlongWall = center - o.width / 2
        }
        if dH != 0 { o.height = min(max(o.height + dH, 0.30), wallHeightPreview) }
        if dB != 0 { o.bottom = min(max(o.bottom + dB, 0.00), wallHeightPreview - o.height) }
        rm.walls[wIdx].clampOpening(&o)
        rm.walls[wIdx].openings[oIdx] = o
        roomModel = rm; editingOpening = o
        refreshOpeningValues(o)
    }

    @objc private func confirmOpening() {
        guard let confirmed = editingOpening else { return }
        editingOpening = nil; selectedWallId = nil
        openingEditPanel.isHidden = true
        openingTypeRow.isHidden   = false
        guidanceSubtext.text = "Punta un altro muro o premi Esporta"
        applyClassification(confirmed)
    }

    private func applyClassification(_ opening: OpeningModel) {
        let (status, target, wallIdx) = pendingClassification.removeValue(forKey: opening.id)
            ?? (.external, nil, 0)

        switch status {
        case .external:
            openingMetadataMap[opening.id] = OpeningMetadata(
                openingId: opening.id, wallId: opening.wallId,
                isInternal: false, linkedRoomId: nil, connectionLabel: nil,
                connectionStatus: .external)

        case .pending:
            openingMetadataMap[opening.id] = OpeningMetadata(
                openingId: opening.id, wallId: opening.wallId,
                isInternal: true, linkedRoomId: nil, connectionLabel: nil,
                connectionStatus: .pending)
            pendingUnlinkedOpenings.append((opening.id, UnlinkedOpening(
                id:             "uop_\(UUID().uuidString)",
                sourceRoomId:   "",   // popolato in doStopScan dopo save
                sourceRoomName: currentRoomName,
                openingId:      opening.id,
                kind:           opening.kind,
                width:          opening.width,
                height:         opening.height,
                bottom:         opening.bottom,
                wallIndex:      wallIdx,
                customLabel:    pendingLabels.removeValue(forKey: opening.id) ?? ""
            )))

        case .linked:
            if let t = target {
                openingMetadataMap[opening.id] = OpeningMetadata(
                    openingId: opening.id, wallId: opening.wallId,
                    isInternal: true, linkedRoomId: t.sourceRoomId,
                    connectionLabel: t.sourceRoomName, connectionStatus: .linked)
                pendingLinkUpdates.append(t)
                if pendingComposerRoomId == nil {
                    pendingComposerRoomId          = t.sourceRoomId
                    pendingComposerLinkKind        = t.kind.rawValue
                    pendingComposerLinkWidth       = t.width
                    pendingComposerParentOpeningId = t.openingId
                    pendingComposerNewOpeningId    = opening.id
                }
            }
        }
    }

    @objc private func deleteOpening() {
        guard var rm  = roomModel,
              let eid = editingOpening?.id,
              let wIdx = rm.walls.firstIndex(where: { $0.openings.contains(where: { $0.id == eid }) }),
              let oIdx = rm.walls[wIdx].openings.firstIndex(where: { $0.id == eid }) else { return }
        rm.walls[wIdx].openings.remove(at: oIdx)
        roomModel = rm; editingOpening = nil; selectedWallId = nil
        openingEditPanel.isHidden = true; openingTypeRow.isHidden = false
        guidanceSubtext.text = "Apertura eliminata · seleziona un muro"
    }

    // MARK: – Confirm corner button

    @objc private func confirmCornerTapped() { handlePerimeterTap() }

    // MARK: – Cancel / undo button

    @objc private func cancelTapped() {
        if openingMode {
            if editingOpening != nil {
                // Dismiss edit panel → back to type row
                editingOpening = nil
                openingEditPanel.isHidden = true
                openingTypeRow.isHidden   = false
                guidanceSubtext.text = "Seleziona tipo apertura"
            } else if selectedWallId != nil {
                // Deselect wall
                selectedWallId = nil
                openingTypeRow.isHidden = false
                guidanceSubtext.text = "Punta un muro e toccalo per selezionarlo"
            } else {
                exitScanSafely()
            }
            return
        }
        if perimeterCapture.canUndo {
            perimeterCapture.undo()
            updateCaptureUI()
        } else {
            exitScanSafely()
        }
    }

    // MARK: – Action button

    @objc private func actionTapped() {
        if openingMode {
            actionBtn.isEnabled = false
            actionBtn.setTitle("Elaborazione…", for: .normal)
            doStopScan()
            return
        }
        let state = perimeterCapture.state
        if state == .closed {
            buildRoomModel()
            enterOpeningMode()
        } else if perimeterCapture.canClose {
            perimeterCapture.close()
            frozenPolygon = perimeterCapture.getPolygon().map { $0 }
            frozenFloorY  = lastFloorY
            updateCaptureUI()
        } else {
            exitScanSafely()
        }
    }

    // MARK: – Stop scan

    /// Called by SpatialScanManager.requestStop() — triggers the export flow.
    func triggerStop() {
        DispatchQueue.main.async {
            self.actionBtn.isEnabled = false
            self.actionBtn.setTitle("Elaborazione…", for: .normal)
            self.doStopScan()
        }
    }

    private func doStopScan() {
        let poly = frozenPolygon ?? perimeterCapture.getPolygon()
        guard !poly.isEmpty else {
            DispatchQueue.main.async { self.dismiss(animated: true) { self.onScanCancelled?() } }
            return
        }
        let rectified = RoomRectifier.rectify(poly).polygon

        // Calcola area con shoelace
        var area: Double = 0
        let n = rectified.count
        for i in 0..<n {
            let a = rectified[i]; let b = rectified[(i + 1) % n]
            area += Double(a.x * b.z - b.x * a.z)
        }
        area = abs(area) / 2.0

        // Perimetro
        let perimeter = (roomModel?.walls.reduce(0.0) { $0 + Double($1.length) } ?? 0) * 0.5

        // Bounding box
        let allX = rectified.flatMap { [Double($0.x)] }
        let allZ = rectified.flatMap { [Double($0.z)] }
        let bWidth  = (allX.max() ?? 0) - (allX.min() ?? 0)
        let bLength = (allZ.max() ?? 0) - (allZ.min() ?? 0)

        // Walls JSON (formato Composer: id, startPoint, endPoint, openings con metadata)
        var wallsJson = [[String: Any]]()
        if let rm = roomModel {
            for wall in rm.walls {
                var openingsJson = [[String: Any]]()
                for op in wall.openings {
                    let meta = openingMetadataMap[op.id]
                    openingsJson.append([
                        "id":               op.id,
                        "kind":             op.kind.rawValue,
                        "offsetAlongWall":  op.offsetAlongWall,
                        "width":            op.width,
                        "height":           op.height,
                        "bottom":           op.bottom,
                        "isInternal":       meta?.isInternal ?? false,
                        "connectionStatus": meta?.connectionStatus.rawValue ?? ConnectionStatus.external.rawValue,
                        "linkedRoomId":     meta?.linkedRoomId ?? "",
                        "connectionLabel":  meta?.connectionLabel ?? ""
                    ] as [String: Any])
                }
                wallsJson.append([
                    "id": wall.id,
                    "startPoint": ["x": wall.start.x, "z": wall.start.z],
                    "endPoint":   ["x": wall.end.x,   "z": wall.end.z],
                    "height":     wall.height,
                    "openings":   openingsJson
                ])
            }
        }

        // Floor JSON
        let floorVertices = rectified.map { ["x": $0.x, "z": $0.z] }
        let floorJson: [String: Any] = ["vertices": floorVertices, "area": area]

        // RoomDimensions JSON
        let dimsJson: [String: Any] = [
            "width":     bWidth,
            "length":    bLength,
            "height":    Double(wallHeightPreview),
            "area":      area,
            "perimeter": perimeter
        ]

        let roomData: [String: Any] = [
            "name":           currentRoomName,
            "walls":          wallsJson,
            "floor":          floorJson,
            "roomDimensions": dimsJson,
            "floorPlanPath":  "",
            "glbPath":        ""
        ]

        // Salva con RoomHistoryManager
        let savedRecord = RoomHistoryManager.shared.save(roomData: roomData, name: currentRoomName)
        let realRoomId  = savedRecord?.id ?? UUID().uuidString

        // Processa aperture PENDING → UnlinkedOpeningStore
        for (_, entry) in pendingUnlinkedOpenings {
            UnlinkedOpeningStore.shared.add(entry.withSourceRoomId(realRoomId))
        }
        pendingUnlinkedOpenings.removeAll()

        // Processa collegamenti bilaterali (LINKED)
        for target in pendingLinkUpdates {
            RoomHistoryManager.shared.updateOpeningMetadata(
                roomId:        target.sourceRoomId,
                openingId:     target.openingId,
                linkedRoomId:  realRoomId,
                linkedRoomName: currentRoomName
            )
            UnlinkedOpeningStore.shared.remove(id: target.id)
        }
        pendingLinkUpdates.removeAll()

        // Pre-calcola se verrà presentato il Composer (regola M4/5/6)
        let composerRoomId          = pendingComposerRoomId
        let composerLinkKind        = pendingComposerLinkKind
        let composerLinkWidth       = pendingComposerLinkWidth
        let composerParentOpeningId = pendingComposerParentOpeningId
        let composerNewOpeningId    = pendingComposerNewOpeningId
        pendingComposerRoomId = nil

        let otherRooms = savedRecord != nil
            ? RoomHistoryManager.shared.loadAll().filter { $0.id != realRoomId }
            : []
        let anchorId: String? = savedRecord != nil
            ? (composerRoomId
               ?? CompositionGraph.shared.loadAll().first?.roomId
               ?? otherRooms.last?.id)
            : nil
        let willPresentComposer = anchorId != nil

        // Se non c'è Composer: prepara export singola dalla geometria live
        let singleExportData: RoomExportData?
        if !willPresentComposer, let rm = roomModel {
            let base = RoomExportData.fromRoomModel(rm)
            singleExportData = RoomExportData(
                walls:        base.walls,
                dimensions:   base.dimensions,
                roomPolygons: [(currentRoomName, rectified.map { ($0.x, $0.z) })]
            )
        } else {
            singleExportData = nil
        }

        let cacheDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory

        var result: [String: Any] = [
            "success":        true,
            "roomId":         realRoomId,
            "walls":          wallsJson,
            "roomDimensions": dimsJson,
            "area":           area,
            "wallCount":      wallsJson.count,
            "floorPlanPath":  "",
            "glbPath":        ""
        ]

        DispatchQueue.main.async {
            self.dismiss(animated: true) {
                if willPresentComposer, let record = savedRecord, let anchorId = anchorId {
                    // Composer segue: solo housekeeping, ZERO eventi JS.
                    // Il Composer chiamerà manager.composerDidConfirm() con il risultato finale.
                    NSLog("[HUB_DIAG] ScanningVC: willPresentComposer=true, anchorId=%@, newRoomId=%@",
                          anchorId, record.id)
                    self.manager?.beginComposerPhase()
                    let composerVC = RoomComposerViewController()
                    composerVC.parentRoomId        = anchorId
                    composerVC.newRoomId           = record.id
                    composerVC.newRoomName         = self.currentRoomName
                    composerVC.manager             = self.manager
                    if composerRoomId != nil {
                        composerVC.linkKind            = composerLinkKind
                        composerVC.linkWidth           = composerLinkWidth
                        composerVC.linkParentOpeningId = composerParentOpeningId
                        composerVC.linkNewOpeningId    = composerNewOpeningId
                    }
                    composerVC.modalPresentationStyle = .fullScreen
                    UIApplication.shared.hubTopViewController()?.present(composerVC, animated: true)
                } else if let data = singleExportData {
                    // Nessun Composer: genera PNG + GLB + PDF in background, poi onScanFinished con paths.
                    DispatchQueue.global(qos: .userInitiated).async {
                        let pngPath = FloorPlanExporter.export(data: data, cacheDir: cacheDir)
                        let glbPath = GlbExporter.export(data: data, cacheDir: cacheDir)
                        let pdfPath = FloorPlanExporter.exportPdf(data: data, cacheDir: cacheDir)
                        if let p = pdfPath {
                            UserDefaults.standard.set(p, forKey: "hub_lastPdfPath")
                        }
                        result["floorPlanPath"] = pngPath ?? ""
                        result["glbPath"]       = glbPath ?? ""
                        DispatchQueue.main.async {
                            self.onScanFinished?(result)
                            self.showContinueScanDialog { }
                        }
                    }
                } else {
                    self.onScanFinished?(result)
                    self.showContinueScanDialog { }
                }
            }
        }
    }

    private func showContinueScanDialog(onFinish: @escaping () -> Void) {
        let alert = UIAlertController(title: "Scan completata",
                                      message: "Vuoi scansionare un altro ambiente?",
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Sì", style: .default) { [weak self] _ in
            onFinish()
            self?.manager?.startContinuationScan()
        })
        alert.addAction(UIAlertAction(title: "No", style: .cancel) { _ in
            UserDefaults.standard.set(true, forKey: "hub_sessionEnded")
            onFinish()
        })
        // Present from the top VC after dismiss completes
        UIApplication.shared.hubTopViewController()?.present(alert, animated: true)
    }

    private func clearSessionData() {
        RoomHistoryManager.shared.clearAll()
        UnlinkedOpeningStore.shared.clear()
        CompositionGraph.shared.clear()
        UserDefaults.standard.removeObject(forKey: "hub_lastPdfPath")
    }

    // MARK: – Exit safely

    private func exitScanSafely() {
        let alert = UIAlertController(title: "Uscire dalla scansione?",
                                      message: "I dati non salvati andranno persi.",
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Esci", style: .destructive) { [weak self] _ in
            self?.dismiss(animated: true) { self?.onScanCancelled?() }
        })
        alert.addAction(UIAlertAction(title: "Continua", style: .cancel))
        present(alert, animated: true)
    }

    // MARK: – Room name dialog

    private func showRoomNameAlert() {
        let alert = UIAlertController(title: "Nome stanza", message: nil, preferredStyle: .alert)
        alert.addTextField { $0.placeholder = "es. Soggiorno" }
        alert.addAction(UIAlertAction(title: "Inizia", style: .default) { [weak self, weak alert] _ in
            self?.currentRoomName = alert?.textFields?.first?.text?.isEmpty == false
                ? alert!.textFields!.first!.text!
                : "Stanza"
            self?.startSession()
        })
        alert.addAction(UIAlertAction(title: "Annulla", style: .cancel) { [weak self] _ in
            self?.dismiss(animated: true) { self?.onScanCancelled?() }
        })
        present(alert, animated: true)
    }

    private func startSession() {
        perimeterCapture.reset(); floorAnchor.reset()
        frozenPolygon = nil; frozenFloorY = nil
        lastReticleWorld = nil; lastLivePreview = nil; lastReticleWorldFree = nil
        lastSnappedReticle = nil; reticleIsSnapped = false; reticleIsRealHit = false
        goniometerCenterPt = nil; goniometerCurrentAngle = 0; goniometerSnapAngle = nil
        reticleTopMode = false; lastTopCursorWorld = nil
        reticleBufIdx = 0; reticleBufFill = 0
        heightBufIdx  = 0; heightBufFill  = 0
        scanStartTime = Date()
        updateCaptureUI()
        guidanceHeadline.text = "Inizializzazione…"
        guidanceSubtext.text  = "Muoviti lentamente, punta verso il pavimento"
        glView.isHidden = false
    }

    // MARK: – UI update

    private func updateCaptureUI() {
        let state    = perimeterCapture.state
        let isClosed = state == .closed
        let phase    = perimeterCapture.capturePhase
        let canClose = perimeterCapture.canClose
        let locked   = floorAnchor.isLocked

        confirmBtn.isHidden        = isClosed || !locked
        heightBanner.isHidden      = !(phase == .awaitHeight && !isClosed)
        heightControlView.isHidden = openingMode || !(isClosed || phase == .awaitHeight)
        heightValueLabel.text      = String(format: "%.2fm", wallHeightPreview)

        if !openingMode {
            if isClosed {
                actionBtn.setTitle("Aggiungi Aperture", for: .normal); actionBtn.isHidden = false
            } else if canClose {
                actionBtn.setTitle("Chiudi Poligono", for: .normal); actionBtn.isHidden = false
            } else {
                actionBtn.isHidden = true
            }
        }
        undoBtn.isEnabled = perimeterCapture.canUndo && !isClosed
    }

    private func updateUI(
        trackingOk: Bool, floorLocked: Bool,
        state: PerimeterCapture.State, phase: PerimeterCapture.CapturePhase,
        ptCount: Int, elapsed: Int, lastLen: Float?, liveDistM: Float?,
        liveHeightM: Float?
    ) {
        let isClosed = state == .closed

        let (hl, sub): (String, String) = {
            if openingMode        { return ("Aperture", "Punta un muro e toccalo per selezionarlo") }
            if !trackingOk        { return ("Tracking perso", "Muoviti lentamente e guarda le superfici") }
            if !floorLocked       { return ("Inizializzazione…", "Punta verso il pavimento") }
            if isClosed           { return ("Stanza chiusa", "Regola altezza · poi aggiungi aperture") }
            if phase == .awaitFirstFloor { return ("Posizionati in un angolo", "Punta alla BASE del muro · TAP") }
            if phase == .awaitHeight     { return ("Imposta altezza pareti", "Usa + / − poi premi ✓") }
            if phase == .awaitSecondFloor { return ("Prima parete!", "Cammina lungo il muro · TAP angolo seguente") }
            if perimeterCapture.canClose { return ("Aggiungi angoli o chiudi", "TAP angolo · ↩ correggi · Chiudi") }
            return ("Cammina verso il prossimo angolo", "Punta alla giunzione muro-pavimento · TAP")
        }()
        guidanceHeadline.text = hl
        guidanceSubtext.text  = sub
        timerLabel.text = "\(elapsed)s"

        if let lastLen = lastLen {
            sideBadge.text = "\(ptCount) lat\(ptCount == 1 ? "o" : "i") · ultimo: \(String(format: "%.2f", lastLen))m"
        } else {
            sideBadge.text = "\(ptCount) punt\(ptCount == 1 ? "o" : "i")"
        }

        distanceLabel.text = {
            if let h = liveHeightM  { return String(format: "%.2f m", h) }
            if let d = liveDistM, d > 0.08 { return String(format: "%.2f m", d) }
            return ""
        }()

        updateCaptureUI()

    }

    // MARK: – Build UI

    private func buildUI() {
        // Top HUD strip
        let topHud = UILabel()
        topHud.text = ">>>  · · · · · · · · · · · · · · ·  <<<"
        topHud.textColor = UIColor(red: 30/255, green: 190/255, blue: 255/255, alpha: 0.43)
        topHud.font = .monospacedSystemFont(ofSize: 9, weight: .regular)
        topHud.textAlignment = .center
        topHud.backgroundColor = UIColor(white: 0, alpha: 0.51)
        topHud.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(topHud)
        NSLayoutConstraint.activate([
            topHud.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            topHud.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            topHud.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            topHud.heightAnchor.constraint(equalToConstant: 28)
        ])

        // Guidance
        guidanceHeadline.text = "Inizializzazione…"
        guidanceHeadline.textColor = .white
        guidanceHeadline.font = .systemFont(ofSize: 19, weight: .bold)
        guidanceHeadline.textAlignment = .center
        guidanceHeadline.numberOfLines = 0
        guidanceHeadline.layer.shadowColor = UIColor.black.cgColor
        guidanceHeadline.layer.shadowRadius = 8; guidanceHeadline.layer.shadowOpacity = 0.9
        guidanceHeadline.layer.shadowOffset = .zero

        guidanceSubtext.text = "Muoviti verso il pavimento"
        guidanceSubtext.textColor = UIColor(red: 20/255, green: 215/255, blue: 255/255, alpha: 0.82)
        guidanceSubtext.font = .systemFont(ofSize: 14)
        guidanceSubtext.textAlignment = .center
        guidanceSubtext.numberOfLines = 0
        guidanceSubtext.layer.shadowColor = UIColor.black.cgColor
        guidanceSubtext.layer.shadowRadius = 6; guidanceSubtext.layer.shadowOpacity = 0.8

        let guidanceStack = UIStackView(arrangedSubviews: [guidanceHeadline, guidanceSubtext])
        guidanceStack.axis = .vertical; guidanceStack.spacing = 4
        guidanceStack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(guidanceStack)
        NSLayoutConstraint.activate([
            guidanceStack.topAnchor.constraint(equalTo: topHud.bottomAnchor, constant: 8),
            guidanceStack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            guidanceStack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24)
        ])

        // Distance label (large, floating center)
        distanceLabel.text = ""
        distanceLabel.textColor = UIColor(red: 230/255, green: 30/255, blue: 155/255, alpha: 1)
        distanceLabel.font = .systemFont(ofSize: 32, weight: .bold)
        distanceLabel.textAlignment = .center
        distanceLabel.layer.shadowColor = UIColor(red: 200/255, green: 20/255, blue: 130/255, alpha: 0.8).cgColor
        distanceLabel.layer.shadowRadius = 8; distanceLabel.layer.shadowOpacity = 1
        distanceLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(distanceLabel)
        NSLayoutConstraint.activate([
            distanceLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            distanceLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: 90)
        ])

        // Height banner
        heightBanner.text = "IMPOSTA ALTEZZA"
        heightBanner.textColor = .white
        heightBanner.font = .systemFont(ofSize: 21, weight: .bold)
        heightBanner.textAlignment = .center
        heightBanner.backgroundColor = UIColor(red: 185/255, green: 18/255, blue: 125/255, alpha: 0.98)
        heightBanner.layer.cornerRadius = 4; heightBanner.layer.masksToBounds = true
        heightBanner.isHidden = true; heightBanner.isUserInteractionEnabled = false
        heightBanner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(heightBanner)
        NSLayoutConstraint.activate([
            heightBanner.topAnchor.constraint(equalTo: topHud.bottomAnchor, constant: 100),
            heightBanner.centerXAnchor.constraint(equalTo: view.centerXAnchor)
        ])

        // ── Bottom stack (Android-style vertical flow) ───────────────────────
        // Nessun topAnchor né heightAnchor — cresce verso l'alto in base al contenuto.
        bottomStack.axis         = .vertical
        bottomStack.alignment    = .fill
        bottomStack.distribution = .fill
        bottomStack.spacing      = 6
        bottomStack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bottomStack)
        NSLayoutConstraint.activate([
            bottomStack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            bottomStack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            bottomStack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -8)
        ])

        // 1. Stats row
        let statsRow = UIView()
        statsRow.translatesAutoresizingMaskIntoConstraints = false
        statsRow.heightAnchor.constraint(equalToConstant: 36).isActive = true

        sideBadge.text = "In attesa…"
        sideBadge.textColor = UIColor(red: 20/255, green: 215/255, blue: 255/255, alpha: 0.90)
        sideBadge.font = .systemFont(ofSize: 15, weight: .bold)

        timerLabel.text = "0s"
        timerLabel.textColor = UIColor(white: 1, alpha: 0.59)
        timerLabel.font = .systemFont(ofSize: 13)

        undoBtn.setTitle("↩", for: .normal)
        undoBtn.setTitleColor(UIColor(red: 140/255, green: 80/255, blue: 210/255, alpha: 0.78), for: .normal)
        undoBtn.titleLabel?.font = .systemFont(ofSize: 20)
        undoBtn.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        undoBtn.isEnabled = false

        [sideBadge, timerLabel, undoBtn].forEach {
            $0.translatesAutoresizingMaskIntoConstraints = false
            statsRow.addSubview($0)
        }
        NSLayoutConstraint.activate([
            sideBadge.leadingAnchor.constraint(equalTo: statsRow.leadingAnchor),
            sideBadge.centerYAnchor.constraint(equalTo: statsRow.centerYAnchor),
            undoBtn.trailingAnchor.constraint(equalTo: statsRow.trailingAnchor),
            undoBtn.centerYAnchor.constraint(equalTo: statsRow.centerYAnchor),
            timerLabel.trailingAnchor.constraint(equalTo: undoBtn.leadingAnchor, constant: -8),
            timerLabel.centerYAnchor.constraint(equalTo: statsRow.centerYAnchor)
        ])
        bottomStack.addArrangedSubview(statsRow)

        // 2. Opening phase UI — wrap_content naturale tramite openingPhaseStack
        buildOpeningPhaseUI()
        bottomStack.addArrangedSubview(openingPhaseView)

        // 3. Height control
        buildHeightControl()
        bottomStack.addArrangedSubview(heightControlView)

        // 4. Action button
        styleSecondaryButton(actionBtn, title: "Annulla")
        actionBtn.addTarget(self, action: #selector(actionTapped), for: .touchUpInside)
        actionBtn.isHidden = true
        actionBtn.translatesAutoresizingMaskIntoConstraints = false
        actionBtn.heightAnchor.constraint(equalToConstant: 36).isActive = true
        bottomStack.addArrangedSubview(actionBtn)

        // 5. Main row (↩ + ✓)
        let mainRow = UIView()
        mainRow.translatesAutoresizingMaskIntoConstraints = false
        mainRow.heightAnchor.constraint(equalToConstant: 110).isActive = true

        cancelBtn.setTitle("↩", for: .normal)
        cancelBtn.setTitleColor(.black, for: .normal)
        cancelBtn.titleLabel?.font = .systemFont(ofSize: 22, weight: .bold)
        cancelBtn.backgroundColor = .white
        cancelBtn.layer.cornerRadius = 32; cancelBtn.clipsToBounds = true
        cancelBtn.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        cancelBtn.translatesAutoresizingMaskIntoConstraints = false

        stylePrimaryButton(confirmBtn, title: "✓")
        confirmBtn.addTarget(self, action: #selector(confirmCornerTapped), for: .touchUpInside)
        confirmBtn.isHidden = true

        let pairRow = UIStackView(arrangedSubviews: [cancelBtn, confirmBtn])
        pairRow.axis = .horizontal; pairRow.spacing = 15; pairRow.alignment = .center
        pairRow.translatesAutoresizingMaskIntoConstraints = false
        mainRow.addSubview(pairRow)
        NSLayoutConstraint.activate([
            cancelBtn.widthAnchor.constraint(equalToConstant: 64),
            cancelBtn.heightAnchor.constraint(equalToConstant: 64),
            confirmBtn.widthAnchor.constraint(equalToConstant: 88),
            confirmBtn.heightAnchor.constraint(equalToConstant: 88),
            pairRow.centerXAnchor.constraint(equalTo: mainRow.centerXAnchor),
            pairRow.bottomAnchor.constraint(equalTo: mainRow.bottomAnchor, constant: -16)
        ])
        bottomStack.addArrangedSubview(mainRow)

        // Center crosshair reticle
        let reticleSize: CGFloat = 26
        let hBar = UIView()
        hBar.backgroundColor = UIColor(red: 20/255, green: 215/255, blue: 255/255, alpha: 0.90)
        hBar.layer.shadowColor = UIColor.black.cgColor
        hBar.layer.shadowRadius = 3; hBar.layer.shadowOpacity = 0.8; hBar.layer.shadowOffset = .zero
        hBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hBar)

        let vBar = UIView()
        vBar.backgroundColor = UIColor(red: 20/255, green: 215/255, blue: 255/255, alpha: 0.90)
        vBar.layer.shadowColor = UIColor.black.cgColor
        vBar.layer.shadowRadius = 3; vBar.layer.shadowOpacity = 0.8; vBar.layer.shadowOffset = .zero
        vBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(vBar)

        let dot = UIView()
        dot.backgroundColor = UIColor(red: 230/255, green: 30/255, blue: 155/255, alpha: 0.95)
        dot.layer.cornerRadius = 3; dot.clipsToBounds = true
        dot.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(dot)

        NSLayoutConstraint.activate([
            hBar.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            hBar.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            hBar.widthAnchor.constraint(equalToConstant: reticleSize),
            hBar.heightAnchor.constraint(equalToConstant: 2),

            vBar.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            vBar.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            vBar.widthAnchor.constraint(equalToConstant: 2),
            vBar.heightAnchor.constraint(equalToConstant: reticleSize),

            dot.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            dot.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            dot.widthAnchor.constraint(equalToConstant: 6),
            dot.heightAnchor.constraint(equalToConstant: 6)
        ])

        // Tap gesture on view (not on buttons)
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleViewTap(_:)))
        tap.cancelsTouchesInView = false
        arView.addGestureRecognizer(tap)
        glView.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(handleViewTap(_:))))
    }

    // MARK: – Opening phase UI

    /// Replica Android openingPhaseBar: UIStackView verticale wrap_content,
    /// aggiunto come arrangedSubview di bottomStack — nessun slot residuo.
    private func buildOpeningPhaseUI() {
        openingPhaseView.backgroundColor = UIColor(white: 0.05, alpha: 0.75)
        openingPhaseView.layer.cornerRadius = 12
        openingPhaseView.clipsToBounds = false          // non tagliare: l'altezza è quella del contenuto
        openingPhaseView.isHidden = true
        openingPhaseView.translatesAutoresizingMaskIntoConstraints = false

        // openingPhaseStack pinned ai 4 lati di openingPhaseView — propaga l'altezza verso l'alto
        openingPhaseStack.axis         = .vertical
        openingPhaseStack.alignment    = .fill
        openingPhaseStack.distribution = .fill
        openingPhaseStack.spacing      = 8
        openingPhaseStack.isLayoutMarginsRelativeArrangement = true
        openingPhaseStack.layoutMargins = UIEdgeInsets(top: 10, left: 12, bottom: 10, right: 12)
        openingPhaseStack.translatesAutoresizingMaskIntoConstraints = false
        openingPhaseView.addSubview(openingPhaseStack)
        NSLayoutConstraint.activate([
            openingPhaseStack.topAnchor.constraint(equalTo: openingPhaseView.topAnchor),
            openingPhaseStack.leadingAnchor.constraint(equalTo: openingPhaseView.leadingAnchor),
            openingPhaseStack.trailingAnchor.constraint(equalTo: openingPhaseView.trailingAnchor),
            openingPhaseStack.bottomAnchor.constraint(equalTo: openingPhaseView.bottomAnchor)
        ])

        // Header
        openingHeader.text = "APERTURE  ·  Punta un muro e selezionalo"
        openingHeader.textColor = UIColor(red: 20/255, green: 215/255, blue: 255/255, alpha: 1)
        openingHeader.font = .systemFont(ofSize: 13, weight: .bold)
        openingPhaseStack.addArrangedSubview(openingHeader)

        // Type row — UIStackView nello stack: collassa automaticamente con isHidden
        openingTypeRow.axis         = .horizontal
        openingTypeRow.spacing      = 4
        openingTypeRow.distribution = .fillEqually
        openingTypeRow.isHidden     = true
        for kind in [OpeningKind.door, .window, .frenchDoor] {
            let btn = UIButton(type: .custom)
            styleSecondaryButton(btn, title: kind.label)
            btn.addTarget(self, action: #selector(openingKindSelected(_:)), for: .touchUpInside)
            btn.tag = kind == .door ? 0 : kind == .window ? 1 : 2
            openingTypeRow.addArrangedSubview(btn)
        }
        openingPhaseStack.addArrangedSubview(openingTypeRow)

        // Edit panel — build poi aggiungi allo stack
        buildOpeningEditPanel()
        openingPhaseStack.addArrangedSubview(openingEditPanel)
    }

    @objc private func openingKindSelected(_ sender: UIButton) {
        if selectedWallId == nil { selectedWallId = hoveredWallId }
        showOpeningTypeDialog()
    }

    /// Replica Android openingEditPanel: UIStackView verticale senza ScrollView.
    private func buildOpeningEditPanel() {
        openingEditPanel.isHidden = true
        openingEditPanel.translatesAutoresizingMaskIntoConstraints = false

        // openingEditStack pinned ai 4 lati — determina l'altezza di openingEditPanel
        openingEditStack.axis         = .vertical
        openingEditStack.alignment    = .fill
        openingEditStack.distribution = .fill
        openingEditStack.spacing      = 8
        openingEditStack.translatesAutoresizingMaskIntoConstraints = false
        openingEditPanel.addSubview(openingEditStack)
        NSLayoutConstraint.activate([
            openingEditStack.topAnchor.constraint(equalTo: openingEditPanel.topAnchor),
            openingEditStack.leadingAnchor.constraint(equalTo: openingEditPanel.leadingAnchor),
            openingEditStack.trailingAnchor.constraint(equalTo: openingEditPanel.trailingAnchor),
            openingEditStack.bottomAnchor.constraint(equalTo: openingEditPanel.bottomAnchor)
        ])

        openingEditTitle.textColor = UIColor(red: 255/255, green: 200/255, blue: 80/255, alpha: 1)
        openingEditTitle.font = .systemFont(ofSize: 15, weight: .bold)
        openingEditTitle.textAlignment = .left

        let row1 = buildStepperRow("Sposta",      label: openingPosLabel,
                                    minusIcon: "←", plusIcon: "→",
                                    minusAction: #selector(nudgeOpening(_:)),
                                    plusAction:  #selector(nudgeOpening(_:)))
        let row2 = buildStepperRow("Larghezza ↔", label: openingWidthLabel,
                                    minusIcon: "←", plusIcon: "→",
                                    minusAction: #selector(adjustOpeningWidth(_:)),
                                    plusAction:  #selector(adjustOpeningWidth(_:)))
        let row3 = buildStepperRow("Altezza ↕",   label: openingHeightLbl,
                                    minusIcon: "↓", plusIcon: "↑",
                                    minusAction: #selector(adjustOpeningHeight(_:)),
                                    plusAction:  #selector(adjustOpeningHeight(_:)))

        openingBottomView.translatesAutoresizingMaskIntoConstraints = false
        let row4 = buildStepperRow("Quota",       label: openingBottomLbl,
                                    minusIcon: "↓", plusIcon: "↑",
                                    minusAction: #selector(adjustOpeningBottom(_:)),
                                    plusAction:  #selector(adjustOpeningBottom(_:)))
        openingBottomView.addSubview(row4)
        row4.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            row4.topAnchor.constraint(equalTo: openingBottomView.topAnchor),
            row4.bottomAnchor.constraint(equalTo: openingBottomView.bottomAnchor),
            row4.leadingAnchor.constraint(equalTo: openingBottomView.leadingAnchor),
            row4.trailingAnchor.constraint(equalTo: openingBottomView.trailingAnchor)
        ])

        let confirmOpeningBtn = UIButton(type: .custom)
        stylePrimaryButton(confirmOpeningBtn, title: "✓")
        confirmOpeningBtn.addTarget(self, action: #selector(confirmOpening), for: .touchUpInside)
        confirmOpeningBtn.translatesAutoresizingMaskIntoConstraints = false

        let deleteBtn = UIButton(type: .custom)
        deleteBtn.setTitle("🗑", for: .normal)
        deleteBtn.titleLabel?.font = .systemFont(ofSize: 22)
        deleteBtn.backgroundColor = .white
        deleteBtn.setTitleColor(UIColor(red: 185/255, green: 18/255, blue: 125/255, alpha: 1), for: .normal)
        deleteBtn.layer.cornerRadius = 28; deleteBtn.clipsToBounds = true
        deleteBtn.layer.borderWidth = 2
        deleteBtn.layer.borderColor = UIColor(red: 185/255, green: 18/255, blue: 125/255, alpha: 1).cgColor
        deleteBtn.addTarget(self, action: #selector(deleteOpening), for: .touchUpInside)
        deleteBtn.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            confirmOpeningBtn.widthAnchor.constraint(equalToConstant: 80),
            confirmOpeningBtn.heightAnchor.constraint(equalToConstant: 80),
            deleteBtn.widthAnchor.constraint(equalToConstant: 56),
            deleteBtn.heightAnchor.constraint(equalToConstant: 56)
        ])

        let actionRow = UIStackView(arrangedSubviews: [confirmOpeningBtn, deleteBtn])
        actionRow.axis = .horizontal; actionRow.spacing = 24; actionRow.alignment = .center
        actionRow.translatesAutoresizingMaskIntoConstraints = false
        actionRow.heightAnchor.constraint(equalToConstant: 84).isActive = true

        openingEditStack.addArrangedSubview(openingEditTitle)
        openingEditStack.addArrangedSubview(row1)
        openingEditStack.addArrangedSubview(row2)
        openingEditStack.addArrangedSubview(row3)
        openingEditStack.addArrangedSubview(openingBottomView)
        openingEditStack.addArrangedSubview(actionRow)
    }

    private func buildStepperRow(_ name: String, label: UILabel,
                                  minusIcon: String = "←", plusIcon: String = "→",
                                  minusAction: Selector, plusAction: Selector) -> UIStackView {
        let lbl = UILabel()
        lbl.text = name
        lbl.textColor = UIColor(white: 1, alpha: 0.70)
        lbl.font = .systemFont(ofSize: 12)
        lbl.widthAnchor.constraint(equalToConstant: 88).isActive = true

        label.text = "—"; label.textColor = .white
        label.font = .monospacedDigitSystemFont(ofSize: 14, weight: .bold)
        label.textAlignment = .center

        let minus = UIButton(type: .custom)
        minus.setTitle(minusIcon, for: .normal)
        minus.titleLabel?.font = .systemFont(ofSize: 20, weight: .medium)
        minus.setTitleColor(UIColor(red: 20/255, green: 215/255, blue: 255/255, alpha: 0.90), for: .normal)
        minus.backgroundColor = UIColor(white: 0.12, alpha: 0.85)
        minus.layer.cornerRadius = 22; minus.clipsToBounds = true
        minus.tag = 0; minus.addTarget(nil, action: minusAction, for: .touchUpInside)

        let plus = UIButton(type: .custom)
        plus.setTitle(plusIcon, for: .normal)
        plus.titleLabel?.font = .systemFont(ofSize: 20, weight: .medium)
        plus.setTitleColor(UIColor(red: 20/255, green: 215/255, blue: 255/255, alpha: 0.90), for: .normal)
        plus.backgroundColor = UIColor(white: 0.12, alpha: 0.85)
        plus.layer.cornerRadius = 22; plus.clipsToBounds = true
        plus.tag = 1; plus.addTarget(nil, action: plusAction, for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [lbl, minus, label, plus])
        stack.axis = .horizontal; stack.spacing = 10; stack.alignment = .center
        minus.widthAnchor.constraint(equalToConstant: 44).isActive = true
        minus.heightAnchor.constraint(equalToConstant: 44).isActive = true
        plus.widthAnchor.constraint(equalToConstant: 44).isActive = true
        plus.heightAnchor.constraint(equalToConstant: 44).isActive = true
        label.widthAnchor.constraint(equalToConstant: 60).isActive = true
        return stack
    }

    // MARK: – Height control

    private func buildHeightControl() {
        heightControlView.isHidden = true
        heightControlView.translatesAutoresizingMaskIntoConstraints = false

        let minus = UIButton(type: .custom)
        minus.setTitle("↓", for: .normal)
        minus.titleLabel?.font = .systemFont(ofSize: 22, weight: .bold)
        minus.setTitleColor(UIColor(red: 20/255, green: 215/255, blue: 255/255, alpha: 0.86), for: .normal)
        minus.backgroundColor = UIColor(white: 0.04, alpha: 0.7)
        minus.layer.cornerRadius = 30; minus.clipsToBounds = true
        minus.widthAnchor.constraint(equalToConstant: 60).isActive = true
        minus.heightAnchor.constraint(equalToConstant: 60).isActive = true
        minus.addTarget(self, action: #selector(heightMinus), for: .touchUpInside)

        let plus = UIButton(type: .custom)
        plus.setTitle("↑", for: .normal)
        plus.titleLabel?.font = .systemFont(ofSize: 22, weight: .bold)
        plus.setTitleColor(UIColor(red: 20/255, green: 215/255, blue: 255/255, alpha: 0.86), for: .normal)
        plus.backgroundColor = UIColor(white: 0.04, alpha: 0.7)
        plus.layer.cornerRadius = 30; plus.clipsToBounds = true
        plus.widthAnchor.constraint(equalToConstant: 60).isActive = true
        plus.heightAnchor.constraint(equalToConstant: 60).isActive = true
        plus.addTarget(self, action: #selector(heightPlus), for: .touchUpInside)

        heightValueLabel.text = "2.50m"
        heightValueLabel.textColor = UIColor(red: 30/255, green: 235/255, blue: 120/255, alpha: 1)
        heightValueLabel.font = .systemFont(ofSize: 16, weight: .bold)

        let stack = UIStackView(arrangedSubviews: [minus, heightValueLabel, plus])
        stack.axis = .horizontal; stack.spacing = 12; stack.alignment = .center
        stack.translatesAutoresizingMaskIntoConstraints = false
        heightControlView.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: heightControlView.topAnchor, constant: 8),
            stack.bottomAnchor.constraint(equalTo: heightControlView.bottomAnchor, constant: -8),
            stack.centerXAnchor.constraint(equalTo: heightControlView.centerXAnchor)
        ])
    }

    @objc private func heightMinus() {
        wallHeightPreview = max(wallHeightPreview - 0.1, 1.80)
        heightValueLabel.text = String(format: "%.2fm", wallHeightPreview)
    }
    @objc private func heightPlus() {
        wallHeightPreview = min(wallHeightPreview + 0.1, 5.00)
        heightValueLabel.text = String(format: "%.2fm", wallHeightPreview)
    }

    // MARK: – Button style helpers

    private func stylePrimaryButton(_ btn: UIButton, title: String) {
        btn.setTitle(title, for: .normal)
        btn.setTitleColor(.white, for: .normal)
        btn.titleLabel?.font = .systemFont(ofSize: 36, weight: .bold)
        btn.backgroundColor = UIColor(red: 185/255, green: 18/255, blue: 125/255, alpha: 1)
        btn.layer.cornerRadius = 44; btn.clipsToBounds = true
        btn.layer.borderWidth = 3
        btn.layer.borderColor = UIColor(red: 230/255, green: 60/255, blue: 170/255, alpha: 0.78).cgColor
    }

    private func styleSecondaryButton(_ btn: UIButton, title: String) {
        btn.setTitle(title, for: .normal)
        btn.setTitleColor(UIColor(red: 20/255, green: 215/255, blue: 255/255, alpha: 0.90), for: .normal)
        btn.titleLabel?.font = .systemFont(ofSize: 13)
        btn.backgroundColor = UIColor(red: 0.03, green: 0.04, blue: 0.11, alpha: 0.63)
        btn.layer.cornerRadius = 5; btn.clipsToBounds = true
        btn.layer.borderWidth = 1
        btn.layer.borderColor = UIColor(red: 30/255, green: 120/255, blue: 240/255, alpha: 0.47).cgColor
        btn.contentEdgeInsets = UIEdgeInsets(top: 7, left: 16, bottom: 7, right: 16)
    }
}
