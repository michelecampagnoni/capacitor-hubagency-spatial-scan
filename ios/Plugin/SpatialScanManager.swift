import Foundation
import ARKit
import AVFoundation
import UIKit

struct SupportResult {
    let supported: Bool
    let reason: String?
}

struct FrameUpdateData {
    let trackingState: String
    let planesDetected: Int
    let wallsDetected: Int
    let coverageEstimate: Double
    let scanDurationSeconds: Double
}

struct ScanError {
    let code: String
    let message: String
}

class SpatialScanManager: NSObject {

    var onFrameUpdate: ((FrameUpdateData) -> Void)?
    var onTrackingStateChanged: ((String, String?) -> Void)?
    var onScanComplete: (([String: Any]) -> Void)?

    var pendingResult: [String: Any]?
    private(set) var isScanning = false
    /// true dal momento in cui ScanningVC sceglie il path Composer fino a quando
    /// il Composer chiama composerDidConfirm() o composerDidCancel().
    /// Mentre è true, onScanComplete NON viene emesso.
    private(set) var composerPending = false
    private var scanStartTime: Date?
    private var stopCallback: (([String: Any]) -> Void)?
    private weak var scanningVC: ScanningViewController?

    var scanDurationSeconds: Double {
        guard let start = scanStartTime else { return 0 }
        return Date().timeIntervalSince(start)
    }

    // ── Support check ─────────────────────────────────────────────

    func checkSupport() -> SupportResult {
        guard ARWorldTrackingConfiguration.isSupported else {
            return SupportResult(supported: false, reason: "DEVICE_NOT_SUPPORTED")
        }
        return SupportResult(supported: true, reason: nil)
    }

    // ── Camera permission ────────────────────────────────────────

    func requestCameraPermission(completion: @escaping (Bool) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            completion(true)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async { completion(granted) }
            }
        default:
            completion(false)
        }
    }

    // ── startScan ────────────────────────────────────────────────

    func startScan(enableDepth: Bool, plugin: AnyObject, completion: @escaping (ScanError?) -> Void) {
        guard !isScanning else {
            completion(ScanError(code: "SESSION_FAILED", message: "Scan already in progress"))
            return
        }
        guard ARWorldTrackingConfiguration.isSupported else {
            completion(ScanError(code: "DEVICE_NOT_SUPPORTED", message: "ARKit not supported on this device"))
            return
        }

        isScanning = true
        scanStartTime = Date()
        pendingResult = nil

        guard let topVC = UIApplication.shared.hubTopViewController() else {
            isScanning = false
            completion(ScanError(code: "SESSION_FAILED", message: "No root view controller"))
            return
        }

        let vc = ScanningViewController()
        vc.manager = self
        vc.modalPresentationStyle = .fullScreen

        vc.onScanFinished = { [weak self] result in
            guard let self else { return }
            self.scanningVC = nil
            if let cb = self.stopCallback {
                self.stopCallback = nil
                self.isScanning = false
                cb(result)
            } else {
                self.isScanning = false
                self.pendingResult = result
                self.onScanComplete?(result)
            }
        }

        vc.onScanCancelled = { [weak self] in
            guard let self else { return }
            self.scanningVC = nil
            self.isScanning = false
            self.scanStartTime = nil
            self.stopCallback = nil
        }

        self.scanningVC = vc
        topVC.present(vc, animated: true) {
            completion(nil)
        }
    }

    // ── requestStop ──────────────────────────────────────────────

    func requestStop(completion: @escaping ([String: Any]) -> Void) {
        stopCallback = completion
        if let vc = scanningVC {
            vc.triggerStop()
        } else {
            // Already dismissed or never started
            let stub: [String: Any] = ["success": false, "error": "NOT_SCANNING"]
            stopCallback?(stub)
            stopCallback = nil
            isScanning = false
        }
    }

    func didFinishScan(_ result: [String: Any]) {
        isScanning = false
        if let cb = stopCallback {
            stopCallback = nil
            cb(result)
        } else {
            pendingResult = result
            onScanComplete?(result)
        }
    }

    // ── Composer lifecycle ───────────────────────────────────────

    /// Chiamato da ScanningViewController quando sceglie il path Composer.
    /// Esegue solo housekeeping (isScanning=false, scanningVC=nil).
    /// NON emette onScanComplete: sarà il Composer a farlo via composerDidConfirm().
    func beginComposerPhase() {
        NSLog("[HUB_DIAG] SpatialScanManager: beginComposerPhase — stopCallback tenuto, attesa Composer")
        scanningVC      = nil
        composerPending = true
        isScanning      = false
        // stopCallback NON viene fired qui — il Composer lo risolve via composerDidConfirm/Cancel
    }

    /// Chiamato da RoomComposerViewController.confirmSave() dopo l'export combinato.
    /// Se stopScan() è in attesa (stopCallback), risolve la promise JS con il risultato combinato.
    /// Altrimenti emette l'evento onScanComplete (path listener).
    func composerDidConfirm(_ result: [String: Any]) {
        NSLog("[HUB_DIAG] SpatialScanManager: composerDidConfirm — result ricevuto")
        composerPending = false
        if let cb = stopCallback {
            NSLog("[HUB_DIAG] SpatialScanManager: composerDidConfirm — resolving stopCallback")
            stopCallback = nil
            cb(result)
        } else {
            NSLog("[HUB_DIAG] SpatialScanManager: composerDidConfirm — firing onScanComplete")
            pendingResult = result
            onScanComplete?(result)
        }
    }

    /// Chiamato quando il Composer viene annullato/chiuso senza conferma oppure
    /// quando l'export combinato fallisce.
    func composerDidCancel() {
        guard composerPending else { return }
        NSLog("[HUB_DIAG] SpatialScanManager: composerDidCancel — composerPending reset")
        composerPending = false
        if let cb = stopCallback {
            stopCallback = nil
            cb(["success": false, "error": "COMPOSER_CANCELLED"])
        }
    }

    // ── cancelScan ───────────────────────────────────────────────

    func cancelScan() {
        scanningVC?.dismiss(animated: true)
        scanningVC = nil
        isScanning = false
        scanStartTime = nil
        pendingResult = nil
        stopCallback = nil
    }

    // ── startContinuationScan ────────────────────────────────────

    func startContinuationScan() {
        guard let topVC = UIApplication.shared.hubTopViewController() else { return }
        let vc = ScanningViewController()
        vc.isContinuation = true
        vc.manager = self
        vc.modalPresentationStyle = .fullScreen

        vc.onScanFinished = { [weak self] result in
            guard let self else { return }
            self.scanningVC = nil
            self.pendingResult = result
            self.onScanComplete?(result)
        }
        vc.onScanCancelled = { [weak self] in
            guard let self else { return }
            self.scanningVC = nil
            self.isScanning = false
        }

        self.scanningVC = vc
        topVC.present(vc, animated: true)
    }

    // ── exportPdf ────────────────────────────────────────────────

    func exportPdf(completion: @escaping ([String: Any]?, ScanError?) -> Void) {
        // 1. Controlla cache (come Android: SharedPreferences "lastPdfPath")
        if let cached = UserDefaults.standard.string(forKey: "hub_lastPdfPath"),
           FileManager.default.fileExists(atPath: cached) {
            completion(["pdfPath": cached], nil)
            return
        }
        // 2. Fallback: ricostruisce da RoomDataLoader + FloorPlanExporter
        let cacheDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        DispatchQueue.global(qos: .userInitiated).async {
            guard let exportData = RoomDataLoader.buildExportData() else {
                DispatchQueue.main.async {
                    completion(nil, ScanError(code: "EXPORT_FAILED",
                                              message: "Nessun dato stanza disponibile"))
                }
                return
            }
            guard let pdfPath = FloorPlanExporter.exportPdf(data: exportData, cacheDir: cacheDir) else {
                DispatchQueue.main.async {
                    completion(nil, ScanError(code: "EXPORT_FAILED",
                                              message: "Generazione PDF fallita"))
                }
                return
            }
            UserDefaults.standard.set(pdfPath, forKey: "hub_lastPdfPath")
            DispatchQueue.main.async {
                completion(["pdfPath": pdfPath], nil)
            }
        }
    }
}

// MARK: – UIApplication helper (internal — usato da ScanningViewController e RoomComposerViewController)

extension UIApplication {
    func hubTopViewController() -> UIViewController? {
        guard let root = connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .flatMap({ $0.windows })
            .first(where: { $0.isKeyWindow })?.rootViewController
        else { return nil }

        var top: UIViewController = root
        while let presented = top.presentedViewController {
            top = presented
        }
        return top
    }
}
