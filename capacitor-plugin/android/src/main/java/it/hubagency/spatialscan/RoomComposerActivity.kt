package it.hubagency.spatialscan

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import kotlin.math.*

/**
 * Editor leggero per la composizione planimetrica di due stanze collegate.
 *
 * Room A è fissa (stanza sorgente). Room B è trasformabile con offset + rotazione.
 * L'allineamento iniziale è calcolato automaticamente dall'apertura di collegamento.
 *
 * Controlli: sposta ±5cm (4 direzioni), ruota ±1°/±5°, snap 90°, conferma/annulla.
 */
class RoomComposerActivity : Activity() {

    private lateinit var composerView: RoomComposerView
    private var roomAId = ""; private var roomBId = ""
    private var linkKind = ""; private var linkWidth = 0f

    // Trasformazione corrente Room B
    private var offsetX    = 0f
    private var offsetZ    = 0f
    private var rotDeg     = 0f   // in gradi per semplicità dei calcoli UI

    // Geometrie caricate
    private var polyA: List<Pair<Float,Float>> = emptyList()
    private var polyB: List<Pair<Float,Float>> = emptyList()
    private var linkCenterA: Pair<Float,Float>? = null
    private var linkCenterB: Pair<Float,Float>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roomAId    = intent.getStringExtra("roomAId")    ?: ""
        roomBId    = intent.getStringExtra("roomBId")    ?: ""
        linkKind   = intent.getStringExtra("linkKind")   ?: ""
        linkWidth  = intent.getFloatExtra("linkWidth", 0f)

        if (roomAId.isEmpty() || roomBId.isEmpty()) {
            Log.e("Composer", "missing roomAId or roomBId"); finish(); return
        }

        val jsonA = RoomHistoryManager.loadRoomData(this, roomAId)
        val jsonB = RoomHistoryManager.loadRoomData(this, roomBId)
        if (jsonA == null || jsonB == null) {
            AlertDialog.Builder(this)
                .setTitle("Dati non disponibili")
                .setMessage("Non è stato possibile caricare i dati geometrici delle stanze.")
                .setPositiveButton("OK") { _, _ -> finish() }
                .setCancelable(false).show()
            return
        }

        polyA = parsePolygon(jsonA)
        polyB = parsePolygon(jsonB)

        val (wallA, openA) = findLinkOpening(jsonA, linkKind, linkWidth)
        val (wallB, openB) = findLinkOpening(jsonB, linkKind, linkWidth)

        if (wallA != null && openA != null) linkCenterA = openingCenter(wallA, openA)
        if (wallB != null && openB != null) linkCenterB = openingCenter(wallB, openB)

        // Calcola allineamento iniziale
        if (wallA != null && openA != null && wallB != null && openB != null) {
            val (tx, tz, tRad) = computeInitialAlignment(wallA, openA, wallB, openB)
            offsetX = tx; offsetZ = tz; rotDeg = Math.toDegrees(tRad.toDouble()).toFloat()
        }

        setContentView(buildLayout())
        syncViewTransform()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { finish() }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout(): android.view.View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        composerView = RoomComposerView(this).apply {
            polygonA    = polyA
            polygonB    = polyB
            linkCenterA = this@RoomComposerActivity.linkCenterA
            linkCenterB = this@RoomComposerActivity.linkCenterB
        }

        // Controls panel (bottom ~260dp)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 12, 14, 22))
            setPadding(dp(12), dp(10), dp(12), dp(14))
        }

        // Title
        panel.addView(TextView(this).apply {
            text = "Composizione planimetrica"
            setTextColor(Color.argb(200, 180, 200, 255))
            setTypeface(null, Typeface.BOLD)
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // Row 1: move arrows
        panel.addView(makeLabel("Sposta Room B"))
        panel.addView(makeRow(
            makeBtn("←")  { move(-0.05f, 0f) },
            makeBtn("→")  { move( 0.05f, 0f) },
            makeBtn("↑")  { move(0f, -0.05f) },
            makeBtn("↓")  { move(0f,  0.05f) }
        ))

        // Row 2: rotate
        panel.addView(makeLabel("Ruota Room B"))
        panel.addView(makeRow(
            makeBtn("↺ 1°") { rotate(-1f) },
            makeBtn("↻ 1°") { rotate( 1f) },
            makeBtn("↺ 5°") { rotate(-5f) },
            makeBtn("↻ 5°") { rotate( 5f) },
            makeBtn("Snap 90°") { snap90() }
        ))

        // Row 3: confirm / cancel
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actionRow.addView(makeBtn("Annulla", Color.argb(255, 80, 30, 30)) { confirmCancel() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), dp(8), dp(4), 0) })
        actionRow.addView(makeBtn("Conferma", Color.argb(255, 18, 90, 50)) { confirmSave() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), dp(8), dp(4), 0) })
        panel.addView(actionRow)

        // Compose: composerView above, panel below
        root.addView(composerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { bottomMargin = dp(252) })
        root.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(252),
            Gravity.BOTTOM
        ))

        return root
    }

    private fun makeLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.argb(140, 160, 180, 220))
        textSize = 10f
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun makeRow(vararg views: android.view.View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            views.forEach { v ->
                addView(v, LinearLayout.LayoutParams(0, dp(40), 1f)
                    .apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
        }
    }

    private fun makeBtn(label: String, bgColor: Int = Color.argb(255, 30, 40, 60), action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11f
            setBackgroundColor(bgColor)
            setOnClickListener { action() }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun move(dx: Float, dz: Float) {
        offsetX += dx; offsetZ += dz; syncViewTransform()
    }

    private fun rotate(deltaDeg: Float) {
        rotDeg += deltaDeg; syncViewTransform()
    }

    private fun snap90() {
        rotDeg = (rotDeg / 90f).roundToInt() * 90f; syncViewTransform()
    }

    private fun syncViewTransform() {
        composerView.offsetX     = offsetX
        composerView.offsetZ     = offsetZ
        composerView.rotationRad = Math.toRadians(rotDeg.toDouble()).toFloat()
    }

    private fun confirmCancel() { finish() }

    private fun confirmSave() {
        val comp = RoomComposition.create(
            roomAId     = roomAId,
            roomBId     = roomBId,
            offsetX     = offsetX,
            offsetZ     = offsetZ,
            rotationRad = Math.toRadians(rotDeg.toDouble()).toFloat()
        )
        RoomComposition.save(this, comp)
        Log.d("Composer", "composition saved id=${comp.id} offset=(${comp.offsetX},${comp.offsetZ}) rot=${rotDeg}°")
        finish()
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    private fun parsePolygon(json: JSONObject): List<Pair<Float,Float>> {
        return try {
            val vertices = json.getJSONObject("floor").getJSONArray("vertices")
            (0 until vertices.length()).map { i ->
                val v = vertices.getJSONObject(i)
                Pair(v.optDouble("x", 0.0).toFloat(), v.optDouble("z", 0.0).toFloat())
            }
        } catch (e: Exception) {
            Log.e("Composer", "parsePolygon failed: ${e.message}"); emptyList()
        }
    }

    data class WallData(val sx: Float, val sz: Float, val ex: Float, val ez: Float)
    data class OpenData(val offset: Float, val width: Float)

    private fun findLinkOpening(json: JSONObject, kind: String, width: Float): Pair<WallData?, OpenData?> {
        return try {
            val walls = json.getJSONArray("walls")
            for (i in 0 until walls.length()) {
                val wall = walls.getJSONObject(i)
                val ops  = wall.optJSONArray("openings") ?: continue
                for (j in 0 until ops.length()) {
                    val op = ops.getJSONObject(j)
                    val k  = op.optString("kind")
                    val w  = op.optDouble("width", 0.0).toFloat()
                    if (k == kind && abs(w - width) < 0.12f) {
                        val sp = wall.getJSONObject("startPoint")
                        val ep = wall.getJSONObject("endPoint")
                        val wd = WallData(
                            sp.optDouble("x", 0.0).toFloat(), sp.optDouble("z", 0.0).toFloat(),
                            ep.optDouble("x", 0.0).toFloat(), ep.optDouble("z", 0.0).toFloat()
                        )
                        val od = OpenData(op.optDouble("offsetAlongWall", 0.0).toFloat(), w)
                        return Pair(wd, od)
                    }
                }
            }
            Pair(null, null)
        } catch (e: Exception) {
            Log.e("Composer", "findLinkOpening failed: ${e.message}"); Pair(null, null)
        }
    }

    private fun openingCenter(wall: WallData, open: OpenData): Pair<Float, Float> {
        val dx = wall.ex - wall.sx; val dz = wall.ez - wall.sz
        val len = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-6f)
        val t   = open.offset + open.width / 2f
        return Pair(wall.sx + (dx / len) * t, wall.sz + (dz / len) * t)
    }

    private fun computeInitialAlignment(
        wA: WallData, oA: OpenData, wB: WallData, oB: OpenData
    ): Triple<Float, Float, Float> {
        fun dir(w: WallData): Pair<Float,Float> {
            val dx = w.ex - w.sx; val dz = w.ez - w.sz
            val l = sqrt(dx*dx+dz*dz).coerceAtLeast(1e-6f)
            return Pair(dx/l, dz/l)
        }
        fun normal(w: WallData): Pair<Float,Float> { val (dx,dz)=dir(w); return Pair(-dz, dx) }
        fun center(w: WallData, o: OpenData): Pair<Float,Float> {
            val (dx,dz)=dir(w); val t=o.offset+o.width/2f
            return Pair(w.sx+dx*t, w.sz+dz*t)
        }

        val (nAx, nAz) = normal(wA)
        val (nBx, nBz) = normal(wB)
        val (cAx, cAz) = center(wA, oA)
        val (cBx, cBz) = center(wB, oB)

        // Rotation: rotate B so nB points opposite to nA
        val targetAngle  = atan2(-nAz, -nAx)
        val currentAngle = atan2(nBz, nBx)
        val theta = targetAngle - currentAngle

        // Translation: after rotation, cB coincides with cA
        val cosT = cos(theta); val sinT = sin(theta)
        val cBrotX = cBx * cosT - cBz * sinT
        val cBrotZ = cBx * sinT + cBz * cosT

        return Triple(cAx - cBrotX, cAz - cBrotZ, theta.toFloat())
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    private fun dp(n: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics).toInt()
}
