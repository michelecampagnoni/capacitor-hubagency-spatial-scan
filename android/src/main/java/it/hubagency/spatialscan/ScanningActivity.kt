// ScanningActivity.kt
// Version : v0.4-ui-round1
// Branch  : capture-stable
// Date    : 2026-03-31
// Status  : SMOKE TEST PASSED — motore GPC stabile, P0/P1/floor ok, openings/export ok
// Changelog:
//   v0.4  Revert completo anchor/local-space architecture → baseline motore GPC world-space
//         Rimossi: roomAnchor, confirmedLocal, capturedHeightM, pendingTap,
//                  processConfirm(), undoLocalModel(), localToWorldPts()
//         Ripristinati: handlePerimeterTap() con lastReticleWorld/lastReticleWorldFree,
//                       livePreview diretto, getPolygon() ovunque
//         Mantenuti: onPause order fix, cancelScanAndFinish queueEvent, isTouchOnVisibleButton,
//                    screenToWorld forceFloor=false 2m fallback, floor grid disabilitata
//   v0.3  Baseline pre-UI-round-2: motore funzionante, openings/export ok
//   v0.2  Openings + export pipeline completa
//   v0.1  Capture motore stabile (GPC world-space, FloorPlaneAnchor)

package it.hubagency.spatialscan

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * ScanningActivity — Guided Perimeter Capture + Opening Placement
 *
 * Flusso principale:
 *  Floor lock → TAP angoli → Chiudi Poligono → Imposta Altezza
 *  → "Aggiungi Aperture" (modalità opening) → Esporta
 *
 * Modalità opening:
 *  L'utente punta un muro → highlight → tap seleziona il muro
 *  → sceglie tipo (Porta / Finestra / Portafinestra)
 *  → il prefab compare centrato sul muro
 *  → stepper regolano posizione/dimensioni
 *  → Conferma salva, oppure passa al muro successivo
 */
class ScanningActivity : Activity(), GLSurfaceView.Renderer {

    companion object {
        @Volatile var instance:               ScanningActivity? = null
        @Volatile var pendingResult:          JSObject?         = null
        @Volatile var onScanResult:           ((JSObject) -> Unit)? = null
        @Volatile var onScanComplete:         ((JSObject) -> Unit)? = null
        @Volatile var onFrameUpdate:          ((FrameUpdateData) -> Unit)? = null
        @Volatile var onTrackingStateChanged: ((String, String?) -> Unit)? = null
    }

    // ── UI: perimeter capture ─────────────────────────────────────────────────
    private lateinit var glSurfaceView:    GLSurfaceView
    private lateinit var reticleView:      ReticleView
    private lateinit var guidanceHeadline: TextView
    private lateinit var guidanceSubtext:  TextView
    private lateinit var guidancePill:     LinearLayout
    private lateinit var sideBadge:        TextView
    private lateinit var timerText:        TextView
    private lateinit var actionBtn:        Button
    private lateinit var undoBtn:          Button
    private lateinit var cancelBtn:        Button
    private lateinit var heightControlRow: LinearLayout
    private lateinit var heightValueText:  TextView
    @Volatile private var wallHeightPreview: Float = 2.50f
    private lateinit var confirmCornerBtn: Button
    private lateinit var pcDebugOverlay:   TextView
    private lateinit var distanceLabel:    TextView
    private lateinit var guidancePillBg:   GradientDrawable
    private lateinit var heightBanner:     TextView
    private lateinit var qualityBassa:     TextView
    private lateinit var qualityMedia:     TextView
    private lateinit var qualityAlta:      TextView

    // ── UI: opening placement ─────────────────────────────────────────────────
    private lateinit var openingPhaseBar:   LinearLayout   // "Aggiungi Aperture" header
    private lateinit var openingTypeRow:    LinearLayout   // [Porta] [Finestra] [Portafinestra]
    private lateinit var openingEditPanel:  LinearLayout   // stepper panel
    private lateinit var openingEditTitle:  TextView
    private lateinit var openingPosText:    TextView
    private lateinit var openingWidthText:  TextView
    private lateinit var openingHeightText: TextView
    private lateinit var openingBottomRow:  LinearLayout   // solo per finestre
    private lateinit var openingBottomText: TextView

    // ── ARCore ───────────────────────────────────────────────────────────────
    private val backgroundRenderer = BackgroundRenderer()
    private var session: Session?  = null
    private var sessionCreated     = false
    // Dopo il floor lock disabilitiamo plane finding: riduce CPU ARCore e la probabilità
    // di perdere il tracking su superfici uniformi. I piani già rilevati rimangono validi.
    @Volatile private var planeFindingDisabled = false

    // ── Perimeter capture ────────────────────────────────────────────────────
    private val perimeterCapture  = PerimeterCapture()
    private val perimeterRenderer = PerimeterRenderer()
    @Volatile private var lastReticleWorld: FloatArray? = null
    @Volatile private var lastLivePreview:  FloatArray? = null
    @Volatile private var screenWidth  = 1
    @Volatile private var screenHeight = 1

    // ── Reticle XZ smoother floor (GL thread only) ────────────────────────────
    // Buffer circolare di 8 campioni XZ. ~267ms a 30fps.
    // Il tap cattura la media smoothed, non il raw istantaneo.
    private val reticleXBuf = FloatArray(8); private val reticleZBuf = FloatArray(8)
    private var reticleBufIdx = 0; private var reticleBufFill = 0

    // ── Reticle libero con smoothing separato — AWAIT_HEIGHT ──────────────────
    // Buffer dedicato per Y (altezza): evita il jitter da hitTest su superfici
    // verticali/soffitto. Reset automatico ogni volta che si entra in AWAIT_HEIGHT.
    @Volatile private var lastReticleWorldFree: FloatArray? = null
    private val heightXBuf = FloatArray(4); private val heightYBuf = FloatArray(4)
    private val heightZBuf = FloatArray(4)  // ridotto da 8 → 4 (~133ms lag, meno sticky)
    private var heightBufIdx  = 0; private var heightBufFill = 0

    // ── Goniometer snap ───────────────────────────────────────────────────────
    // Il goniometro è centrato sull'ultimo punto confermato. Snap a 5° nel settore
    // visibile intorno alla direzione corrente del reticolo.
    @Volatile private var lastSnappedReticle:    FloatArray? = null
    @Volatile private var reticleIsSnapped:      Boolean     = false
    @Volatile private var reticleIsRealHit:      Boolean     = false
    @Volatile private var goniometerCenterPt:    FloatArray? = null  // ultimo punto floor
    @Volatile private var goniometerCurrentAngle: Float      = 0f    // angolo reticolo (rad)
    @Volatile private var goniometerSnapAngle:   Float?      = null  // angolo snappato (rad)

    // ── Top reticle mode (auto da tilt camera) ───────────────────────────────
    // Attivato automaticamente quando la camera punta verso l'alto oltre soglia.
    // Proietta il raggio camera sul piano Y = lastFloorY + wallHeightPreview.
    // Isteresi: ON a camFwdY > 0.20 (~12°), OFF a camFwdY < 0.10 (~6°).
    @Volatile private var reticleTopMode      = false
    @Volatile private var lastTopCursorWorld: FloatArray? = null

    // ── Freeze-at-close (anti-drift) ──────────────────────────────────────────
    // Al close: snapshot world coords calcolate dall'anchor. Immutabile.
    @Volatile private var frozenPolygon: List<FloatArray>? = null
    @Volatile private var frozenFloorY:  Float?           = null

    // ── Fresh hit test at tap ─────────────────────────────────────────────────
    // Ultimo frame/camera ARCore valido — usati per hit test fresco al tap.
    // Scritti solo sul GL thread (onDrawFrame). Letti sul GL thread (queueEvent).
    @Volatile private var lastArFrame:  Frame?  = null
    @Volatile private var lastArCamera: Camera? = null



    // ── Floor / tracking ─────────────────────────────────────────────────────
    @Volatile private var lastFloorY = 0f
    private val floorAnchor  = FloorPlaneAnchor()
    private val pcSampler    = PointCloudSampler()
    private var scanStartTime  = 0L
    private var lastUiUpdateMs = 0L
    private var lastDebugMs    = 0L
    private val mainHandler    = Handler(Looper.getMainLooper())

    // ── Plane overlay ─────────────────────────────────────────────────────────
    private val planeOverlayRenderer = PlaneOverlayRenderer()

    // ── Opening placement (Fase 2) ────────────────────────────────────────────
    private val openingRenderer = OpeningRenderer()
    @Volatile private var roomModel:      RoomModel?    = null
    @Volatile private var hoveredWallId:  String?       = null
    @Volatile private var selectedWallId: String?       = null
    @Volatile private var editingOpening: OpeningModel? = null
    @Volatile private var openingMode     = false
    // Metadati collegamento per ogni apertura classificata (keyed by OpeningModel.id)
    private val openingMetadataMap = mutableMapOf<String, OpeningMetadata>()
    // Spec apertura ereditata dalla stanza precedente (da Intent)
    private var linkedOpeningSpec:  LinkedOpeningSpec? = null
    private var linkedSpecAccepted = false   // utente ha confermato il riuso
    private var linkedSpecPlaced   = false   // spec già usata per il primo spawn

    // ── Legacy (non attivi) ───────────────────────────────────────────────────
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
        linkedOpeningSpec = LinkedOpeningSpec.fromIntent(intent)
        @Suppress("DEPRECATION")
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildLayout())
        wireListeners()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        try { session?.resume() } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        // ORDINE OBBLIGATORIO (da ARCore best practices):
        // 1. ferma il GL thread PRIMA di pausare la session
        // 2. se invertito, il GL thread può chiamare sess.update() su una session già pausata → SIGSEGV
        glSurfaceView.onPause()
        session?.pause()
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
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // GL fullscreen
        glSurfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setRenderer(this@ScanningActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(glSurfaceView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Reticle
        reticleView = ReticleView(this).apply { isClickable = false; isFocusable = false }
        root.addView(reticleView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Striscia HUD decorativa in cima — ">>> · · · · <<<"
        val topHudStrip = TextView(this).apply {
            text = ">>>  · · · · · · · · · · · · · · ·  <<<"
            setTextColor(Color.argb(110, 30, 190, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(130, 3, 4, 14))
            setPadding(0, dp(5), 0, dp(5))
        }
        root.addView(topHudStrip, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP })

        // Guidance — NIENTE background, testo floating puro
        guidancePillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; setColor(Color.TRANSPARENT)
        }
        guidancePill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            background = guidancePillBg
        }
        guidanceHeadline = TextView(this).apply {
            text = "Inizializzazione…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0))
        }
        guidanceSubtext = TextView(this).apply {
            text = "Muoviti verso il pavimento"
            setTextColor(Color.argb(210, 20, 215, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 1f, Color.argb(140, 0, 0, 0))
        }
        guidancePill.addView(guidanceHeadline)
        guidancePill.addView(guidanceSubtext)
        root.addView(guidancePill, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(44) })

        // Bottom bar — sci-fi dark navy
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(225, 3, 4, 14))
            setPadding(dp(16), dp(14), dp(16), dp(36))
        }

        // Stats row
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        sideBadge = TextView(this).apply {
            text = "In attesa…"
            setTextColor(Color.argb(230, 20, 215, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }
        timerText = TextView(this).apply {
            text = "0s"
            setTextColor(Color.argb(150, 130, 150, 200))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        statsRow.addView(sideBadge, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statsRow.addView(timerText)
        bottomBar.addView(statsRow)

        // ANGOLO QUI / ALTEZZA QUI button — fucsia neon
        confirmCornerBtn = Button(this).apply {
            text = "ANGOLO QUI"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(Color.argb(255, 185, 18, 125))
                setStroke(dp(1), Color.argb(200, 230, 60, 170))
            }
            setPadding(dp(20), dp(12), dp(20), dp(12))
            visibility = android.view.View.GONE
            setOnClickListener { handlePerimeterTap() }
        }
        // confirmCornerBtn is added to mainBtnRow below — not here

        // ── Opening phase bar ─────────────────────────────────────────────────
        openingPhaseBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }

        // Header "APERTURE" con titolo fase
        val openingHeader = TextView(this).apply {
            text = "APERTURE  ·  Punta un muro e selezionalo"
            setTextColor(Color.argb(255, 20, 215, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        openingPhaseBar.addView(openingHeader)

        // Type row: [Porta] [Finestra] [Portafinestra]
        openingTypeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = android.view.View.GONE  // visibile solo dopo selezione muro
        }
        for (kind in OpeningKind.entries) {
            val btn = Button(this).apply {
                text = kind.label
                setTextColor(Color.argb(230, 20, 215, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(5).toFloat()
                    setColor(Color.argb(180, 8, 14, 35))
                    setStroke(dp(1), Color.argb(150, 20, 180, 255))
                }
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { spawnOpening(kind) }
            }
            openingTypeRow.addView(btn, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = dp(4) })
        }
        openingPhaseBar.addView(openingTypeRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        // Edit panel (stepper)
        openingEditPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        openingEditTitle = TextView(this).apply {
            text = "Porta"
            setTextColor(Color.argb(255, 255, 200, 80))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        }
        openingEditPanel.addView(openingEditTitle)

        // Riga spostamento laterale — frecce ◀ ▶ (non coordinate)
        openingPosText    = addStepperRow(openingEditPanel, "Sposta",  "◀", "▶",
            { nudgeOpening(+0.05f) }, { nudgeOpening(-0.05f) })
        // Riga larghezza — resize simmetrico (si allarga da entrambi i lati)
        openingWidthText  = addStepperRow(openingEditPanel, "Larghezza ↔",  "−", "+",
            { adjustOpening(dW = -0.05f) }, { adjustOpening(dW = +0.05f) })
        // Riga altezza apertura
        openingHeightText = addStepperRow(openingEditPanel, "Altezza ↕",    "−", "+",
            { adjustOpening(dH = -0.05f) }, { adjustOpening(dH = +0.05f) })
        // Riga posizione verticale (quota da terra) — solo finestre — frecce ▼ ▲
        openingBottomRow  = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        openingBottomText = addStepperRow(openingBottomRow, "Quota",  "▼", "▲",
            { adjustOpening(dB = -0.05f) }, { adjustOpening(dB = +0.05f) })
        openingEditPanel.addView(openingBottomRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Confirm / Delete row
        val editBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val confirmOpeningBtn = Button(this).apply {
            text = "Conferma"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(5).toFloat()
                setColor(Color.argb(255, 185, 18, 125))
                setStroke(dp(1), Color.argb(200, 230, 60, 170))
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { confirmOpening() }
        }
        val deleteOpeningBtn = Button(this).apply {
            text = "Elimina"
            setTextColor(Color.argb(220, 255, 80, 80))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(5).toFloat()
                setColor(Color.argb(180, 12, 4, 4))
                setStroke(dp(1), Color.argb(160, 200, 40, 40))
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { deleteEditingOpening() }
        }
        editBtnRow.addView(confirmOpeningBtn, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        editBtnRow.addView(deleteOpeningBtn, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        openingEditPanel.addView(editBtnRow)

        openingPhaseBar.addView(openingEditPanel)
        bottomBar.addView(openingPhaseBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        // Height control (post-chiusura, pre-opening)
        heightControlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
            visibility = android.view.View.GONE
        }
        val heightLabel = TextView(this).apply {
            text = "Altezza:"
            setTextColor(Color.argb(200, 170, 200, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, dp(6), 0)
        }
        heightValueText = TextView(this).apply {
            text = "2.50m"
            setTextColor(Color.argb(255, 30, 235, 120))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(8), 0)
        }
        val stepperBg = { GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = dp(4).toFloat()
            setColor(Color.argb(160, 8, 10, 25)); setStroke(dp(1), Color.argb(100, 60, 100, 200))
        }}
        val btnHeightMinus = Button(this).apply {
            text = "−"; setTextColor(Color.argb(220, 20, 215, 255))
            background = stepperBg()
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setOnClickListener {
                wallHeightPreview = (wallHeightPreview - 0.1f).coerceAtLeast(1.80f)
                heightValueText.text = "${"%.2f".format(wallHeightPreview)}m"
            }
        }
        val btnHeightPlus = Button(this).apply {
            text = "+"; setTextColor(Color.argb(220, 20, 215, 255))
            background = stepperBg()
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setOnClickListener {
                wallHeightPreview = (wallHeightPreview + 0.1f).coerceAtMost(5.00f)
                heightValueText.text = "${"%.2f".format(wallHeightPreview)}m"
            }
        }
        heightControlRow.addView(heightLabel)
        heightControlRow.addView(btnHeightMinus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        heightControlRow.addView(heightValueText)
        heightControlRow.addView(btnHeightPlus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottomBar.addView(heightControlRow)

        // ── Layout bottoni reference-style: [INDIETRO] [AZIONE PRIMARIA] ───────
        // undoBtn: piccolo, nella riga stats
        undoBtn = Button(this).apply {
            text = "↩"
            setTextColor(Color.argb(200, 140, 80, 210))
            setBackgroundColor(Color.argb(0, 0, 0, 0))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            isEnabled = false
            setOnClickListener {
                glSurfaceView.queueEvent {
                    perimeterCapture.undo()
                    mainHandler.post { updateCaptureUI() }
                }
            }
        }
        statsRow.addView(undoBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(4) })

        // actionBtn: secondario, sopra la riga principale (chiudi/aggiungi aperture)
        actionBtn = Button(this).apply {
            text = "Annulla"
            setTextColor(Color.argb(210, 20, 215, 255))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = dp(5).toFloat()
                setColor(Color.argb(160, 8, 10, 28)); setStroke(dp(1), Color.argb(120, 30, 120, 240))
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(16), dp(7), dp(16), dp(7))
            visibility = android.view.View.GONE
        }
        bottomBar.addView(actionBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })

        // Riga bottoni principale: [INDIETRO outlined] [CONFERMA fucsia]
        val mainBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        cancelBtn = Button(this).apply {
            text = "INDIETRO"
            setTextColor(Color.argb(210, 180, 190, 220))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = dp(5).toFloat()
                setColor(Color.argb(130, 8, 10, 25)); setStroke(dp(1), Color.argb(130, 80, 100, 180))
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener {
                if (perimeterCapture.canUndo) {
                    glSurfaceView.queueEvent {
                        perimeterCapture.undo()
                        mainHandler.post { updateCaptureUI() }
                    }
                } else {
                    cancelScanAndFinish()
                }
            }
        }
        mainBtnRow.addView(cancelBtn, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        mainBtnRow.addView(confirmCornerBtn, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f))
        bottomBar.addView(mainBtnRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        // Quality bar — ">>> BASSA  MEDIA  ALTA"
        val qualityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(4))
        }
        val qualityDecor = TextView(this).apply {
            text = "> >> "; setTextColor(Color.argb(140, 30, 190, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        fun qualLabel(text: String): TextView = TextView(this).apply {
            this.text = text
            setTextColor(Color.argb(90, 130, 140, 200))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), 0, dp(6), 0)
        }
        qualityBassa = qualLabel("BASSA")
        qualityMedia = qualLabel("MEDIA")
        qualityAlta  = qualLabel("ALTA")
        qualityRow.addView(qualityDecor)
        qualityRow.addView(qualityBassa)
        qualityRow.addView(qualityMedia)
        qualityRow.addView(qualityAlta)
        bottomBar.addView(qualityRow)

        root.addView(bottomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))

        // Debug overlay
        pcDebugOverlay = TextView(this).apply {
            text = "GPC v2"
            setTextColor(Color.argb(200, 180, 255, 180))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(Color.argb(160, 0, 0, 0))
        }
        root.addView(pcDebugOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START; topMargin = dp(44) })

        // Banner IMPOSTA ALTEZZA — fucsia grande, solo durante AWAIT_HEIGHT
        heightBanner = TextView(this).apply {
            text = "IMPOSTA ALTEZZA"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = dp(4).toFloat()
                setColor(Color.argb(250, 185, 18, 125))
                setStroke(dp(1), Color.argb(210, 230, 60, 170))
            }
            setPadding(dp(28), dp(14), dp(28), dp(14))
            visibility = android.view.View.GONE
            isClickable = false; isFocusable = false
        }
        root.addView(heightBanner, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(145) })

        // Floating distance / height label — grande, fucsia/cyan, centro schermo +120dp
        distanceLabel = TextView(this).apply {
            text = ""
            setTextColor(Color.argb(255, 230, 30, 155))   // fucsia come nella reference
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setShadowLayer(16f, 0f, 0f, Color.argb(200, 200, 20, 130))
            isClickable = false; isFocusable = false
        }
        root.addView(distanceLabel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }).also {
            distanceLabel.translationY = dp(120).toFloat()
        }

        return root
    }

    /** Aggiunge una riga stepper (label  − value +) a un container e restituisce il TextView del valore. */
    private fun addStepperRow(
        container: LinearLayout,
        label: String,
        minusLabel: String,
        plusLabel: String,
        onMinus: () -> Unit,
        onPlus:  () -> Unit
    ): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        val lbl = TextView(this).apply {
            text = label
            setTextColor(Color.argb(200, 170, 200, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val valTv = TextView(this).apply {
            text = "—"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val btnM = Button(this).apply {
            text = minusLabel; setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(180, 60, 60, 80))
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { onMinus() }
        }
        val btnP = Button(this).apply {
            text = plusLabel; setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(180, 60, 60, 80))
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { onPlus() }
        }
        row.addView(lbl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        row.addView(btnM, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(valTv, LinearLayout.LayoutParams(dp(60), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(btnP, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return valTv
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    /**
     * Restituisce true se le coordinate raw del touch (rawX, rawY) cadono
     * dentro i bounds di un pulsante visibile sovrapposto al GLSurfaceView.
     * Impedisce che il touch listener del GL surface intercetti click sui bottoni.
     */
    private fun isTouchOnVisibleButton(rawX: Float, rawY: Float): Boolean {
        val buttons = listOf(confirmCornerBtn, undoBtn, cancelBtn, actionBtn)
        val loc = IntArray(2)
        for (btn in buttons) {
            if (btn.visibility != android.view.View.VISIBLE) continue
            btn.getLocationOnScreen(loc)
            if (rawX >= loc[0] && rawX <= loc[0] + btn.width &&
                rawY >= loc[1] && rawY <= loc[1] + btn.height) return true
        }
        return false
    }

    private fun wireListeners() {
        glSurfaceView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                if (!isTouchOnVisibleButton(event.rawX, event.rawY)) {
                    if (openingMode) handleOpeningTap()
                    else handlePerimeterTap()
                }
            }
            true
        }

        actionBtn.setOnClickListener {
            when {
                openingMode -> {
                    // In opening mode: "Esporta" → genera risultato
                    actionBtn.isEnabled = false
                    actionBtn.text = "Elaborazione…"
                    glSurfaceView.queueEvent { doStopScan() }
                }
                perimeterCapture.state == PerimeterCapture.State.CLOSED &&
                    roomModel != null -> {
                    // Già in fase altezza: entra in opening mode
                    enterOpeningMode()
                }
                perimeterCapture.state == PerimeterCapture.State.CLOSED -> {
                    // Prima volta che si preme dopo la chiusura: build RoomModel
                    buildRoomModel()
                    enterOpeningMode()
                }
                perimeterCapture.canClose -> {
                    glSurfaceView.queueEvent {
                        perimeterCapture.close()
                        frozenPolygon = perimeterCapture.getPolygon().map { it.copyOf() }
                        frozenFloorY = lastFloorY
                        val capturedH = perimeterCapture.capturedHeight
                        mainHandler.post {
                            // wallHeightPreview rimane quello impostato dall'utente con lo stepper.
                            // capturedH (dal tap P1) era usato per sovrascriverlo, ma causava
                            // reset indesiderati: lo stepper è l'unica fonte autoritativa.
                            updateCaptureUI()
                        }
                    }
                }
                else -> cancelScanAndFinish()
            }
        }
    }

    private fun handlePerimeterTap() {
        val phase = perimeterCapture.capturePhase
        if (phase == PerimeterCapture.CapturePhase.AWAIT_HEIGHT) {
            // Usa il valore dello stepper come altezza confermata.
            // Il reticolo libero (lastReticleWorldFree) non è affidabile per il soffitto:
            // spesso non trova feature points e ricade nel fallback "2m avanti alla camera",
            // producendo altezze sbagliate. Lo stepper è l'unica fonte autoritativa.
            val h = wallHeightPreview
            glSurfaceView.queueEvent {
                perimeterCapture.addPoint(0f, h, 0f)
                mainHandler.post { updateCaptureUI() }
            }
        } else {
            glSurfaceView.queueEvent {
                // Usa la posizione snappata all'asse se disponibile (più precisa),
                // altrimenti freshRw → lastReticleWorld come fallback.
                val rw = lastSnappedReticle ?: run {
                    val cx = screenWidth / 2f; val cy = screenHeight / 2f
                    lastArFrame?.let { f -> lastArCamera?.let { c ->
                        // Entrambe le modalità usano geometria pura dopo il floor lock.
                        // Nessun ARCore hit test — elimina drift da plane detection.
                        if (reticleTopMode) screenToWorldTopPlane(c)
                        else screenToWorldFloorPlane(c)
                    }} ?: lastReticleWorld
                } ?: return@queueEvent
                perimeterCapture.addPoint(rw[0], rw[1], rw[2])
                mainHandler.post { updateCaptureUI() }
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
        planeOverlayRenderer.init()
        openingRenderer.init()
        confirmedWallRenderer.init()
        roomPreviewRenderer.init()
        roomBoxRenderer.init()

        if (!sessionCreated) {
            try {
                session = Session(this)
                val cfg = Config(session!!).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    updateMode       = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode        = Config.FocusMode.AUTO
                }
                session!!.configure(cfg)
                session!!.setCameraTextureName(texIds[0])
                session!!.resume()
                sessionCreated = true
                scanStartTime  = System.currentTimeMillis()
                floorAnchor.reset(); pcSampler.reset(); perimeterCapture.reset()
                frozenPolygon = null; frozenFloorY = null
                lastReticleWorld = null; lastLivePreview = null; lastReticleWorldFree = null
                lastSnappedReticle = null; reticleIsSnapped = false; reticleIsRealHit = false
                goniometerCenterPt = null; goniometerCurrentAngle = 0f; goniometerSnapAngle = null
                reticleTopMode = false; lastTopCursorWorld = null
                planeFindingDisabled = false
                reticleBufIdx = 0; reticleBufFill = 0
                heightBufIdx  = 0; heightBufFill  = 0
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
        screenWidth = width; screenHeight = height
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            display?.rotation ?: Surface.ROTATION_0
        else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        session?.setDisplayGeometry(rotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val sess = session ?: return
        try {
            val frame  = sess.update()
            backgroundRenderer.draw(frame)
            val camera = frame.camera
            lastArFrame = frame; lastArCamera = camera
            val nowMs  = System.currentTimeMillis()

            // Floor Y
            val allPlanes = sess.getAllTrackables(Plane::class.java)
            lastFloorY = floorAnchor.update(allPlanes, camera.pose.ty(),
                camera.trackingState == TrackingState.TRACKING)
            pcSampler.sample(frame, lastFloorY)

            // Dopo il floor lock: disabilita plane finding per ridurre CPU ARCore
            // e la probabilità di perdere il tracking su superfici uniformi.
            // Il posizionamento passa interamente alla geometria (screenToWorldFloorPlane /
            // screenToWorldTopPlane). ARCore serve solo per camera pose (IMU + odometria).
            if (floorAnchor.isLocked && !planeFindingDisabled) {
                try {
                    sess.configure(Config(sess).apply {
                        planeFindingMode = Config.PlaneFindingMode.DISABLED
                        updateMode       = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        focusMode        = Config.FocusMode.AUTO
                    })
                    planeFindingDisabled = true
                } catch (_: Exception) {}
            }

            if (camera.trackingState == TrackingState.TRACKING) {
                val phase = perimeterCapture.capturePhase
                val cx = screenWidth / 2f; val cy = screenHeight / 2f

                // ── Auto-switch TOP/FLOOR da inclinazione camera ──────────────────
                // camFwd[1] = componente Y del vettore forward (positivo = camera guarda in su).
                // Calcolato dal pose ARCore (già usa giroscopio + accelerometro internamente).
                // Isteresi: attiva TOP a >0.20 (~12°), disattiva a <0.10 (~6°).
                // Reset buffer al cambio di modalità per evitare blend di campioni misti.
                val camFwdAuto = camera.pose.rotateVector(floatArrayOf(0f, 0f, -1f))
                val canActivateTop = !openingMode &&
                    (phase == PerimeterCapture.CapturePhase.AWAIT_SECOND_FLOOR ||
                     phase == PerimeterCapture.CapturePhase.FLOOR_ONLY)
                if (canActivateTop) {
                    if (camFwdAuto[1] > 0.20f && !reticleTopMode) {
                        reticleTopMode = true
                        reticleBufIdx = 0; reticleBufFill = 0
                    } else if (camFwdAuto[1] < 0.10f && reticleTopMode) {
                        reticleTopMode = false
                        reticleBufIdx = 0; reticleBufFill = 0
                        lastTopCursorWorld = null
                    }
                } else if (reticleTopMode) {
                    reticleTopMode = false
                    lastTopCursorWorld = null
                }

                // ── Reticle — floor mode (default) o top mode (auto da tilt) ──────
                // TOP mode: proietta il raggio camera sul piano Y = lastFloorY + wallHeight.
                // Permette di puntare il bordo soffitto/parete quando il pavimento è coperto.
                val topModeActive = reticleTopMode && canActivateTop
                if (topModeActive) {
                    val topHit = screenToWorldTopPlane(camera)
                    reticleIsRealHit = topHit != null
                    if (topHit != null) {
                        reticleXBuf[reticleBufIdx] = topHit[0]
                        reticleZBuf[reticleBufIdx] = topHit[2]
                        reticleBufIdx = (reticleBufIdx + 1) % reticleXBuf.size
                        if (reticleBufFill < reticleXBuf.size) reticleBufFill++
                        var sumX = 0f; var sumZ = 0f
                        for (k in 0 until reticleBufFill) { sumX += reticleXBuf[k]; sumZ += reticleZBuf[k] }
                        val normRw = floatArrayOf(sumX / reticleBufFill, lastFloorY, sumZ / reticleBufFill)
                        lastReticleWorld = normRw
                        lastLivePreview = perimeterCapture.livePreview(normRw[0], normRw[1], normRw[2])
                        lastTopCursorWorld = floatArrayOf(normRw[0], lastFloorY + wallHeightPreview, normRw[2])
                    }
                    // else: sticky — lastReticleWorld e lastLivePreview restano quelli del frame precedente
                } else {
                    lastTopCursorWorld = null
                    // Dopo floor lock: proiezione geometrica pura (raggio camera → piano Y=lastFloorY).
                    // Nessun ARCore hit test — elimina la fonte principale di drift/confusione.
                    // Prima del lock: screenToWorld per trovare superfici durante l'inizializzazione.
                    val rawRw = if (floorAnchor.isLocked) screenToWorldFloorPlane(camera)
                                else screenToWorld(frame, camera, cx, cy, forceFloor = false)
                    reticleIsRealHit = rawRw != null
                    if (rawRw != null) {
                        val projY = if (floorAnchor.isLocked) lastFloorY else rawRw[1]
                        reticleXBuf[reticleBufIdx] = rawRw[0]
                        reticleZBuf[reticleBufIdx] = rawRw[2]
                        reticleBufIdx = (reticleBufIdx + 1) % reticleXBuf.size
                        if (reticleBufFill < reticleXBuf.size) reticleBufFill++
                        var sumX = 0f; var sumZ = 0f
                        for (k in 0 until reticleBufFill) { sumX += reticleXBuf[k]; sumZ += reticleZBuf[k] }
                        val normRw = floatArrayOf(sumX / reticleBufFill, projY, sumZ / reticleBufFill)
                        lastReticleWorld = normRw
                        lastLivePreview = perimeterCapture.livePreview(normRw[0], normRw[1], normRw[2])
                    }
                    // else: sticky — lastReticleWorld e lastLivePreview restano quelli del frame precedente
                }

                // ── Goniometer snap ──────────────────────────────────────────────
                // Centrato sull'ultimo punto confermato. Snap al raggio 1° più vicino.
                // Snap reale: 10cm. Tracking perso: 30cm. Min dist dal centro: 20cm.
                // Il settore visibile segue la direzione orizzontale della camera
                // (crosshair fisso a centro schermo), non il reticolo ARCore.
                val gonioCenterPt = perimeterCapture.getPolygon().lastOrNull()
                // Proiezione geometrica camera→floor SEMPRE fresca (segue il crosshair).
                // Non dipende da ARCore hit: funziona anche in zone buie/angoli difficili.
                // Quando camera guarda verso l'alto o quasi orizzontale (floor > 15m)
                // usa la direzione XZ della camera a 3m davanti al dispositivo.
                val cp2  = camera.pose.translation
                val cf2  = camera.pose.rotateVector(floatArrayOf(0f, 0f, -1f))
                val dy2  = cf2[1]
                val geoFloor: FloatArray = if (dy2 < -0.005f) {
                    val t = (lastFloorY - cp2[1]) / dy2
                    if (t in 0f..15f)
                        floatArrayOf(cp2[0] + t * cf2[0], lastFloorY, cp2[2] + t * cf2[2])
                    else {
                        // Floor troppo lontana: XZ forward a 3m
                        val xzL = sqrt(cf2[0] * cf2[0] + cf2[2] * cf2[2])
                        if (xzL > 0.01f) floatArrayOf(cp2[0] + cf2[0] / xzL * 3f, lastFloorY, cp2[2] + cf2[2] / xzL * 3f)
                        else lastReticleWorld ?: floatArrayOf(cp2[0], lastFloorY, cp2[2])
                    }
                } else {
                    // Camera guarda su/orizzontale: XZ forward a 3m
                    val xzL = sqrt(cf2[0] * cf2[0] + cf2[2] * cf2[2])
                    if (xzL > 0.01f) floatArrayOf(cp2[0] + cf2[0] / xzL * 3f, lastFloorY, cp2[2] + cf2[2] / xzL * 3f)
                    else lastReticleWorld ?: floatArrayOf(cp2[0], lastFloorY, cp2[2])
                }
                // In TOP mode: usa lastReticleWorld (XZ da piano superiore, già corretto)
                // invece di geoFloor (XZ "3m avanti" quando camera guarda in alto → sbagliato).
                val baseReticle = if (topModeActive) lastReticleWorld ?: geoFloor else geoFloor
                goniometerCenterPt = gonioCenterPt
                // Settore: direzione orizzontale della camera → segue il crosshair fisso
                val camFwd = camera.pose.rotateVector(floatArrayOf(0f, 0f, -1f))
                val fwdXZLen = sqrt(camFwd[0] * camFwd[0] + camFwd[2] * camFwd[2])
                if (fwdXZLen > 0.01f)
                    goniometerCurrentAngle = kotlin.math.atan2(camFwd[2], camFwd[0])
                // Snap: 1° step, basato sulla posizione del reticolo sul pavimento
                if (gonioCenterPt != null && baseReticle != null) {
                    val cx2 = gonioCenterPt[0]; val cz2 = gonioCenterPt[2]
                    val vx  = baseReticle[0] - cx2; val vz = baseReticle[2] - cz2
                    val dist = sqrt(vx * vx + vz * vz)
                    if (dist > 0.20f) {   // troppo vicino al centro → nessuno snap (jitter)
                        val rawAngle  = kotlin.math.atan2(vz, vx)
                        val STEP_RAD  = Math.toRadians(1.0).toFloat()   // snap ogni 1°
                        val snapAngle = (rawAngle / STEP_RAD).roundToInt() * STEP_RAD
                        val perpDist  = dist * kotlin.math.abs(kotlin.math.sin(rawAngle - snapAngle))
                        val SNAP_RADIUS = if (reticleIsRealHit) 0.10f else 0.30f
                        if (perpDist < SNAP_RADIUS) {
                            val sx = cx2 + kotlin.math.cos(snapAngle) * dist
                            val sz = cz2 + kotlin.math.sin(snapAngle) * dist
                            lastSnappedReticle  = floatArrayOf(sx, lastFloorY, sz)
                            reticleIsSnapped    = true
                            goniometerSnapAngle = snapAngle
                            lastLivePreview     = lastSnappedReticle
                        } else {
                            lastSnappedReticle  = baseReticle
                            reticleIsSnapped    = false
                            goniometerSnapAngle = null
                        }
                    } else {
                        lastSnappedReticle  = baseReticle
                        reticleIsSnapped    = false
                        goniometerSnapAngle = null
                    }
                } else {
                    lastSnappedReticle  = baseReticle
                    reticleIsSnapped    = false
                    goniometerSnapAngle = null
                }

                // ── Sync post-goniometro ──────────────────────────────────────────
                // livePreview: aggiornata dall'output goniometro (XZ snappato o raw).
                // lastTopCursorWorld: NON modificato qui — rimane all'XZ del mirino (normRw)
                // impostato nel branch reticle. Il cursore arancione segue il centro schermo,
                // non la posizione del goniometro.
                val sr = lastSnappedReticle
                if (gonioCenterPt != null && sr != null) lastLivePreview = sr

                // ── Reticle libero con smoothing dedicato — solo AWAIT_HEIGHT ──
                // Buffer separato da quello floor: reset automatico quando si esce dalla fase.
                if (phase == PerimeterCapture.CapturePhase.AWAIT_HEIGHT) {
                    val rawFree = screenToWorld(frame, camera, cx, cy, forceFloor = false)
                    if (rawFree != null) {
                        heightXBuf[heightBufIdx] = rawFree[0]
                        heightYBuf[heightBufIdx] = rawFree[1]
                        heightZBuf[heightBufIdx] = rawFree[2]
                        heightBufIdx = (heightBufIdx + 1) % heightXBuf.size
                        if (heightBufFill < heightXBuf.size) heightBufFill++
                        var hX = 0f; var hY = 0f; var hZ = 0f
                        for (k in 0 until heightBufFill) { hX += heightXBuf[k]; hY += heightYBuf[k]; hZ += heightZBuf[k] }
                        lastReticleWorldFree = floatArrayOf(hX / heightBufFill, hY / heightBufFill, hZ / heightBufFill)
                    }
                    // else: sticky — lastReticleWorldFree resta il valore del frame precedente
                } else {
                    // Reset buffer altezza quando non in AWAIT_HEIGHT → buffer pulito alla prossima entrata
                    heightBufIdx = 0; heightBufFill = 0
                    lastReticleWorldFree = null
                }

                // Altezza live per preview verticale e sideBadge
                // liveHeightM: usa wallHeightPreview (stepper) così la linea di anteprima
                // riflette istantaneamente ciò che l'utente imposta, senza dipendere dal
                // raycast verso il soffitto (spesso inaffidabile su superfici lisce).
                val liveHeightM: Float? = if (phase == PerimeterCapture.CapturePhase.AWAIT_HEIGHT)
                    wallHeightPreview
                else null

                val viewMatrix = FloatArray(16); val projMatrix = FloatArray(16)
                camera.getViewMatrix(viewMatrix, 0)
                camera.getProjectionMatrix(projMatrix, 0, 0.05f, 100f)

                val currentWorldPts = frozenPolygon ?: perimeterCapture.getPolygon()
                perimeterRenderer.draw(
                    confirmedPts          = currentWorldPts,
                    livePoint             = if (openingMode || phase == PerimeterCapture.CapturePhase.AWAIT_HEIGHT) null else lastLivePreview,
                    isClosed              = perimeterCapture.state == PerimeterCapture.State.CLOSED,
                    canClose              = perimeterCapture.canClose,
                    wallHeight            = wallHeightPreview,
                    capturePhase          = phase,
                    liveHeightM           = liveHeightM,
                    viewMatrix            = viewMatrix,
                    projMatrix            = projMatrix,
                    floorGridCenter       = frozenPolygon?.firstOrNull()
                        ?: currentWorldPts.firstOrNull()
                        ?: lastReticleWorld,
                    reticleSnapped        = reticleIsSnapped,
                    goniometerCenter      = if (!openingMode && phase != PerimeterCapture.CapturePhase.AWAIT_HEIGHT && !perimeterCapture.state.let { it == PerimeterCapture.State.CLOSED }) goniometerCenterPt else null,
                    goniometerAngle       = goniometerCurrentAngle,
                    goniometerSnapAngle   = goniometerSnapAngle,
                    currentFloorY         = lastFloorY,
                    topCursorPoint        = lastTopCursorWorld
                )

                // Opening render (solo in opening mode)
                val rm = roomModel
                if (openingMode && rm != null) {
                    val reticle = lastReticleWorld
                    if (reticle != null) hoveredWallId = pickWall(reticle, rm)
                    // Usa frozenFloorY: il pavimento è congelato al momento del close,
                    // nessuna dipendenza da lastFloorY live.
                    val baseY = frozenFloorY ?: lastFloorY
                    openingRenderer.draw(rm, baseY, hoveredWallId, selectedWallId, viewMatrix, projMatrix)
                }
            }

            // UI throttled 300ms
            if (nowMs - lastUiUpdateMs >= 300L) {
                lastUiUpdateMs = nowMs
                val trackingState = camera.trackingState
                val capturePhaseUi = perimeterCapture.capturePhase
                val liveHmUi: Float? = if (capturePhaseUi == PerimeterCapture.CapturePhase.AWAIT_HEIGHT)
                    wallHeightPreview
                else null
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
                val elapsed     = ((nowMs - scanStartTime) / 1000).toInt()
                val floorLocked = floorAnchor.isLocked
                val captureState = perimeterCapture.state
                val ptCount     = perimeterCapture.pointCount
                val lastLen     = perimeterCapture.lastSegmentLength()
                val reticleW    = lastReticleWorld
                val liveDistM: Float? = if (!openingMode && ptCount > 0 && reticleW != null
                    && capturePhaseUi != PerimeterCapture.CapturePhase.AWAIT_HEIGHT) {
                    val pts = perimeterCapture.getPolygon()
                    val lastPt = pts.lastOrNull()
                    if (lastPt != null)
                        sqrt((reticleW[0] - lastPt[0]).pow(2) + (reticleW[2] - lastPt[2]).pow(2))
                    else null
                } else null

                mainHandler.post {
                    reticleView.reticleState = when {
                        trackingState != TrackingState.TRACKING -> ReticleView.State.IDLE
                        !floorLocked                            -> ReticleView.State.IDLE
                        else                                    -> ReticleView.State.TRACKING
                    }
                    val (hl, sub) = guidanceText(trackingState, floorLocked, captureState, capturePhaseUi, ptCount)
                    guidancePillBg.setColor(
                        if (captureState == PerimeterCapture.State.CLOSED)
                            Color.argb(210, 5, 28, 18) else Color.argb(195, 4, 5, 18)
                    )
                    guidanceHeadline.text = hl
                    guidanceSubtext.text  = sub
                    sideBadge.text = when {
                        capturePhaseUi == PerimeterCapture.CapturePhase.AWAIT_HEIGHT ->
                            "Altezza: ${"%.2f".format(wallHeightPreview)}m · usa + / − poi conferma"
                        ptCount == 0 && !floorLocked -> "Attendi floor lock…"
                        ptCount == 0                 -> "Pronto · Vai in un angolo"
                        capturePhaseUi == PerimeterCapture.CapturePhase.AWAIT_SECOND_FLOOR ->
                            "Altezza: ${"%.2f".format(perimeterCapture.capturedHeight ?: wallHeightPreview)}m · ora il 2° angolo"
                        lastLen != null ->
                            "$ptCount lat${if (ptCount == 1) "o" else "i"} · ultimo: ${"%.2f".format(lastLen)}m"
                        else -> "$ptCount punt${if (ptCount == 1) "o" else "i"}"
                    }
                    timerText.text = "${elapsed}s"
                    // Quality bar — BASSA/MEDIA/ALTA
                    val quality = when {
                        !floorLocked -> "BASSA"
                        ptCount < 3  -> "MEDIA"
                        else         -> "ALTA"
                    }
                    val qualActive   = Color.argb(255, 20, 215, 255)
                    val qualInactive = Color.argb(90, 130, 140, 200)
                    qualityBassa.setTextColor(if (quality == "BASSA") qualActive else qualInactive)
                    qualityMedia.setTextColor(if (quality == "MEDIA") qualActive else qualInactive)
                    qualityAlta.setTextColor( if (quality == "ALTA")  qualActive else qualInactive)
                    // Floating distance label
                    distanceLabel.text = when {
                        capturePhaseUi == PerimeterCapture.CapturePhase.AWAIT_HEIGHT && liveHmUi != null ->
                            "${"%.2f".format(liveHmUi)} m"
                        liveDistM != null && liveDistM > 0.08f ->
                            "${"%.2f".format(liveDistM)} m"
                        else -> ""
                    }
                    updateActionBtn(captureState, perimeterCapture.canClose)
                    pcDebugOverlay.text = buildString {
                        append("GPC | pts:$ptCount state:${captureState.name}\n")
                        append("floor:${if (floorLocked) "LOCK" else "est"} Y=${"%.2f".format(lastFloorY)}\n")
                        if (reticleW != null)
                            append("ret X=${"%.2f".format(reticleW[0])} Z=${"%.2f".format(reticleW[2])}")
                        if (openingMode) append(" | openingMode hovW:${hoveredWallId ?: "—"}")
                    }
                }
            }

            if (nowMs - lastDebugMs >= 1000L) {
                lastDebugMs = nowMs
                Log.d("SpatialScan", "GPC | pts=${perimeterCapture.pointCount} " +
                    "state=${perimeterCapture.state} floorY=${"%.2f".format(lastFloorY)} " +
                    "openingMode=$openingMode hovW=$hoveredWallId selW=$selectedWallId")
            }
        } catch (_: Exception) { }
    }

    // ── Wall picking (raycast XZ proximity) ───────────────────────────────────

    /**
     * Trova il muro più vicino al punto reticle [X, Z] nel piano pavimento.
     * Ritorna l'id del muro se la distanza è < 0.5m, null altrimenti.
     */
    private fun pickWall(reticle: FloatArray, rm: RoomModel): String? {
        val rx = reticle[0]; val rz = reticle[2]
        var bestId: String? = null
        var bestDist = 0.5f  // threshold: 50cm

        for (wall in rm.walls) {
            val dist = pointToSegmentDist(rx, rz,
                wall.start[0], wall.start[2], wall.end[0], wall.end[2])
            if (dist < bestDist) {
                bestDist = dist
                bestId   = wall.id
            }
        }
        return bestId
    }

    /** Distanza punto (px,pz) → segmento (ax,az)-(bx,bz) nel piano XZ. */
    private fun pointToSegmentDist(
        px: Float, pz: Float, ax: Float, az: Float, bx: Float, bz: Float
    ): Float {
        val dx = bx - ax; val dz = bz - az
        val lenSq = dx * dx + dz * dz
        if (lenSq < 1e-10f) return sqrt((px - ax).pow(2) + (pz - az).pow(2))
        val t = ((px - ax) * dx + (pz - az) * dz) / lenSq
        val tc = t.coerceIn(0f, 1f)
        val cx = ax + tc * dx; val cz = az + tc * dz
        return sqrt((px - cx).pow(2) + (pz - cz).pow(2))
    }

    // ── Opening placement logic ───────────────────────────────────────────────

    private fun buildRoomModel() {
        val poly = frozenPolygon ?: perimeterCapture.getPolygon()
        val rectified = RoomRectifier.rectify(poly).polygon
        roomModel = RoomModel.fromPolygon(rectified, wallHeightPreview)
    }

    private fun enterOpeningMode() {
        if (roomModel == null) buildRoomModel()
        openingMode = true
        selectedWallId = null
        editingOpening = null
        mainHandler.post {
            confirmCornerBtn.visibility      = android.view.View.GONE
            heightControlRow.visibility      = android.view.View.GONE
            openingPhaseBar.visibility       = android.view.View.VISIBLE
            openingTypeRow.visibility        = android.view.View.GONE
            openingEditPanel.visibility      = android.view.View.GONE
            actionBtn.text                   = "Esporta"
            actionBtn.setBackgroundColor(Color.argb(255, 18, 90, 180))
            actionBtn.isEnabled              = true
            undoBtn.visibility               = android.view.View.GONE
            guidanceHeadline.text            = "Aggiungi aperture"
            guidanceSubtext.text             = "Punta un muro e toccalo per selezionarlo"
            // Offri il riuso dell'apertura collegata (se spec presente e non ancora offerta)
            val spec = linkedOpeningSpec
            if (spec != null && !linkedSpecAccepted) showLinkedSpecBanner(spec)
        }
    }

    private fun handleOpeningTap() {
        val wid = hoveredWallId ?: return
        selectedWallId = wid
        mainHandler.post {
            openingTypeRow.visibility = android.view.View.VISIBLE
            openingEditPanel.visibility = android.view.View.GONE
            guidanceSubtext.text = "Scegli il tipo di apertura"
        }
    }

    private fun spawnOpening(kind: OpeningKind) {
        val wid  = selectedWallId ?: return
        val rm   = roomModel      ?: return
        val wall = rm.walls.find { it.id == wid } ?: return

        // Riusa dimensioni dall'apertura collegata (se accettata e non ancora piazzata)
        val spec   = linkedOpeningSpec?.takeIf { linkedSpecAccepted && !linkedSpecPlaced }
        val width  = spec?.width  ?: kind.defaultWidth
        val height = spec?.height ?: kind.defaultHeight
        val bottom = spec?.bottom ?: kind.defaultBottom
        if (spec != null) linkedSpecPlaced = true

        val defaultOffset = ((wall.length - width) / 2f).coerceAtLeast(0.10f)
        val opening = OpeningModel(
            id              = "op_${System.currentTimeMillis()}",
            wallId          = wid,
            kind            = kind,
            offsetAlongWall = defaultOffset,
            width           = width,
            bottom          = bottom,
            height          = height
        )
        wall.clampOpening(opening)
        wall.openings.add(opening)
        editingOpening = opening

        mainHandler.post { showOpeningEditPanel(opening) }
    }

    private fun showOpeningEditPanel(o: OpeningModel) {
        openingEditTitle.text       = o.kind.label
        openingBottomRow.visibility = if (o.kind == OpeningKind.WINDOW)
            android.view.View.VISIBLE else android.view.View.GONE
        openingEditPanel.visibility = android.view.View.VISIBLE
        openingTypeRow.visibility   = android.view.View.GONE
        refreshOpeningValues(o)
        guidanceSubtext.text = "Regola la posizione e le dimensioni"
    }

    private fun refreshOpeningValues(o: OpeningModel) {
        // Mostra valori misurati, non coordinate grezze
        openingPosText.text    = "${"%.2f".format(o.offsetAlongWall)}m"
        openingWidthText.text  = "${"%.2f".format(o.width)}m"
        openingHeightText.text = "${"%.2f".format(o.height)}m"
        openingBottomText.text = "${"%.2f".format(o.bottom)}m"
    }

    private fun nudgeOpening(delta: Float) {
        val o    = editingOpening ?: return
        val rm   = roomModel      ?: return
        val wall = rm.walls.find { it.id == o.wallId } ?: return
        o.offsetAlongWall += delta
        wall.clampOpening(o)
        mainHandler.post { refreshOpeningValues(o) }
    }

    private fun adjustOpening(dW: Float = 0f, dH: Float = 0f, dB: Float = 0f) {
        val o    = editingOpening ?: return
        val rm   = roomModel      ?: return
        val wall = rm.walls.find { it.id == o.wallId } ?: return
        if (dW != 0f) {
            // Resize simmetrico: il centro dell'apertura rimane fisso.
            // L'offset si aggiusta di metà del delta, poi clampOpening
            // lo ancora ai bordi della parete se necessario.
            val center   = o.offsetAlongWall + o.width / 2f
            val newWidth = (o.width + dW).coerceIn(0.30f, wall.length - 0.20f)
            o.width           = newWidth
            o.offsetAlongWall = center - newWidth / 2f
        }
        if (dH != 0f) o.height = (o.height + dH).coerceIn(0.30f, wallHeightPreview)
        if (dB != 0f) o.bottom = (o.bottom + dB).coerceIn(0.00f, wallHeightPreview - o.height)
        wall.clampOpening(o)
        mainHandler.post { refreshOpeningValues(o) }
    }

    private fun confirmOpening() {
        val confirmed  = editingOpening   // cattura prima di azzerare
        editingOpening = null
        selectedWallId = null
        mainHandler.post {
            openingEditPanel.visibility = android.view.View.GONE
            openingTypeRow.visibility   = android.view.View.GONE
            guidanceSubtext.text = "Punta un altro muro o premi Esporta"
            // Mostra dialog classificazione solo per porte (non finestre)
            if (confirmed != null && confirmed.kind != OpeningKind.WINDOW) {
                showConnectionDialog(confirmed)
            }
        }
    }

    /** Dialog classificazione: esterna / interna (con o senza collegamento). */
    private fun showConnectionDialog(opening: OpeningModel) {
        val rooms   = RoomHistoryManager.loadAll(this)
        val options = mutableListOf("Esterna / non collegare", "Interna — collegherò dopo")
        if (rooms.isNotEmpty()) options.add("Interna — collega ora")

        var selected = 0
        AlertDialog.Builder(this)
            .setTitle("Tipo apertura")
            .setSingleChoiceItems(options.toTypedArray(), 0) { _, which -> selected = which }
            .setPositiveButton("OK") { _, _ ->
                when (selected) {
                    0 -> openingMetadataMap[opening.id] = OpeningMetadata(
                            openingId = opening.id, wallId = opening.wallId,
                            isInternal = false, linkedRoomId = null, connectionLabel = null)
                    1 -> openingMetadataMap[opening.id] = OpeningMetadata(
                            openingId = opening.id, wallId = opening.wallId,
                            isInternal = true,  linkedRoomId = null, connectionLabel = null)
                    2 -> showRoomPickerDialog(opening, rooms)
                }
            }
            .setNegativeButton("Salta") { _, _ -> }
            .setCancelable(false)
            .show()
    }

    /** Secondo step: scelta stanza da collegare (solo se history non vuota). */
    private fun showRoomPickerDialog(opening: OpeningModel, rooms: List<RoomRecord>) {
        val names = rooms.map { it.name }.toTypedArray()
        var selectedIdx = 0
        AlertDialog.Builder(this)
            .setTitle("Collega a quale stanza?")
            .setSingleChoiceItems(names, 0) { _, which -> selectedIdx = which }
            .setPositiveButton("Collega") { _, _ ->
                openingMetadataMap[opening.id] = OpeningMetadata(
                    openingId       = opening.id,
                    wallId          = opening.wallId,
                    isInternal      = true,
                    linkedRoomId    = rooms[selectedIdx].id,
                    connectionLabel = rooms[selectedIdx].name
                )
            }
            .setNegativeButton("Salta") { _, _ ->
                // Rimane interna ma senza collegamento risolto
                openingMetadataMap[opening.id] = OpeningMetadata(
                    openingId = opening.id, wallId = opening.wallId,
                    isInternal = true, linkedRoomId = null, connectionLabel = null
                )
            }
            .setCancelable(false)
            .show()
    }

    private fun deleteEditingOpening() {
        val o  = editingOpening ?: return
        val rm = roomModel      ?: return
        rm.walls.find { it.id == o.wallId }?.openings?.remove(o)
        editingOpening = null
        selectedWallId = null
        mainHandler.post {
            openingEditPanel.visibility = android.view.View.GONE
            openingTypeRow.visibility   = android.view.View.GONE
            guidanceSubtext.text = "Apertura eliminata · seleziona un muro"
        }
    }

    // ── screenToWorld ─────────────────────────────────────────────────────────

    /**
     * Raycast centro schermo → coordinate world.
     *
     * @param forceFloor true (default): forza Y = lastFloorY su qualsiasi hit
     *                   (piano orizzontale, piano verticale, feature point su parete)
     *                   quando il floor è locked. Permette di puntare le pareti
     *                   invece del pavimento coperto da oggetti.
     *                   false: usa Y reale dal hit — per cattura altezza (AWAIT_HEIGHT).
     */
    private fun screenToWorld(
        frame: Frame, camera: Camera, px: Float, py: Float,
        forceFloor: Boolean = true
    ): FloatArray? {
        val hits = frame.hitTest(px, py)
        for (hit in hits) {
            val trackable = hit.trackable
            val pose = hit.hitPose
            when {
                trackable is Plane &&
                    trackable.trackingState == TrackingState.TRACKING &&
                    trackable.subsumedBy == null -> {
                    val y = if (forceFloor && floorAnchor.isLocked) lastFloorY else pose.ty()
                    return floatArrayOf(pose.tx(), y, pose.tz())
                }
                trackable is com.google.ar.core.Point &&
                    trackable.orientationMode ==
                        com.google.ar.core.Point.OrientationMode.ESTIMATED_SURFACE_NORMAL -> {
                    val y = if (forceFloor && floorAnchor.isLocked) lastFloorY else pose.ty()
                    return floatArrayOf(pose.tx(), y, pose.tz())
                }
            }
        }
        if (floorAnchor.isLocked && forceFloor) {
            val camPos = camera.pose.translation
            val camFwd = camera.pose.rotateVector(floatArrayOf(0f, 0f, -1f))
            val dy = camFwd[1]
            if (dy < -0.01f) {
                val t = (lastFloorY - camPos[1]) / dy
                if (t in 0f..15f)
                    return floatArrayOf(camPos[0] + t * camFwd[0], lastFloorY, camPos[2] + t * camFwd[2])
            }
        }
        // Nessun hit valido:
        // - forceFloor=true  → null (sticky): il caller usa l'ultimo valore stabile.
        // - forceFloor=false → fallback 2m-ahead: per l'altezza non si usa sticky,
        //   servono stime continue anche quando ARCore non trova pareti/soffitti.
        if (!forceFloor) {
            val ahead = camera.pose.transformPoint(floatArrayOf(0f, 0f, -2f))
            return floatArrayOf(ahead[0], ahead[1], ahead[2])
        }
        return null
    }

    /**
     * FLOOR mode geometrico: proietta il raggio camera verso il piano Y = lastFloorY.
     * Usato dopo il floor lock in sostituzione di frame.hitTest() — nessuna dipendenza
     * da ARCore plane detection / feature point tracking per il posizionamento XZ.
     * Stessa logica del fallback in screenToWorld(), promossa a percorso primario.
     * @return [X, lastFloorY, Z] se la camera punta verso il basso, null altrimenti (sticky).
     */
    private fun screenToWorldFloorPlane(camera: Camera): FloatArray? {
        if (!floorAnchor.isLocked) return null
        val camPos = camera.pose.translation
        val camFwd = camera.pose.rotateVector(floatArrayOf(0f, 0f, -1f))
        val dy     = camFwd[1]
        if (dy > -0.01f) return null   // camera non punta verso il pavimento → sticky
        val t = (lastFloorY - camPos[1]) / dy
        if (t < 0.05f || t > 15f) return null
        return floatArrayOf(camPos[0] + t * camFwd[0], lastFloorY, camPos[2] + t * camFwd[2])
    }

    /**
     * TOP mode: proietta il raggio camera verso il piano Y = lastFloorY + wallHeightPreview.
     * Non usa ARCore hit test — geometria pura, funziona anche con zero feature points.
     *
     * Quando l'utente punta la camera verso l'alto (bordo soffitto/parete),
     * restituisce [X, lastFloorY, Z]: la proiezione verticale al pavimento.
     * XZ identici al floor mode — stessa planimetria, sorgente diversa.
     *
     * @return [X, lastFloorY, Z] se la camera punta verso il piano superiore, null altrimenti.
     */
    private fun screenToWorldTopPlane(camera: Camera): FloatArray? {
        if (!floorAnchor.isLocked) return null
        val wallTopY = lastFloorY + wallHeightPreview
        val camPos   = camera.pose.translation
        val camFwd   = camera.pose.rotateVector(floatArrayOf(0f, 0f, -1f))
        val dy       = camFwd[1]
        // Camera deve puntare verso l'alto (dy > 0) per intersecare il piano superiore
        if (dy < 0.01f) return null
        val t = (wallTopY - camPos[1]) / dy
        if (t < 0.05f || t > 15f) return null
        return floatArrayOf(camPos[0] + t * camFwd[0], lastFloorY, camPos[2] + t * camFwd[2])
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun guidanceText(
        tracking:    TrackingState,
        floorLocked: Boolean,
        state:       PerimeterCapture.State,
        phase:       PerimeterCapture.CapturePhase,
        ptCount:     Int
    ): Pair<String, String> = when {
        openingMode ->
            "Aperture" to "Punta un muro e toccalo per selezionarlo"
        tracking != TrackingState.TRACKING ->
            "Tracking perso" to "Muoviti lentamente e guarda le superfici"
        !floorLocked ->
            "Inizializzazione…" to "Punta verso il pavimento"
        state == PerimeterCapture.State.CLOSED ->
            "Stanza chiusa" to "Regola altezza · poi aggiungi aperture o esporta"
        phase == PerimeterCapture.CapturePhase.AWAIT_FIRST_FLOOR ->
            "Posizionati in un angolo" to "Punta alla BASE del muro · TAP"
        phase == PerimeterCapture.CapturePhase.AWAIT_HEIGHT ->
            "Imposta l'altezza delle pareti" to "Usa + / − per regolare · poi premi ALTEZZA QUI"
        phase == PerimeterCapture.CapturePhase.AWAIT_SECOND_FLOOR && reticleTopMode ->
            "Prima parete! Cammina lungo il muro" to "Cursore ALTO attivo · punta l'angolo superiore · TAP"
        phase == PerimeterCapture.CapturePhase.AWAIT_SECOND_FLOOR ->
            "Prima parete! Cammina lungo il muro" to "Punta alla BASE dell'angolo successivo · TAP"
        perimeterCapture.canClose && reticleTopMode ->
            "Aggiungi angoli o chiudi" to "Cursore ALTO · TAP per angolo · Chiudi per finire"
        perimeterCapture.canClose ->
            "Aggiungi angoli o chiudi" to "TAP per altro angolo · ↩ per correggere · 'Chiudi' per finire"
        reticleTopMode ->
            "Cammina verso il prossimo angolo" to "Cursore ALTO attivo · punta l'angolo superiore · TAP"
        else ->
            "Cammina verso il prossimo angolo" to "Punta alla giunzione muro-pavimento · TAP"
    }

    private fun updateActionBtn(state: PerimeterCapture.State, canClose: Boolean) {
        if (openingMode) return
        val isClosed = state == PerimeterCapture.State.CLOSED
        val phase    = perimeterCapture.capturePhase

        // confirmCornerBtn: bottone primario CONFERMA (destra)
        confirmCornerBtn.visibility = if (!isClosed && floorAnchor.isLocked)
            android.view.View.VISIBLE else android.view.View.GONE
        confirmCornerBtn.text = when (phase) {
            PerimeterCapture.CapturePhase.AWAIT_HEIGHT -> "ALTEZZA QUI"
            else -> "CONFERMA ANGOLO"
        }

        // heightBanner: visibile solo in AWAIT_HEIGHT
        heightBanner.visibility = if (phase == PerimeterCapture.CapturePhase.AWAIT_HEIGHT && !isClosed)
            android.view.View.VISIBLE else android.view.View.GONE

        // actionBtn: secondario, visibile solo quando canClose o isClosed
        // Lo stepper è visibile anche durante AWAIT_HEIGHT: l'utente imposta l'altezza
        // PRIMA di confermare con "ALTEZZA QUI", non dopo la chiusura del poligono.
        val showStepper = isClosed || phase == PerimeterCapture.CapturePhase.AWAIT_HEIGHT
        when {
            isClosed -> {
                actionBtn.text = "Aggiungi Aperture"
                actionBtn.visibility = android.view.View.VISIBLE
            }
            canClose -> {
                actionBtn.text = "Chiudi Poligono"
                actionBtn.visibility = android.view.View.VISIBLE
            }
            else -> {
                actionBtn.visibility = android.view.View.GONE
            }
        }
        if (showStepper) {
            heightControlRow.visibility = android.view.View.VISIBLE
            heightValueText.text = "${"%.2f".format(wallHeightPreview)}m"
        } else {
            heightControlRow.visibility = android.view.View.GONE
        }

    }

    private fun updateCaptureUI() {
        val state    = perimeterCapture.state
        val canClose = perimeterCapture.canClose
        val canUndo  = perimeterCapture.canUndo
        updateActionBtn(state, canClose)
        undoBtn.isEnabled = canUndo && state != PerimeterCapture.State.CLOSED
        // cancelBtn: "INDIETRO" = undo se ha punti, "ANNULLA" = esci se nessun punto
        cancelBtn.text = if (canUndo && state != PerimeterCapture.State.CLOSED) "INDIETRO" else "ANNULLA"
        val ptCount = perimeterCapture.pointCount
        val lastLen = perimeterCapture.lastSegmentLength()
        sideBadge.text = if (lastLen != null)
            "$ptCount lat${if (ptCount == 1) "o" else "i"} · ultimo: ${"%.2f".format(lastLen)}m"
        else "$ptCount punt${if (ptCount == 1) "o" else "i"}"
    }

    // ── Stop / cancel ─────────────────────────────────────────────────────────

    fun requestStop() { glSurfaceView.queueEvent { doStopScan() } }

    fun cancelScanAndFinish() {
        // Chiude la session SUL GL thread per evitare SIGSEGV in ArSession_update:
        // se chiamato dal main thread mentre onDrawFrame è in esecuzione, la close()
        // può causare null-dereference nel codice nativo ARCore.
        glSurfaceView.queueEvent {
            session?.pause()
            session?.close()
            session = null
        }
        setResult(RESULT_CANCELED)
        finish()
    }

    fun doStopScan() {
        val result = buildResult()
        session?.pause(); session?.close(); session = null
        mainHandler.post {
            showNamingDialogAndSave(result) { savedRecord ->
                onScanComplete?.invoke(result)
                val cb = onScanResult
                if (cb != null) { cb(result); onScanResult = null }
                else pendingResult = result
                offerComposer(savedRecord) {
                    showContinueScanDialog(result, savedRecord) {
                        setResult(RESULT_OK); finish()
                    }
                }
            }
        }
    }

    private fun showNamingDialogAndSave(result: JSObject, onDone: (RoomRecord?) -> Unit) {
        // Se la scansione non è valida, salta il salvataggio e prosegui
        if (!result.optBoolean("success", false)) { onDone(null); return }

        val input = EditText(this).apply {
            hint = "es. salotto, cucina, corridoio"
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        AlertDialog.Builder(this)
            .setTitle("Nome stanza")
            .setView(input)
            .setPositiveButton("Salva") { _, _ ->
                val saved = try {
                    val name = input.text.toString().trim().ifEmpty { "Stanza" }
                    RoomHistoryManager.save(this, result, name)
                } catch (e: Exception) {
                    Log.e("ScanningActivity", "room save failed: ${e.message}", e)
                    null
                }
                onDone(saved)
            }
            .setNegativeButton("Salta") { _, _ -> onDone(null) }
            .setCancelable(false)
            .show()
    }

    // ── Multi-room workflow ───────────────────────────────────────────────────

    /** Banner offerta riuso apertura — mostrato all'ingresso in opening mode. */
    private fun showLinkedSpecBanner(spec: LinkedOpeningSpec) {
        val label = "${spec.kind.label} ${String.format("%.2f", spec.width)}m × ${String.format("%.2f", spec.height)}m"
        AlertDialog.Builder(this)
            .setTitle("Apertura collegata disponibile")
            .setMessage("Dalla stanza \"${spec.sourceRoomName}\" c'è una $label.\n\nVuoi usarne le misure per la prima apertura di questo ambiente?")
            .setPositiveButton("Sì, usa queste misure") { _, _ ->
                linkedSpecAccepted = true
                guidanceSubtext.text = "Seleziona un muro — misure ${spec.kind.label} pronte"
            }
            .setNegativeButton("No, uso misure standard") { _, _ ->
                linkedOpeningSpec = null  // ignora la spec
            }
            .setCancelable(false)
            .show()
    }

    /** Offre la composizione planimetrica se questa è una scan collegata (linkedOpeningSpec != null). */
    private fun offerComposer(savedRecord: RoomRecord?, onDone: () -> Unit) {
        val spec = linkedOpeningSpec
        if (spec == null || spec.sourceRoomId.isEmpty() || savedRecord == null) { onDone(); return }
        AlertDialog.Builder(this)
            .setTitle("Planimetria multi-stanza")
            .setMessage("Vuoi comporre la planimetria con \"${spec.sourceRoomName}\"?")
            .setPositiveButton("Sì, componi") { _, _ ->
                val next = Intent(this, RoomComposerActivity::class.java).apply {
                    putExtra("roomAId",   spec.sourceRoomId)
                    putExtra("roomBId",   savedRecord.id)
                    putExtra("linkKind",  spec.kind.name)
                    putExtra("linkWidth", spec.width)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(next)
                onDone()
            }
            .setNegativeButton("Non ora") { _, _ -> onDone() }
            .setCancelable(false)
            .show()
    }

    /** Step 1 — "Vuoi scansionare un altro ambiente?" */
    private fun showContinueScanDialog(result: JSObject, savedRecord: RoomRecord?, onFinish: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Scan completata")
            .setMessage("Vuoi scansionare un altro ambiente?")
            .setPositiveButton("Sì") { _, _ -> showConnectionChoiceDialog(result, savedRecord, onFinish) }
            .setNegativeButton("No")  { _, _ -> onFinish() }
            .setCancelable(false)
            .show()
    }

    /** Step 2 — "L'ambiente si collega tramite un'apertura?" */
    private fun showConnectionChoiceDialog(result: JSObject, savedRecord: RoomRecord?, onFinish: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Collegamento")
            .setMessage("Il nuovo ambiente si collega tramite un'apertura già definita?")
            .setPositiveButton("Sì") { _, _ -> showOpeningPickerForRestart(result, savedRecord, onFinish) }
            .setNegativeButton("No") { _, _ -> restartWithSpec(null, onFinish) }
            .setCancelable(false)
            .show()
    }

    /** Step 3 — Selezione apertura da riusare (solo DOOR / FRENCH_DOOR). */
    private fun showOpeningPickerForRestart(result: JSObject, savedRecord: RoomRecord?, onFinish: () -> Unit) {
        data class Entry(val label: String, val spec: LinkedOpeningSpec)

        val walls = result.getJSONArray("walls")
        val entries = mutableListOf<Entry>()
        if (walls != null) {
            for (i in 0 until walls.length()) {
                val wall = walls.optJSONObject(i) ?: continue
                val ops  = wall.optJSONArray("openings") ?: continue
                for (j in 0 until ops.length()) {
                    val op      = ops.optJSONObject(j) ?: continue
                    val kindStr = op.optString("kind")
                    if (kindStr != "DOOR" && kindStr != "FRENCH_DOOR") continue
                    val kind   = runCatching { OpeningKind.valueOf(kindStr) }.getOrNull() ?: continue
                    val w      = op.optDouble("width",  kind.defaultWidth.toDouble()).toFloat()
                    val h      = op.optDouble("height", kind.defaultHeight.toDouble()).toFloat()
                    val b      = op.optDouble("bottom", kind.defaultBottom.toDouble()).toFloat()
                    val label  = "${kind.label} ${String.format("%.2f", w)}m × ${String.format("%.2f", h)}m (muro $i)"
                    entries.add(Entry(label, LinkedOpeningSpec(
                        sourceRoomId   = savedRecord?.id   ?: "",
                        sourceRoomName = savedRecord?.name ?: "Stanza precedente",
                        kind = kind, width = w, height = h, bottom = b
                    )))
                }
            }
        }

        if (entries.isEmpty()) { restartWithSpec(null, onFinish); return }

        var selected = 0
        AlertDialog.Builder(this)
            .setTitle("Seleziona apertura di collegamento")
            .setSingleChoiceItems(entries.map { it.label }.toTypedArray(), 0) { _, which -> selected = which }
            .setPositiveButton("Usa questa") { _, _ -> restartWithSpec(entries[selected].spec, onFinish) }
            .setNegativeButton("Senza collegamento") { _, _ -> restartWithSpec(null, onFinish) }
            .setCancelable(false)
            .show()
    }

    /** Riavvia ScanningActivity (con o senza spec). onFinish() chiude l'Activity corrente. */
    private fun restartWithSpec(spec: LinkedOpeningSpec?, onFinish: () -> Unit) {
        val enableDepth = intent.getBooleanExtra("enableDepth", true)
        val next = Intent(this, ScanningActivity::class.java).apply {
            putExtra("enableDepth", enableDepth)
            spec?.putInto(this)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(next)
        onFinish()
    }

    // ── buildResult ───────────────────────────────────────────────────────────

    private fun buildResult(): JSObject {
        val elapsed = (System.currentTimeMillis() - scanStartTime) / 1000.0
        val version = arcoreVersion()
        val polygon = perimeterCapture.getPolygon()
        val finalPolygon = frozenPolygon?.takeIf { it.size == polygon.size } ?: polygon
        val ptCount = polygon.size

        if (finalPolygon == null || finalPolygon.size < 3) {
            return JSObject().apply {
                put("success", false); put("error", "INSUFFICIENT_POINTS")
                put("walls", JSArray()); put("floor", JSObject.NULL)
                put("roomDimensions", zeroDims())
                put("scanMetadata", meta(elapsed, ptCount, 0, version))
            }
        }

        val exportPolygon = RoomRectifier.rectify(finalPolygon).polygon
        val rm = roomModel ?: RoomModel.fromPolygon(exportPolygon, wallHeightPreview)
        val exportData    = RoomExportData.fromRoomModel(rm)
        val roomDim       = exportData.dimensions
        val floorPlanPath = FloorPlanExporter.export(exportData, cacheDir)
        val glbPath       = GlbExporter.export(exportData, cacheDir)

        val wallsArr = JSArray().also { arr ->
            rm.walls.forEach { wall ->
                arr.put(wallModelToObj(wall))
            }
        }

        val floorVerts = JSArray().also { a ->
            exportPolygon.forEach { pt ->
                a.put(JSObject().apply {
                    put("x", pt[0].toDouble()); put("y", 0.0); put("z", pt[2].toDouble())
                })
            }
        }
        val floorObj = JSObject().apply {
            put("vertices", floorVerts); put("area", roomDim.area)
        }

        return JSObject().apply {
            put("success", true)
            put("walls",   wallsArr)
            put("floor",   floorObj)
            put("roomDimensions", JSObject().apply {
                put("width",     roomDim.width);  put("length",    roomDim.length)
                put("height",    roomDim.height); put("area",      roomDim.area)
                put("perimeter", roomDim.perimeter)
            })
            put("scanMetadata", meta(elapsed, ptCount, rm.walls.size, version))
            if (floorPlanPath != null) put("floorPlanPath", floorPlanPath)
            if (glbPath       != null) put("glbPath",       glbPath)
        }
    }

    // ── Serializzazione ───────────────────────────────────────────────────────

    private fun wallModelToObj(w: WallModel): JSObject = JSObject().apply {
        put("id", w.id)
        put("startPoint", JSObject().apply {
            put("x", w.start[0].toDouble()); put("y", 0.0); put("z", w.start[2].toDouble())
        })
        put("endPoint", JSObject().apply {
            put("x", w.end[0].toDouble()); put("y", 0.0); put("z", w.end[2].toDouble())
        })
        put("length",    w.length.toDouble())
        put("height",    w.height.toDouble())
        put("thickness", w.thickness.toDouble())
        val openingsArr = JSArray().also { arr ->
            w.openings.forEach { o ->
                arr.put(JSObject().apply {
                    put("id",             o.id)
                    put("wallId",         o.wallId)
                    put("kind",           o.kind.name)
                    put("offsetAlongWall", o.offsetAlongWall.toDouble())
                    put("width",          o.width.toDouble())
                    put("bottom",         o.bottom.toDouble())
                    put("height",         o.height.toDouble())
                    // Metadati collegamento (presenti solo se classificati dall'utente)
                    openingMetadataMap[o.id]?.let { meta ->
                        put("isInternal", meta.isInternal)
                        if (meta.linkedRoomId    != null) put("linkedRoomId",    meta.linkedRoomId)
                        if (meta.connectionLabel != null) put("connectionLabel", meta.connectionLabel)
                    }
                })
            }
        }
        put("openings", openingsArr)
    }

    private fun buildWallsFromPolygon(
        polygon: List<FloatArray>, @Suppress("UNUSED_PARAMETER") floorY: Float, wallHeight: Double
    ): List<Wall> {
        val walls = mutableListOf<Wall>()
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]
            val dx = (b[0] - a[0]).toDouble(); val dz = (b[2] - a[2]).toDouble()
            val len = sqrt(dx.pow(2) + dz.pow(2))
            val nx = if (len > 1e-6) -dz / len else 0.0
            val nz = if (len > 1e-6)  dx / len else 0.0
            walls.add(Wall(
                id         = "w$i",
                startPoint = Point3D(a[0].toDouble(), 0.0, a[2].toDouble()),
                endPoint   = Point3D(b[0].toDouble(), 0.0, b[2].toDouble()),
                length     = len, height = wallHeight,
                normal     = Vector3D(nx, 0.0, nz), confidence = 1.0
            ))
        }
        return walls
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
        put("width", 0.0); put("length", 0.0); put("height", 0.0)
        put("area", 0.0); put("perimeter", 0.0)
    }

    private fun meta(elapsed: Double, pts: Int, walls: Int, ver: String) = JSObject().apply {
        put("scanDurationSeconds", elapsed); put("pointsPlaced", pts)
        put("wallsInResult", walls);         put("arcoreVersion", ver)
    }

    private fun arcoreVersion() = try {
        packageManager.getPackageInfo("com.google.ar.core", 0).versionName ?: "unknown"
    } catch (_: Exception) { "unknown" }

    private fun dp(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()
}
