package it.hubagency.spatialscan

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.pow

/**
 * ScanningActivity — Guided Perimeter Capture
 *
 * Architettura: l'utente cammina e piazza punti angolo tap-by-tap.
 * ARCore fornisce solo:
 *  1. Floor Y  — stabile, via FloorPlaneAnchor + PointCloudSampler
 *  2. Camera pose — per il raycast reticle → pavimento
 *
 * Il sistema assiste la geometria (snap 90°, griglia 5cm), non la scopre.
 *
 * Flusso:
 *  Floor lock → "Spostati in un angolo" → TAP (P0)
 *  → cammina → TAP (P1…Pn) → "Chiudi Poligono" (≥3 punti)
 *  → "Genera Planimetria" → JSON
 *
 * Vecchi componenti (wall detection V1–V4) restano come campi
 * ma NON vengono chiamati in questo pipeline.
 */
class ScanningActivity : Activity(), GLSurfaceView.Renderer {

    companion object {
        @Volatile var instance:             ScanningActivity? = null
        @Volatile var pendingResult:        JSObject?         = null
        @Volatile var onScanResult:         ((JSObject) -> Unit)? = null
        @Volatile var onScanComplete:       ((JSObject) -> Unit)? = null
        @Volatile var onFrameUpdate:        ((FrameUpdateData) -> Unit)? = null
        @Volatile var onTrackingStateChanged: ((String, String?) -> Unit)? = null
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    private lateinit var glSurfaceView:    GLSurfaceView
    private lateinit var reticleView:      ReticleView
    private lateinit var guidanceHeadline: TextView
    private lateinit var guidanceSubtext:  TextView
    private lateinit var guidancePill:     LinearLayout
    private lateinit var sideBadge:        TextView       // ex wallCountBadge
    private lateinit var timerText:        TextView
    private lateinit var actionBtn:        Button         // Chiudi / Genera / Annulla
    private lateinit var cancelBtn:        Button
    private lateinit var pcDebugOverlay:   TextView

    // ── ARCore ───────────────────────────────────────────────────────────────
    private val backgroundRenderer    = BackgroundRenderer()
    private var session: Session?     = null
    private var sessionCreated        = false

    // ── Perimeter Capture (pipeline attivo) ──────────────────────────────────
    private val perimeterCapture  = PerimeterCapture()
    private val perimeterRenderer = PerimeterRenderer()

    // Ultima posizione world XZ della reticle (aggiornata ogni frame GL thread)
    @Volatile private var lastReticleWorld: FloatArray? = null
    // Punto live snappato (aggiornato ogni frame GL thread)
    @Volatile private var lastLivePreview:  FloatArray? = null

    // Dimensioni schermo (settate in onSurfaceChanged)
    @Volatile private var screenWidth  = 1
    @Volatile private var screenHeight = 1

    // ── Floor / tracking ─────────────────────────────────────────────────────
    @Volatile private var lastFloorY   = 0f
    private val floorAnchor  = FloorPlaneAnchor()
    private val pcSampler    = PointCloudSampler()   // attivo: serve per floor lock
    private var scanStartTime    = 0L
    private var lastUiUpdateMs   = 0L
    private var lastDebugMs      = 0L
    private val mainHandler      = Handler(Looper.getMainLooper())

    // ── Vecchi componenti (non chiamati in questo pipeline) ───────────────────
    @Suppress("unused") private val confirmedWallRenderer  = ConfirmedWallRenderer()
    @Suppress("unused") private val roomPreviewRenderer    = RoomPreviewRenderer()
    @Suppress("unused") private val wallHypothesisTracker  = WallHypothesisTracker()
    @Suppress("unused") private val roomBoxEstimator       = RoomBoxEstimator()
    @Suppress("unused") private val roomBoxRenderer        = RoomBoxRenderer()
    @Suppress("unused") private val wallDetector           = WallDetector()
    @Suppress("unused") private val planeProcessor         = PlaneProcessor()
    @Suppress("unused") private val depthProcessor         = DepthProcessor()
    @Suppress("unused") private val pcSeeder               = PointCloudWallSeeder()
    @Suppress("unused") private var depthApiAvailable      = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        @Suppress("DEPRECATION")
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(buildLayout())
        wireActionButton()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        try { session?.resume() } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        session?.pause()
        glSurfaceView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        session?.close(); session = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { cancelScanAndFinish() }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout(): android.view.View {
        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // GLSurfaceView fullscreen
        glSurfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setRenderer(this@ScanningActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(glSurfaceView, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Reticle — centrata, touch-through (non intercetta eventi)
        reticleView = ReticleView(this).apply { isClickable = false; isFocusable = false }
        root.addView(reticleView, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Guidance pill — top center
        guidancePill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setBackgroundColor(Color.TRANSPARENT)
        }
        guidanceHeadline = TextView(this).apply {
            text = "Inizializzazione…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setShadowLayer(16f, 0f, 0f, Color.BLACK)
        }
        guidanceSubtext = TextView(this).apply {
            text = "Muoviti verso il pavimento"
            setTextColor(Color.argb(200, 170, 200, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setShadowLayer(14f, 0f, 0f, Color.BLACK)
        }
        guidancePill.addView(guidanceHeadline)
        guidancePill.addView(guidanceSubtext)
        root.addView(guidancePill, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(44) })

        // Bottom bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(16), dp(14), dp(16), dp(36))
        }

        // Stats row
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
            visibility = View.GONE
        }
        sideBadge = TextView(this).apply {
            text = "In attesa…"
            setTextColor(Color.argb(230, 30, 235, 120))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }
        timerText = TextView(this).apply {
            text = "0s"
            setTextColor(Color.argb(180, 170, 190, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        statsRow.addView(sideBadge, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statsRow.addView(timerText)
        bottomBar.addView(statsRow)

        // Button row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actionBtn = Button(this).apply {
            text = "Annulla"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(255, 18, 120, 70))
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        cancelBtn = Button(this).apply {
            text = "Annulla"
            setTextColor(Color.argb(200, 200, 200, 200))
            setBackgroundColor(Color.argb(0, 0, 0, 0))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { cancelScanAndFinish() }
        }
        btnRow.addView(actionBtn, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply { marginEnd = dp(8) })
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottomBar.addView(btnRow)

        root.addView(bottomBar, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))

        // Debug overlay top-left
        pcDebugOverlay = TextView(this).apply {
            text = "GPC v1"
            setTextColor(Color.argb(200, 180, 255, 180))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            visibility = View.GONE
        }
        root.addView(pcDebugOverlay, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START; topMargin = dp(44) })

        return root
    }

    /**
     * Collega il listener dell'actionBtn (fisso).
     * Il comportamento varia in base allo stato corrente della cattura.
     */
    private fun wireActionButton() {
        glSurfaceView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val rw = lastReticleWorld ?: return@setOnTouchListener true
                val x = rw[0]; val z = rw[1]
                glSurfaceView.queueEvent {
                    val added = perimeterCapture.addPoint(x, z)
                    if (added) mainHandler.post { updateCaptureUI() }
                }
            }
            true
        }

        actionBtn.setOnClickListener {
            val state    = perimeterCapture.state
            val canClose = perimeterCapture.canClose
            when {
                state == PerimeterCapture.State.CLOSED -> {
                    actionBtn.isEnabled = false
                    actionBtn.text = "Elaborazione…"
                    glSurfaceView.queueEvent { doStopScan() }
                }
                canClose -> {
                    glSurfaceView.queueEvent {
                        perimeterCapture.close()
                        mainHandler.post { updateCaptureUI() }
                    }
                }
                else -> cancelScanAndFinish()
            }
        }
    }

    // ── GLSurfaceView.Renderer ────────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        backgroundRenderer.init(texIds[0])
        perimeterRenderer.init()

        // Vecchi renderer: init per evitare crash se chiamati accidentalmente
        confirmedWallRenderer.init()
        roomPreviewRenderer.init()
        roomBoxRenderer.init()

        if (!sessionCreated) {
            try {
                session = Session(this)
                val cfg = Config(session!!).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    updateMode       = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode        = Config.FocusMode.AUTO
                }
                session!!.configure(cfg)
                session!!.setCameraTextureName(texIds[0])
                session!!.resume()

                sessionCreated = true
                scanStartTime  = System.currentTimeMillis()

                floorAnchor.reset()
                pcSampler.reset()
                perimeterCapture.reset()
                lastReticleWorld = null
                lastLivePreview  = null

                Log.d("SpatialScan", "GPC_INIT | Guided Perimeter Capture active | ts=${scanStartTime}")

                mainHandler.post {
                    guidanceHeadline.text = "Inizializzazione…"
                    guidanceSubtext.text  = "Muoviti lentamente, punta verso il pavimento"
                }
            } catch (e: UnavailableArcoreNotInstalledException) {
                mainHandler.post { guidanceHeadline.text = "ARCore non installato" }
            } catch (e: UnavailableDeviceNotCompatibleException) {
                mainHandler.post { guidanceHeadline.text = "Dispositivo non compatibile" }
            } catch (e: Exception) {
                mainHandler.post { guidanceHeadline.text = "Errore: ${e.message}" }
            }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        screenWidth  = width
        screenHeight = height
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        session?.setDisplayGeometry(rotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val sess = session ?: return

        try {
            val frame  = sess.update()
            backgroundRenderer.draw(frame)

            val camera = frame.camera
            val nowMs  = System.currentTimeMillis()

            // ── Floor Y stabile ───────────────────────────────────────────────
            val allPlanes = sess.getAllTrackables(Plane::class.java)
            val floorY    = floorAnchor.update(
                allPlanes,
                camera.pose.ty(),
                camera.trackingState == TrackingState.TRACKING
            )
            lastFloorY = floorY

            // PointCloudSampler attivo solo per il floor lock (non per geometria)
            pcSampler.sample(frame, floorY)

            // ── Reticle raycast: centro schermo → pavimento ───────────────────
            if (camera.trackingState == TrackingState.TRACKING) {
                val cx = screenWidth  / 2f
                val cy = screenHeight / 2f
                val rw = screenToFloor(frame, camera, cx, cy, floorY)
                if (rw != null) {
                    lastReticleWorld = rw
                    lastLivePreview  = perimeterCapture.livePreview(rw[0], rw[1])
                }
            }

            // ── PerimeterRenderer ─────────────────────────────────────────────
            if (camera.trackingState == TrackingState.TRACKING) {
                val viewMatrix = FloatArray(16)
                val projMatrix = FloatArray(16)
                camera.getViewMatrix(viewMatrix, 0)
                camera.getProjectionMatrix(projMatrix, 0, 0.05f, 100f)

                perimeterRenderer.draw(
                    confirmedPts = perimeterCapture.getPolygon(),
                    livePoint    = lastLivePreview,
                    isClosed     = perimeterCapture.state == PerimeterCapture.State.CLOSED,
                    floorY       = floorY,
                    viewMatrix   = viewMatrix,
                    projMatrix   = projMatrix
                )
            }

            // ── Aggiornamento UI (throttled 300ms) ────────────────────────────
            if (nowMs - lastUiUpdateMs >= 300L) {
                lastUiUpdateMs = nowMs

                val trackingState = camera.trackingState
                val pauseReason   = if (trackingState == TrackingState.PAUSED)
                    camera.trackingFailureReason.name else null
                onTrackingStateChanged?.invoke(trackingState.name, pauseReason)

                onFrameUpdate?.invoke(FrameUpdateData(
                    trackingState       = trackingState.name,
                    planesDetected      = 0,
                    wallsDetected       = perimeterCapture.pointCount,
                    coverageEstimate    = 0.0,
                    scanDurationSeconds = ((nowMs - scanStartTime) / 1000).toDouble()
                ))

                val elapsed         = ((nowMs - scanStartTime) / 1000).toInt()
                val floorLocked     = floorAnchor.isLocked
                val captureState    = perimeterCapture.state
                val ptCount         = perimeterCapture.pointCount
                val lastLen         = perimeterCapture.lastSegmentLength()
                val reticleW        = lastReticleWorld

                mainHandler.post {
                    // Reticle state
                    reticleView.reticleState = when {
                        trackingState != TrackingState.TRACKING -> ReticleView.State.IDLE
                        !floorLocked                            -> ReticleView.State.IDLE
                        else                                    -> ReticleView.State.TRACKING
                    }

                    // Guidance
                    val (hl, sub) = guidanceText(trackingState, floorLocked, captureState, ptCount)
                    guidancePill.setBackgroundColor(Color.TRANSPARENT)
                    guidanceHeadline.text = hl
                    guidanceSubtext.text  = sub

                    // Badge lati
                    sideBadge.text = when {
                        ptCount == 0 && !floorLocked -> "Attendi floor lock…"
                        ptCount == 0                 -> "Pronto · Vai in un angolo"
                        lastLen != null ->
                            "$ptCount lat${if (ptCount == 1) "o" else "i"} · ultimo: ${"%.2f".format(lastLen)}m"
                        else ->
                            "$ptCount punt${if (ptCount == 1) "o" else "i"}"
                    }
                    timerText.text = "${elapsed}s"

                    // Action button
                    updateActionBtn(captureState, perimeterCapture.canClose)

                    // Debug overlay
                    val rXZ = reticleW
                    pcDebugOverlay.text = buildString {
                        append("GPC | pts:$ptCount state:${captureState.name}\n")
                        append("floor:${if (floorLocked) "LOCK" else "est"} Y=${"%.2f".format(floorY)}\n")
                        if (rXZ != null)
                            append("reticle: X=${"%.2f".format(rXZ[0])} Z=${"%.2f".format(rXZ[1])}")
                    }
                }
            }

            // Debug logcat 1s
            if (nowMs - lastDebugMs >= 1000L) {
                lastDebugMs = nowMs
                Log.d("SpatialScan", "GPC | pts=${perimeterCapture.pointCount} " +
                    "state=${perimeterCapture.state} floorY=${"%.2f".format(floorY)} " +
                    "floorLocked=${floorAnchor.isLocked} " +
                    "reticle=${lastReticleWorld?.let { "(${it[0]}, ${it[1]})" } ?: "null"}")
            }

        } catch (_: Exception) { /* continua */ }
    }

    // ── Raycast centro schermo → pavimento ────────────────────────────────────

    /**
     * Tenta prima un ARCore hit test sul piano orizzontale.
     * Fallback: ray camera forward ∩ Y = floorY.
     */
    private fun screenToFloor(frame: Frame, camera: Camera, px: Float, py: Float, floorY: Float): FloatArray? {
        // Tentativo 1: ARCore hit test → piano orizzontale
        val hits = frame.hitTest(px, py)
        for (hit in hits) {
            val trackable = hit.trackable
            if (trackable is Plane &&
                trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                trackable.trackingState == TrackingState.TRACKING
            ) {
                val pose = hit.hitPose
                return floatArrayOf(pose.tx(), pose.tz())
            }
        }

        // Fallback: raggio dalla camera → intersezione con Y = floorY
        val origin = camera.pose.translation                        // [x, y, z]
        // Punto 1m davanti alla camera in world space
        val ahead  = camera.pose.transformPoint(floatArrayOf(0f, 0f, -1f))
        val dir    = floatArrayOf(
            ahead[0] - origin[0],
            ahead[1] - origin[1],
            ahead[2] - origin[2]
        )
        val dy = dir[1]
        if (abs(dy) < 0.05f) return null          // camera quasi orizzontale, nessuna intersezione affidabile
        val t = (floorY - origin[1]) / dy
        if (t < 0f) return null                   // pavimento dietro alla camera
        return floatArrayOf(
            origin[0] + dir[0] * t,
            origin[2] + dir[2] * t
        )
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun guidanceText(
        tracking:    TrackingState,
        floorLocked: Boolean,
        state:       PerimeterCapture.State,
        ptCount:     Int
    ): Pair<String, String> = when {
        tracking != TrackingState.TRACKING ->
            "Tracking perso" to "Muoviti lentamente e guarda le superfici"
        !floorLocked ->
            "Inizializzazione…" to "Punta verso il pavimento"
        state == PerimeterCapture.State.CLOSED ->
            "Poligono chiuso" to "Premi 'Genera Planimetria'"
        ptCount == 0 ->
            "Spostati in un angolo" to "Punta la reticle verso l'angolo e TAP"
        ptCount == 1 ->
            "Cammina lungo il lato" to "Tap per il prossimo angolo"
        perimeterCapture.canClose ->
            "Aggiungi angoli o chiudi" to "TAP per altro angolo · 'Chiudi' per finire"
        else ->
            "Cammina verso il prossimo angolo" to "Tap per confermare"
    }

    private fun updateActionBtn(state: PerimeterCapture.State, canClose: Boolean) {
        when {
            state == PerimeterCapture.State.CLOSED -> {
                actionBtn.text = "Genera Planimetria"
                actionBtn.setBackgroundColor(Color.argb(255, 18, 120, 70))
                actionBtn.isEnabled = true
            }
            canClose -> {
                actionBtn.text = "Chiudi Poligono"
                actionBtn.setBackgroundColor(Color.argb(255, 18, 90, 130))
                actionBtn.isEnabled = true
            }
            else -> {
                actionBtn.text = "Annulla"
                actionBtn.setBackgroundColor(Color.argb(100, 100, 100, 100))
                actionBtn.isEnabled = true
            }
        }
    }

    private fun updateCaptureUI() {
        updateActionBtn(perimeterCapture.state, perimeterCapture.canClose)
        val ptCount = perimeterCapture.pointCount
        val lastLen = perimeterCapture.lastSegmentLength()
        sideBadge.text = if (lastLen != null)
            "$ptCount lat${if (ptCount == 1) "o" else "i"} · ultimo: ${"%.2f".format(lastLen)}m"
        else
            "$ptCount punt${if (ptCount == 1) "o" else "i"}"
    }

    // ── Stop / cancel ─────────────────────────────────────────────────────────

    fun requestStop() {
        glSurfaceView.queueEvent { doStopScan() }
    }

    fun cancelScanAndFinish() {
        session?.pause(); session?.close(); session = null
        setResult(RESULT_CANCELED)
        finish()
    }

    fun doStopScan() {
        val result = buildResult()
        session?.pause(); session?.close(); session = null
        mainHandler.post {
            onScanComplete?.invoke(result)
            val cb = onScanResult
            if (cb != null) { cb(result); onScanResult = null }
            else pendingResult = result
            setResult(RESULT_OK)
            finish()
        }
    }

    // ── buildResult: dal poligono catturato ───────────────────────────────────

    private fun buildResult(): JSObject {
        val elapsed = (System.currentTimeMillis() - scanStartTime) / 1000.0
        val polygon  = perimeterCapture.getPolygon()
        val version  = arcoreVersion()

        if (polygon.size < 3) {
            return JSObject().apply {
                put("success", false)
                put("error",   "INSUFFICIENT_POINTS")
                put("walls",   JSArray())
                put("floor",   JSObject.NULL)
                put("roomDimensions", zeroDims())
                put("scanMetadata",   meta(elapsed, polygon.size, 0, version))
            }
        }

        val wallHeight = 2.5
        val walls      = buildWallsFromPolygon(polygon, lastFloorY, wallHeight)
        val roomDim    = wallDetector.calculateRoomDimensions(walls, 0.0)
        val floorPlanPath = FloorPlanExporter.export(walls, roomDim, cacheDir)
        val glbPath       = GlbExporter.export(walls, roomDim, cacheDir)

        val wallsArr = JSArray().also { arr -> walls.forEach { arr.put(wallToObj(it)) } }

        // Floor: vertici del poligono
        val floorVerts = JSArray().also { a ->
            polygon.forEach { pt ->
                a.put(JSObject().apply {
                    put("x", pt[0].toDouble())
                    put("y", lastFloorY.toDouble())
                    put("z", pt[1].toDouble())
                })
            }
        }
        val floorObj = JSObject().apply {
            put("vertices", floorVerts)
            put("area",     roomDim.area)
        }

        return JSObject().apply {
            put("success", true)
            put("walls",   wallsArr)
            put("floor",   floorObj)
            put("roomDimensions", JSObject().apply {
                put("width",     roomDim.width)
                put("length",    roomDim.length)
                put("height",    roomDim.height)
                put("area",      roomDim.area)
                put("perimeter", roomDim.perimeter)
            })
            put("scanMetadata", meta(elapsed, polygon.size, walls.size, version))
            if (floorPlanPath != null) put("floorPlanPath", floorPlanPath)
            if (glbPath       != null) put("glbPath",       glbPath)
        }
    }

    /** Converte il poligono XZ in lista di Wall compatibili con FloorPlanExporter/GlbExporter. */
    private fun buildWallsFromPolygon(
        polygon:     List<FloatArray>,
        floorY:      Float,
        wallHeight:  Double
    ): List<Wall> {
        val walls = mutableListOf<Wall>()
        for (i in polygon.indices) {
            val a  = polygon[i]
            val b  = polygon[(i + 1) % polygon.size]
            val dx = (b[0] - a[0]).toDouble()
            val dz = (b[1] - a[1]).toDouble()
            val len = sqrt(dx.pow(2) + dz.pow(2))
            val nx  = if (len > 1e-6) -dz / len else 0.0
            val nz  = if (len > 1e-6)  dx / len else 0.0
            walls.add(Wall(
                id         = "w$i",
                startPoint = Point3D(a[0].toDouble(), floorY.toDouble(), a[1].toDouble()),
                endPoint   = Point3D(b[0].toDouble(), floorY.toDouble(), b[1].toDouble()),
                length     = len,
                height     = wallHeight,
                normal     = Vector3D(nx, 0.0, nz),
                confidence = 1.0
            ))
        }
        return walls
    }

    // ── Serializzazione ───────────────────────────────────────────────────────

    private fun wallToObj(w: Wall) = JSObject().apply {
        put("id",         w.id)
        put("startPoint", ptToObj(w.startPoint))
        put("endPoint",   ptToObj(w.endPoint))
        put("length",     w.length)
        put("height",     w.height)
        put("normal",     JSObject().apply {
            put("x", w.normal.x); put("y", w.normal.y); put("z", w.normal.z)
        })
        put("confidence", w.confidence)
    }

    private fun ptToObj(p: Point3D) = JSObject().apply {
        put("x", p.x); put("y", p.y); put("z", p.z)
    }

    private fun zeroDims() = JSObject().apply {
        put("width", 0.0); put("length", 0.0)
        put("height", 0.0); put("area", 0.0); put("perimeter", 0.0)
    }

    private fun meta(elapsed: Double, pts: Int, walls: Int, ver: String) = JSObject().apply {
        put("scanDurationSeconds", elapsed)
        put("pointsPlaced",        pts)
        put("wallsInResult",       walls)
        put("arcoreVersion",       ver)
    }

    private fun arcoreVersion() = try {
        packageManager.getPackageInfo("com.google.ar.core", 0).versionName ?: "unknown"
    } catch (_: Exception) { "unknown" }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun dp(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()
}
