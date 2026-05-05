import Foundation
import Capacitor

@objc(SpatialScanPlugin)
public class SpatialScanPlugin: CAPPlugin, CAPBridgedPlugin {

    public let identifier = "SpatialScanPlugin"
    public let jsName = "SpatialScan"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "isSupported",         returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions",  returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startScan",           returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopScan",            returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "cancelScan",          returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "exportPdf",           returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getScanStatus",       returnType: CAPPluginReturnPromise),
    ]

    private let manager = SpatialScanManager()

    public override func load() {
        manager.onFrameUpdate = { [weak self] data in
            self?.notifyListeners("onFrameUpdate", data: [
                "trackingState":       data.trackingState,
                "planesDetected":      data.planesDetected,
                "wallsDetected":       data.wallsDetected,
                "coverageEstimate":    data.coverageEstimate,
                "scanDurationSeconds": data.scanDurationSeconds
            ])
        }
        manager.onTrackingStateChanged = { [weak self] state, reason in
            var payload: [String: Any] = ["trackingState": state]
            if let r = reason { payload["pauseReason"] = r }
            self?.notifyListeners("onTrackingStateChanged", data: payload)
        }
        manager.onScanComplete = { [weak self] result in
            self?.notifyListeners("onScanComplete", data: result)
        }
    }

    @objc func isSupported(_ call: CAPPluginCall) {
        let result = manager.checkSupport()
        var ret: [String: Any] = ["supported": result.supported]
        if let reason = result.reason { ret["reason"] = reason }
        call.resolve(ret)
    }

    @objc public override func requestPermissions(_ call: CAPPluginCall) {
        manager.requestCameraPermission { granted in
            call.resolve(["camera": granted ? "granted" : "denied"])
        }
    }

    @objc func startScan(_ call: CAPPluginCall) {
        let enableDepth = call.getBool("enableDepth") ?? true

        manager.requestCameraPermission { [weak self] granted in
            guard granted else {
                call.reject("Permesso camera non concesso", "PERMISSION_DENIED")
                return
            }
            DispatchQueue.main.async {
                self?.manager.startScan(enableDepth: enableDepth, plugin: self!) { error in
                    if let error = error {
                        call.reject(error.message, error.code)
                    } else {
                        call.resolve()
                    }
                }
            }
        }
    }

    @objc func stopScan(_ call: CAPPluginCall) {
        if let stored = manager.pendingResult {
            manager.pendingResult = nil
            call.resolve(stored)
            return
        }
        guard manager.isScanning else {
            call.reject("Nessuna scansione in corso", "SESSION_FAILED")
            return
        }
        manager.requestStop { result in
            call.resolve(result)
        }
    }

    @objc func cancelScan(_ call: CAPPluginCall) {
        manager.cancelScan()
        call.resolve()
    }

    @objc func exportPdf(_ call: CAPPluginCall) {
        manager.exportPdf { result, error in
            if let error = error {
                call.reject(error.message, error.code)
            } else if let result = result {
                call.resolve(result)
            }
        }
    }

    @objc func getScanStatus(_ call: CAPPluginCall) {
        call.resolve([
            "isScanning":          manager.isScanning,
            "trackingState":       manager.isScanning ? "TRACKING" : "STOPPED",
            "planesDetected":      0,
            "scanDurationSeconds": manager.scanDurationSeconds
        ])
    }
}
