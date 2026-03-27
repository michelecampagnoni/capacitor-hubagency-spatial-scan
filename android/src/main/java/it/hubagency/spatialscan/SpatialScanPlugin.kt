package it.hubagency.spatialscan

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission

@CapacitorPlugin(
    name = "SpatialScan",
    permissions = [
        Permission(strings = [android.Manifest.permission.CAMERA], alias = "camera")
    ]
)
class SpatialScanPlugin : Plugin() {

    override fun load() {
        // Propaga eventi dalla ScanningActivity ai listener JS
        ScanningActivity.onFrameUpdate = { frameData ->
            notifyListeners("onFrameUpdate", JSObject().apply {
                put("trackingState",       frameData.trackingState)
                put("planesDetected",      frameData.planesDetected)
                put("wallsDetected",       frameData.wallsDetected)
                put("coverageEstimate",    frameData.coverageEstimate)
                put("scanDurationSeconds", frameData.scanDurationSeconds)
            })
        }
        ScanningActivity.onTrackingStateChanged = { trackingState, pauseReason ->
            notifyListeners("onTrackingStateChanged", JSObject().apply {
                put("trackingState", trackingState)
                pauseReason?.let { put("pauseReason", it) }
            })
        }
    }

    @PluginMethod
    fun isSupported(call: PluginCall) {
        val result = SpatialScanManager(context).checkSupport()
        call.resolve(JSObject().apply {
            put("supported", result.supported)
            result.reason?.let { put("reason", it) }
        })
    }

    @PluginMethod
    override fun requestPermissions(call: PluginCall) {
        requestPermissionForAlias("camera", call, "cameraPermissionCallback")
    }

    @com.getcapacitor.annotation.PermissionCallback
    private fun cameraPermissionCallback(call: PluginCall) {
        call.resolve(JSObject().apply {
            put("camera", getPermissionState("camera").toString().lowercase())
        })
    }

    @PluginMethod
    fun startScan(call: PluginCall) {
        val enableDepth = call.getBoolean("enableDepth", true) ?: true

        if (getPermissionState("camera") != com.getcapacitor.PermissionState.GRANTED) {
            call.reject("Permesso camera non concesso", "PERMISSION_DENIED")
            return
        }

        // Pulisce risultato precedente non consumato
        ScanningActivity.pendingResult = null

        val intent = Intent(context, ScanningActivity::class.java)
        intent.putExtra("enableDepth", enableDepth)
        intent.addFlags(FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        // Risolve subito: il risultato arriva tramite stopScan()
        call.resolve()
    }

    @PluginMethod
    fun stopScan(call: PluginCall) {
        // Caso 1: utente ha già premuto "Ferma" nell'Activity prima di questa chiamata
        val stored = ScanningActivity.pendingResult
        if (stored != null) {
            ScanningActivity.pendingResult = null
            call.resolve(stored)
            return
        }

        // Caso 2: Activity ancora in esecuzione → richiedi stop e attendi il risultato
        val scanActivity = ScanningActivity.instance
        if (scanActivity == null) {
            call.reject("Nessuna scansione in corso", "SESSION_FAILED")
            return
        }

        ScanningActivity.onScanResult = { result ->
            ScanningActivity.onScanResult = null
            call.resolve(result)
        }
        scanActivity.requestStop()
    }

    @PluginMethod
    fun cancelScan(call: PluginCall) {
        ScanningActivity.pendingResult = null
        ScanningActivity.instance?.cancelScanAndFinish()
        call.resolve()
    }

    @PluginMethod
    fun getScanStatus(call: PluginCall) {
        val isActive = ScanningActivity.instance != null
        call.resolve(JSObject().apply {
            put("isScanning",          isActive)
            put("trackingState",       if (isActive) "TRACKING" else "STOPPED")
            put("planesDetected",      0)
            put("scanDurationSeconds", 0.0)
        })
    }
}
