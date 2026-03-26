package it.hubagency.spatialscan

import android.os.Handler
import android.os.Looper
import com.getcapacitor.JSArray
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

    private lateinit var scanManager: SpatialScanManager

    override fun load() {
        scanManager = SpatialScanManager(context)

        scanManager.onTrackingStateChanged = { trackingState, pauseReason ->
            val data = JSObject().apply {
                put("trackingState", trackingState)
                pauseReason?.let { put("pauseReason", it) }
            }
            notifyListeners("onTrackingStateChanged", data)
        }

        scanManager.onFrameUpdate = { frameData ->
            val data = JSObject().apply {
                put("trackingState", frameData.trackingState)
                put("planesDetected", frameData.planesDetected)
                put("wallsDetected", frameData.wallsDetected)
                put("coverageEstimate", frameData.coverageEstimate)
                put("scanDurationSeconds", frameData.scanDurationSeconds)
            }
            notifyListeners("onFrameUpdate", data)
        }
    }

    @PluginMethod
    fun isSupported(call: PluginCall) {
        val result = scanManager.checkSupport()
        val ret = JSObject().apply {
            put("supported", result.supported)
            result.reason?.let { put("reason", it) }
        }
        call.resolve(ret)
    }

    @PluginMethod
    override fun requestPermissions(call: PluginCall) {
        requestPermissionForAlias("camera", call, "cameraPermissionCallback")
    }

    @com.getcapacitor.annotation.PermissionCallback
    private fun cameraPermissionCallback(call: PluginCall) {
        val status = getPermissionState("camera")
        val ret = JSObject().apply {
            put("camera", status.toString().lowercase())
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun startScan(call: PluginCall) {
        val enableDepth = call.getBoolean("enableDepth", true) ?: true

        if (getPermissionState("camera").toString() != "GRANTED") {
            call.reject("Permesso camera non concesso", "PERMISSION_DENIED")
            return
        }

        Handler(Looper.getMainLooper()).post {
            try {
                scanManager.startScan(enableDepth)
                call.resolve()
            } catch (e: ScanException) {
                call.reject(e.message, e.errorCode)
            }
        }
    }

    @PluginMethod
    fun stopScan(call: PluginCall) {
        try {
            val result = scanManager.buildFinalResult(context)
            call.resolve(result)
        } catch (e: Exception) {
            call.reject("Errore interno: ${e.message}", "INTERNAL_ERROR")
        }
    }

    @PluginMethod
    fun cancelScan(call: PluginCall) {
        scanManager.cancelScan()
        call.resolve()
    }

    @PluginMethod
    fun getScanStatus(call: PluginCall) {
        val ret = JSObject().apply {
            put("isScanning", scanManager.isCurrentlyScanning())
            put("trackingState", "STOPPED")
            put("planesDetected", 0)
            put("scanDurationSeconds", scanManager.getScanDurationSeconds())
        }
        call.resolve(ret)
    }
}
