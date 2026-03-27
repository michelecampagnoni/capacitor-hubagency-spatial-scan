package it.hubagency.spatialscan

import android.app.Activity
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Activity fullscreen che gestisce la scansione ARCore con camera preview.
 *
 * Risolve il bug dei smoke test: ARCore richiede session.setCameraTextureName()
 * prima di session.update(). Senza una texture GL valida la camera LED si accende
 * ma nessun frame viene processato. Questa Activity crea il contesto GL, genera
 * la texture, e la registra sulla sessione ARCore prima del frame loop.
 */
class ScanningActivity : Activity(), GLSurfaceView.Renderer {

    companion object {
        /** Riferimento all'activity in esecuzione, usato dal plugin per stopScan/cancelScan. */
        @Volatile var instance: ScanningActivity? = null

        /**
         * Risultato pre-calcolato se l'utente preme "Ferma" prima che JS chiami stopScan().
         * stopScan() lo consuma e lo restituisce subito senza aspettare l'Activity.
         */
        @Volatile var pendingResult: JSObject? = null

        /** Callback impostata da stopScan() per ricevere il risultato in modo asincrono. */
        @Volatile var onScanResult: ((JSObject) -> Unit)? = null

        /** Callback impostata da startScan() per propagare eventi frame al plugin. */
        @Volatile var onFrameUpdate: ((FrameUpdateData) -> Unit)? = null

        /** Callback impostata da startScan() per propagare eventi tracking al plugin. */
        @Volatile var onTrackingStateChanged: ((String, String?) -> Unit)? = null
    }

    // ── UI ─────────────────────────────────────────────────────────────────────
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView

    // ── ARCore ─────────────────────────────────────────────────────────────────
    private val backgroundRenderer = BackgroundRenderer()
    private var session: Session? = null
    private var sessionCreated = false

    // ── Scan state ─────────────────────────────────────────────────────────────
    private val planeProcessor = PlaneProcessor()
    private val wallDetector = WallDetector()
    private val depthProcessor = DepthProcessor()
    private var depthApiAvailable = false
    private var enableDepth = true
    private var scanStartTime = 0L
    private var lastUiUpdateMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Lifecycle ───────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        enableDepth = intent.getBooleanExtra("enableDepth", true)

        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // GLSurfaceView fullscreen per il feed della camera
        glSurfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setRenderer(this@ScanningActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(glSurfaceView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Overlay superiore — stato tracking
        val topOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 24)
            setBackgroundColor(0xCC000000.toInt())
        }
        statusText = TextView(this).apply {
            text = "Inizializzazione ARCore…"
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        statsText = TextView(this).apply {
            text = "Piani: 0 | Pareti: 0 | Copertura: 0%"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 13f
        }
        topOverlay.addView(statusText)
        topOverlay.addView(statsText)
        root.addView(topOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Pulsanti in basso
        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 24, 32, 80)
            setBackgroundColor(0xCC000000.toInt())
        }
        val stopBtn = Button(this).apply {
            text = "Ferma Scansione"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF2D6A4F.toInt())
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                isEnabled = false
                glSurfaceView.queueEvent { doStopScan() }
            }
        }
        val cancelBtn = Button(this).apply {
            text = "Annulla"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF7F1D1D.toInt())
            setPadding(32, 16, 32, 16)
            setOnClickListener { cancelScanAndFinish() }
        }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 16 }
        btnLayout.addView(stopBtn, lp)
        btnLayout.addView(cancelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btnLayout, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))

        setContentView(root)
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
        session?.close()
        session = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        cancelScanAndFinish()
    }

    // ── GLSurfaceView.Renderer (GL thread) ─────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // 1. Genera camera texture OpenGL
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val cameraTextureId = texIds[0]

        // 2. Inizializza il renderer del background con quella texture
        backgroundRenderer.init(cameraTextureId)

        // 3. Crea la sessione ARCore (sul GL thread, dopo che il contesto EGL è pronto)
        if (!sessionCreated) {
            try {
                session = Session(this)
                val cfg = Config(session!!).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                }
                depthApiAvailable = enableDepth &&
                    session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                if (depthApiAvailable) cfg.depthMode = Config.DepthMode.AUTOMATIC
                session!!.configure(cfg)

                // 4. LINEA CRITICA: registra la GL texture sulla sessione ARCore.
                //    Senza questa chiamata, session.update() non ha dove scrivere i frame
                //    della camera → tracking non parte mai (camera LED ON ma nessuna scansione).
                session!!.setCameraTextureName(cameraTextureId)
                session!!.resume()

                sessionCreated = true
                scanStartTime = System.currentTimeMillis()
                planeProcessor.reset()
                depthProcessor.reset()

                mainHandler.post { statusText.text = "Punta la telecamera verso le pareti" }
            } catch (e: UnavailableArcoreNotInstalledException) {
                mainHandler.post { statusText.text = "ARCore non installato" }
            } catch (e: UnavailableDeviceNotCompatibleException) {
                mainHandler.post { statusText.text = "Dispositivo non compatibile con ARCore" }
            } catch (e: Exception) {
                mainHandler.post { statusText.text = "Errore: ${e.message}" }
            }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
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
            val frame = sess.update()

            // Disegna il feed della camera come background
            backgroundRenderer.draw(frame)

            val now = System.currentTimeMillis()
            val updatedPlanes = frame.getUpdatedTrackables(Plane::class.java)
            planeProcessor.updatePlanes(updatedPlanes)
            if (depthApiAvailable) depthProcessor.processFrame(frame)

            // Aggiorna UI e notifiche ogni secondo
            if (now - lastUiUpdateMs >= 1000L) {
                lastUiUpdateMs = now
                val camera = frame.camera
                val trackingState = camera.trackingState
                val pauseReason = if (trackingState == TrackingState.PAUSED)
                    camera.trackingFailureReason.name else null
                val elapsed = (now - scanStartTime) / 1000.0
                val planesCount = planeProcessor.getTotalActivePlanes()
                val wallsCount = planeProcessor.getTotalActiveWalls()
                val coverage = (planeProcessor.estimateCoverage() * 100).toInt()

                onFrameUpdate?.invoke(FrameUpdateData(
                    trackingState = trackingState.name,
                    planesDetected = planesCount,
                    wallsDetected = wallsCount,
                    coverageEstimate = planeProcessor.estimateCoverage(),
                    scanDurationSeconds = elapsed
                ))
                onTrackingStateChanged?.invoke(trackingState.name, pauseReason)

                mainHandler.post {
                    statusText.text = when (trackingState) {
                        TrackingState.TRACKING -> "Scansione in corso… ${elapsed.toInt()}s"
                        TrackingState.PAUSED   -> "In pausa: ${pauseReason ?: "motivo sconosciuto"}"
                        else                   -> "Stato: ${trackingState.name}"
                    }
                    statsText.text = "Piani: $planesCount | Pareti: $wallsCount | Copertura: $coverage%"
                }
            }
        } catch (_: Exception) {
            // continua il rendering anche in caso di frame drop
        }
    }

    // ── Metodi chiamabili dal plugin ────────────────────────────────────────────

    /**
     * Richiesto da SpatialScanPlugin.stopScan() — accoda il stop sul GL thread
     * così buildResult() ha accesso allo stato più aggiornato.
     */
    fun requestStop() {
        glSurfaceView.queueEvent { doStopScan() }
    }

    /** Chiude la scansione senza salvare il risultato. */
    fun cancelScanAndFinish() {
        session?.pause()
        session?.close()
        session = null
        setResult(RESULT_CANCELED)
        finish()
    }

    // ── Logica interna ─────────────────────────────────────────────────────────

    /**
     * Costruisce il risultato finale e chiude l'Activity.
     * Chiamato sul GL thread per garantire consistenza dei dati ARCore.
     */
    fun doStopScan() {
        val result = buildResult()
        session?.pause()
        session?.close()
        session = null

        mainHandler.post {
            val callback = onScanResult
            if (callback != null) {
                // stopScan() JS è già in attesa: consegna il risultato
                callback.invoke(result)
                onScanResult = null
            } else {
                // stopScan() non ancora chiamato: salva il risultato per dopo
                pendingResult = result
            }
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun buildResult(): JSObject {
        val allPlanes = planeProcessor.getAllPlaneData()
        val elapsed   = (System.currentTimeMillis() - scanStartTime) / 1000.0
        val totalPlanes = planeProcessor.getTotalActivePlanes()

        val walls = wallDetector.extractWalls(allPlanes.filter { it.isWall() })
        val refinedWalls = if (depthApiAvailable && depthProcessor.getAccumulatedPointCount() > 50) {
            depthProcessor.extractWallSegmentsFromDepth(walls)
        } else walls

        val floorArea = planeProcessor.getFloor()?.area?.toDouble() ?: 0.0
        val roomDim = wallDetector.calculateRoomDimensions(refinedWalls, floorArea)

        val arcoreVersion = try {
            packageManager.getPackageInfo("com.google.ar.core", 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }

        return if (refinedWalls.isEmpty()) {
            JSObject().apply {
                put("success", false)
                put("error", "TRACKING_INSUFFICIENT")
                put("walls", JSArray())
                put("floor", JSObject.NULL)
                put("roomDimensions", zeroDims())
                put("scanMetadata", meta(elapsed, totalPlanes, 0, arcoreVersion))
            }
        } else {
            val wallsArr = JSArray().also { arr -> refinedWalls.forEach { arr.put(wallToObj(it)) } }
            val outline = wallDetector.buildRoomOutline(refinedWalls)
            val floorObj = if (outline.isNotEmpty()) JSObject().apply {
                val vArr = JSArray().also { a -> outline.forEach { a.put(ptToObj(it)) } }
                put("vertices", vArr); put("area", floorArea)
            } else JSObject.NULL

            JSObject().apply {
                put("success", true)
                put("walls", wallsArr)
                put("floor", floorObj)
                put("roomDimensions", JSObject().apply {
                    put("width", roomDim.width); put("length", roomDim.length)
                    put("height", roomDim.height); put("area", roomDim.area)
                    put("perimeter", roomDim.perimeter)
                })
                put("scanMetadata", meta(elapsed, totalPlanes, refinedWalls.size, arcoreVersion))
            }
        }
    }

    private fun wallToObj(w: Wall) = JSObject().apply {
        put("id", w.id)
        put("startPoint", ptToObj(w.startPoint)); put("endPoint", ptToObj(w.endPoint))
        put("length", w.length); put("height", w.height)
        put("normal", JSObject().apply { put("x", w.normal.x); put("y", w.normal.y); put("z", w.normal.z) })
        put("confidence", w.confidence)
    }
    private fun ptToObj(p: Point3D) = JSObject().apply { put("x", p.x); put("y", p.y); put("z", p.z) }
    private fun zeroDims() = JSObject().apply {
        put("width", 0.0); put("length", 0.0); put("height", 0.0); put("area", 0.0); put("perimeter", 0.0)
    }
    private fun meta(elapsed: Double, planes: Int, walls: Int, version: String) = JSObject().apply {
        put("scanDurationSeconds", elapsed); put("planesDetected", planes)
        put("wallsInResult", walls); put("depthApiUsed", depthApiAvailable); put("arcoreVersion", version)
    }
}
