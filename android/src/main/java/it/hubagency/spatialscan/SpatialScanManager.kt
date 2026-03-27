package it.hubagency.spatialscan

import android.content.Context
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import java.util.concurrent.atomic.AtomicBoolean

class SpatialScanManager(private val context: Context) {

    private var session: Session? = null
    private val isScanning = AtomicBoolean(false)
    private var scanStartTime: Long = 0L
    private var frameThread: Thread? = null

    private val planeProcessor = PlaneProcessor()
    private val wallDetector = WallDetector()
    private val depthProcessor = DepthProcessor()
    private var depthApiAvailable = false

    var onTrackingStateChanged: ((String, String?) -> Unit)? = null
    var onFrameUpdate: ((FrameUpdateData) -> Unit)? = null

    // ── checkSupport ──────────────────────────────────────────────

    fun checkSupport(): SupportResult {
        return try {
            when (ArCoreApk.getInstance().checkAvailability(context)) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED ->
                    SupportResult(true, null)
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ->
                    SupportResult(false, "SDK_TOO_OLD")
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ->
                    SupportResult(false, "ARCORE_NOT_INSTALLED")
                ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
                    SupportResult(false, "DEVICE_NOT_SUPPORTED")
                else -> SupportResult(false, "ARCORE_NOT_AVAILABLE")
            }
        } catch (e: Exception) {
            SupportResult(false, "ARCORE_NOT_AVAILABLE")
        }
    }

    // ── startScan ─────────────────────────────────────────────────

    @Throws(ScanException::class)
    fun startScan(enableDepth: Boolean = true) {
        if (isScanning.get()) throw ScanException("SESSION_FAILED", "Scan already in progress")

        session = try {
            Session(context)
        } catch (e: UnavailableArcoreNotInstalledException) {
            throw ScanException("ARCORE_NOT_INSTALLED", e.message)
        } catch (e: UnavailableDeviceNotCompatibleException) {
            throw ScanException("DEVICE_NOT_SUPPORTED", e.message)
        } catch (e: Exception) {
            throw ScanException("SESSION_FAILED", e.message)
        }

        val config = Config(session!!).apply {
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            focusMode = Config.FocusMode.AUTO
        }

        depthApiAvailable = enableDepth &&
            session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)

        if (depthApiAvailable) {
            config.depthMode = Config.DepthMode.AUTOMATIC
        }

        session!!.configure(config)
        session!!.resume()

        isScanning.set(true)
        scanStartTime = System.currentTimeMillis()
        planeProcessor.reset()
        depthProcessor.reset()

        frameThread = Thread { frameLoop() }.apply {
            name = "spatial-scan-frame-loop"
            isDaemon = true
            start()
        }
    }

    // ── Frame loop ────────────────────────────────────────────────

    private fun frameLoop() {
        var lastEventTime = 0L

        while (isScanning.get()) {
            try {
                val frame = session?.update() ?: break
                val now = System.currentTimeMillis()

                val planes = frame.getUpdatedTrackables(Plane::class.java)
                planeProcessor.updatePlanes(planes)
                if (depthApiAvailable) depthProcessor.processFrame(frame)

                if (now - lastEventTime >= 1000L) {
                    lastEventTime = now

                    val trackingState = frame.camera.trackingState
                    val pauseReason = if (trackingState == TrackingState.PAUSED) {
                        frame.camera.trackingFailureReason.name
                    } else null

                    val elapsed = (now - scanStartTime) / 1000.0

                    onTrackingStateChanged?.invoke(trackingState.name, pauseReason)
                    onFrameUpdate?.invoke(
                        FrameUpdateData(
                            trackingState = trackingState.name,
                            planesDetected = planeProcessor.getTotalActivePlanes(),
                            wallsDetected = planeProcessor.getTotalActiveWalls(),
                            coverageEstimate = planeProcessor.estimateCoverage(),
                            scanDurationSeconds = elapsed
                        )
                    )
                }

                Thread.sleep(33)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                // continua il loop
            }
        }
    }

    // ── buildFinalResult ──────────────────────────────────────────

    fun buildFinalResult(context: Context): JSObject {
        isScanning.set(false)
        frameThread?.interrupt()
        frameThread?.join(2000)

        val allPlaneData = planeProcessor.getAllPlaneData()
        val elapsed = (System.currentTimeMillis() - scanStartTime) / 1000.0
        val totalPlanes = planeProcessor.getTotalActivePlanes()

        session?.pause()
        session?.close()
        session = null

        val walls = wallDetector.extractWalls(allPlaneData.filter { it.isWall() })
        val refinedWalls = if (depthApiAvailable && depthProcessor.getAccumulatedPointCount() > 50) {
            depthProcessor.extractWallSegmentsFromDepth(walls)
        } else walls
        val floorArea = planeProcessor.getFloor()?.area?.toDouble() ?: 0.0
        val roomDimensions = wallDetector.calculateRoomDimensions(refinedWalls, floorArea)

        val arcoreVersion = try {
            context.packageManager.getPackageInfo("com.google.ar.core", 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }

        return if (refinedWalls.isEmpty()) {
            JSObject().apply {
                put("success", false)
                put("error", "TRACKING_INSUFFICIENT")
                put("walls", JSArray())
                put("floor", JSObject.NULL)
                put("roomDimensions", zeroDimensions())
                put("scanMetadata", buildMetadata(elapsed, totalPlanes, 0, arcoreVersion))
            }
        } else {
            val wallsArray = JSArray()
            refinedWalls.forEach { wall -> wallsArray.put(wallToJSObject(wall)) }

            val floorOutline = wallDetector.buildRoomOutline(refinedWalls)
            val floorObj = if (floorOutline.isNotEmpty()) {
                JSObject().apply {
                    val vArr = JSArray()
                    floorOutline.forEach { p -> vArr.put(point3DToJSObject(p)) }
                    put("vertices", vArr)
                    put("area", floorArea)
                }
            } else JSObject.NULL

            JSObject().apply {
                put("success", true)
                put("walls", wallsArray)
                put("floor", floorObj)
                put("roomDimensions", JSObject().apply {
                    put("width", roomDimensions.width)
                    put("length", roomDimensions.length)
                    put("height", roomDimensions.height)
                    put("area", roomDimensions.area)
                    put("perimeter", roomDimensions.perimeter)
                })
                put("scanMetadata", buildMetadata(elapsed, totalPlanes, refinedWalls.size, arcoreVersion))
            }
        }
    }

    // ── cancelScan ───────────────────────────────────────────────

    fun cancelScan() {
        isScanning.set(false)
        frameThread?.interrupt()
        try { session?.pause(); session?.close() } catch (e: Exception) { }
        session = null
        planeProcessor.reset()
        depthProcessor.reset()
    }

    fun isCurrentlyScanning() = isScanning.get()
    fun getScanDurationSeconds() =
        if (isScanning.get()) (System.currentTimeMillis() - scanStartTime) / 1000.0 else 0.0

    // ── helpers ───────────────────────────────────────────────────

    private fun wallToJSObject(wall: Wall) = JSObject().apply {
        put("id", wall.id)
        put("startPoint", point3DToJSObject(wall.startPoint))
        put("endPoint", point3DToJSObject(wall.endPoint))
        put("length", wall.length)
        put("height", wall.height)
        put("normal", JSObject().apply {
            put("x", wall.normal.x); put("y", wall.normal.y); put("z", wall.normal.z)
        })
        put("confidence", wall.confidence)
    }

    private fun point3DToJSObject(p: Point3D) = JSObject().apply {
        put("x", p.x); put("y", p.y); put("z", p.z)
    }

    private fun zeroDimensions() = JSObject().apply {
        put("width", 0.0); put("length", 0.0)
        put("height", 0.0); put("area", 0.0); put("perimeter", 0.0)
    }

    private fun buildMetadata(elapsed: Double, planes: Int, walls: Int, version: String) =
        JSObject().apply {
            put("scanDurationSeconds", elapsed)
            put("planesDetected", planes)
            put("wallsInResult", walls)
            put("depthApiUsed", depthApiAvailable)
            put("arcoreVersion", version)
        }
}

data class SupportResult(val supported: Boolean, val reason: String?)
data class FrameUpdateData(
    val trackingState: String,
    val planesDetected: Int,
    val wallsDetected: Int,
    val coverageEstimate: Double,
    val scanDurationSeconds: Double
)
class ScanException(val errorCode: String, message: String?) : Exception(message)
