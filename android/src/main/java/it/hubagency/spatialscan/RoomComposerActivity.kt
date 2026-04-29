package it.hubagency.spatialscan

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.*
import com.getcapacitor.JSObject
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.*

/**
 * Editor di composizione planimetrica multi-stanza.
 *
 * Riceve via Intent:
 *   parentRoomId — stanza già presente nel grafo (con apertura PENDING)
 *   newRoomId    — nuova stanza da aggiungere
 *   linkKind     — tipo apertura di collegamento
 *   linkWidth    — larghezza apertura (per identificarla nel JSON)
 *
 * Flusso:
 *  1. Carica tutti i room della componente connessa di parentRoomId dal CompositionGraph.
 *  2. Trasforma ciascuno in world space e li mostra come sfondo immutabile.
 *  3. Calcola l'allineamento iniziale del nuovo ambiente in world space.
 *  4. L'utente regola offset e rotazione del solo nuovo ambiente.
 *  5. Conferma → salva RoomWorldTransform nel grafo → genera PNG dell'intera componente.
 */
class RoomComposerActivity : Activity() {

    private lateinit var composerView: RoomComposerView
    private var parentRoomId = ""; private var newRoomId = ""
    private var linkKind = ""; private var linkWidth = 0f
    private var linkParentOpeningId = ""  // ID esatto dell'apertura nel parent room
    private var linkNewOpeningId    = ""  // ID esatto dell'apertura nel new room

    // World transform corrente del nuovo ambiente (regolato dall'utente)
    private var worldOx  = 0f
    private var worldOz  = 0f
    private var worldRot = 0f   // radianti

    // Undo stack: ogni entry = stato prima dell'ultima azione
    private val undoStack = ArrayDeque<Triple<Float, Float, Float>>()

    // Dati geometrici caricati in onCreate
    private var fixedExportWalls: List<ExportWall> = emptyList()  // world space
    private var newExportWalls:   List<ExportWall> = emptyList()  // local space

    // Mappa indice fisso → roomId (per sapere quale stanza eliminare)
    private val fixedRoomIds = mutableListOf<String>()
    // Bottone elimina stanza (mostrato/nascosto in base alla selezione)
    private lateinit var deleteRoomBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parentRoomId        = intent.getStringExtra("parentRoomId")        ?: ""
        newRoomId           = intent.getStringExtra("newRoomId")           ?: ""
        linkKind            = intent.getStringExtra("linkKind")            ?: ""
        linkWidth           = intent.getFloatExtra("linkWidth", 0f)
        linkParentOpeningId = intent.getStringExtra("linkParentOpeningId") ?: ""
        linkNewOpeningId    = intent.getStringExtra("linkNewOpeningId")    ?: ""
        Log.d("HUB_DIAG", "Composer onCreate parentRoomId='$parentRoomId' newRoomId='$newRoomId' linkParentOpeningId='$linkParentOpeningId' linkNewOpeningId='$linkNewOpeningId'")

        if (newRoomId.isEmpty()) {
            Log.e("Composer", "missing newRoomId"); finish(); return
        }

        if (!loadCompositionData()) {
            AlertDialog.Builder(this)
                .setTitle("Dati non disponibili")
                .setMessage("Non è stato possibile caricare i dati geometrici delle stanze.")
                .setPositiveButton("OK") { _, _ -> finish() }
                .setCancelable(false).show()
            return
        }

        setContentView(buildLayout())
        composerView.onFixedRoomTapped = { idx ->
            deleteRoomBtn.visibility = if (idx >= 0) android.view.View.VISIBLE else android.view.View.GONE
        }
        syncViewTransform()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { finish() }

    // ── Caricamento dati ──────────────────────────────────────────────────────

    /**
     * Carica la componente connessa di parentRoomId, trasforma tutti gli ambienti in world space,
     * calcola l'allineamento iniziale del nuovo ambiente.
     * Restituisce false se i dati essenziali non sono disponibili.
     */
    private fun loadCompositionData(): Boolean {
        // 1. Componente connessa del parent
        val componentIds = CompositionGraph.getComponentRoomIds(this, parentRoomId)
        Log.d("HUB_DIAG", "Composer loadCompositionData componentIds=$componentIds")
        val roomRecords  = RoomHistoryManager.loadAll(this).associateBy { it.id }

        val accFixedWalls = mutableListOf<ExportWall>()
        val accFixedPolys = mutableListOf<Pair<String, List<Pair<Float, Float>>>>()
        var fixedLinkPt: Pair<Float, Float>? = null
        var parentWorldPoly: List<Pair<Float, Float>> = emptyList()
        fixedRoomIds.clear()

        for (roomId in componentIds) {
            fixedRoomIds.add(roomId)
            val json   = RoomHistoryManager.loadRoomData(this, roomId) ?: continue
            val worldT = CompositionGraph.getTransform(this, roomId)
            val rawWalls = parseExportWalls(json)
            val worldWalls = if (worldT != null)
                transformWalls(rawWalls, worldT.worldOffsetX, worldT.worldOffsetZ, worldT.worldRotRad)
            else rawWalls
            accFixedWalls.addAll(worldWalls)

            val rawPoly   = parsePolygon(json)
            val worldPoly = if (worldT != null)
                rawPoly.map { (x, z) -> tp(x, z, worldT.worldOffsetX, worldT.worldOffsetZ, worldT.worldRotRad) }
            else rawPoly
            val name = roomRecords[roomId]?.name ?: roomId.take(6)
            accFixedPolys.add(name to worldPoly)

            // Apertura di collegamento nel parent (usa ID esatto se disponibile)
            if (roomId == parentRoomId) {
                parentWorldPoly = worldPoly
                val (wP, oP) = findLinkOpening(json, linkKind, linkWidth, linkParentOpeningId)
                if (wP != null && oP != null) {
                    val localCenter = openingCenterOf(wP, oP)
                    fixedLinkPt = if (worldT != null)
                        tp(localCenter.first, localCenter.second,
                           worldT.worldOffsetX, worldT.worldOffsetZ, worldT.worldRotRad)
                    else localCenter
                }
            }
        }

        fixedExportWalls = accFixedWalls

        // 2. Nuovo ambiente
        val jsonNew = RoomHistoryManager.loadRoomData(this, newRoomId) ?: return false
        newExportWalls = parseExportWalls(jsonNew)
        val newPoly    = parsePolygon(jsonNew)

        val (wN, oN) = findLinkOpening(jsonNew, linkKind, linkWidth, linkNewOpeningId)
        var newLinkPt: Pair<Float, Float>? = null
        if (wN != null && oN != null) {
            newLinkPt = openingCenterOf(wN, oN)

            // Allineamento iniziale in world space tramite porta di collegamento
            val jsonParent = RoomHistoryManager.loadRoomData(this, parentRoomId) ?: return false
            val parentWorldT = CompositionGraph.getTransform(this, parentRoomId)
            val (wP, oP) = findLinkOpening(jsonParent, linkKind, linkWidth, linkParentOpeningId)
            if (wP != null && oP != null) {
                val (ox, oz, rot) = computeInitialWorldAlignment(wP, oP, parentWorldT, wN, oN)
                worldOx = ox; worldOz = oz; worldRot = rot

                // Anti-overlap: scegli la soluzione (normale o flippata 180°) dove i centroidi
                // sono più distanti — garantisce che il nuovo ambiente sia fuori dal parent.
                if (newPoly.size >= 3 && parentWorldPoly.size >= 3) {
                    val pCentX = parentWorldPoly.map { it.first  }.average().toFloat()
                    val pCentZ = parentWorldPoly.map { it.second }.average().toFloat()
                    val nLocX  = newPoly.map { it.first  }.average().toFloat()
                    val nLocZ  = newPoly.map { it.second }.average().toFloat()

                    val (nWx1, nWz1) = tp(nLocX, nLocZ, worldOx, worldOz, worldRot)
                    val dist1 = (nWx1 - pCentX).pow(2) + (nWz1 - pCentZ).pow(2)

                    // Versione flippata: ruota di 180° e ricalcola offset per mantenere il centro porta
                    val rotF = worldRot + PI.toFloat()
                    val cosF = cos(rotF); val sinF = sin(rotF)
                    val nlx = newLinkPt.first; val nlz = newLinkPt.second
                    val linkW = fixedLinkPt ?: Pair(0f, 0f)
                    val oxF = linkW.first  - (nlx * cosF - nlz * sinF)
                    val ozF = linkW.second - (nlx * sinF + nlz * cosF)
                    val (nWx2, nWz2) = tp(nLocX, nLocZ, oxF, ozF, rotF)
                    val dist2 = (nWx2 - pCentX).pow(2) + (nWz2 - pCentZ).pow(2)

                    Log.d("HUB_DIAG", "anti-overlap dist1=$dist1 dist2=$dist2 → ${if (dist2 > dist1) "FLIP" else "OK"}")
                    if (dist2 > dist1) {
                        worldRot = rotF; worldOx = oxF; worldOz = ozF
                    }
                }
            }
        } else if (accFixedWalls.isNotEmpty()) {
            // Nessuna porta di collegamento nota: posiziona la nuova stanza a destra del piano fisso
            val maxX   = accFixedWalls.maxOf { maxOf(it.startX, it.endX) }
            val minZ   = accFixedWalls.minOf { minOf(it.startZ, it.endZ) }
            val maxZ   = accFixedWalls.maxOf { maxOf(it.startZ, it.endZ) }
            worldOx    = maxX + 3f
            worldOz    = (minZ + maxZ) / 2f
            worldRot   = 0f
        }

        // 3. Aggiorna view
        composerView = RoomComposerView(this).apply {
            fixedPolygons    = accFixedPolys
            newRoomPolygon   = newPoly
            fixedLinkCenter  = fixedLinkPt
            newRoomLinkCenter = newLinkPt
        }

        return true
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout(): android.view.View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 12, 14, 22))
            setPadding(dp(12), dp(10), dp(12), dp(14))
        }

        panel.addView(TextView(this).apply {
            text = "Composizione planimetrica"
            setTextColor(Color.argb(200, 180, 200, 255))
            setTypeface(null, Typeface.BOLD)
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
        })

        panel.addView(makeRow(
            makeBtn("↩ Annulla", Color.argb(255, 60, 40, 20)) { undoLast() }
        ))

        panel.addView(makeLabel("Sposta nuovo ambiente"))
        panel.addView(makeRow(
            makeBtn("←") { moveNew(-0.05f, 0f) },
            makeBtn("→") { moveNew( 0.05f, 0f) },
            makeBtn("↑") { moveNew(0f, -0.05f) },
            makeBtn("↓") { moveNew(0f,  0.05f) }
        ))

        panel.addView(makeLabel("Ruota nuovo ambiente"))
        panel.addView(makeRow(
            makeBtn("↺ 1°") { rotateNew(-1f) },
            makeBtn("↻ 1°") { rotateNew( 1f) },
            makeBtn("↺ 5°") { rotateNew(-5f) },
            makeBtn("↻ 5°") { rotateNew( 5f) },
            makeBtn("Snap 90°") { snap90() }
        ))

        // Bottone elimina stanza selezionata (visibile solo quando una stanza è selezionata)
        deleteRoomBtn = makeBtn("🗑 Elimina stanza", Color.argb(255, 140, 20, 20)) { confirmDeleteRoom() }
        deleteRoomBtn.visibility = android.view.View.GONE
        panel.addView(deleteRoomBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
                .apply { setMargins(dp(4), dp(6), dp(4), 0) })

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actionRow.addView(
            makeBtn("Annulla", Color.argb(255, 80, 30, 30)) { confirmCancel() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), dp(8), dp(4), 0) }
        )
        actionRow.addView(
            makeBtn("Conferma", Color.argb(255, 18, 90, 50)) { confirmSave() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), dp(8), dp(4), 0) }
        )
        panel.addView(actionRow)

        root.addView(composerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { bottomMargin = dp(252) })
        root.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(252), Gravity.BOTTOM
        ))

        return root
    }

    private fun makeLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.argb(140, 160, 180, 220))
        textSize = 10f
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun makeRow(vararg views: android.view.View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            views.forEach { v ->
                addView(v, LinearLayout.LayoutParams(0, dp(40), 1f)
                    .apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
        }

    private fun makeBtn(
        label: String,
        bgColor: Int = Color.argb(255, 30, 40, 60),
        action: () -> Unit
    ): Button = Button(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 11f
        setBackgroundColor(bgColor); setOnClickListener { action() }
    }

    // ── Azioni UI ────────────────────────────────────────────────────────────

    private fun pushUndo() {
        undoStack.addLast(Triple(worldOx, worldOz, worldRot))
        if (undoStack.size > 50) undoStack.removeFirst()
    }

    private fun undoLast() {
        val prev = undoStack.removeLastOrNull() ?: return
        worldOx = prev.first; worldOz = prev.second; worldRot = prev.third
        syncViewTransform()
    }

    private fun moveNew(dx: Float, dz: Float) {
        pushUndo(); worldOx += dx; worldOz += dz; syncViewTransform()
    }

    private fun rotateNew(deltaDeg: Float) {
        pushUndo(); worldRot += Math.toRadians(deltaDeg.toDouble()).toFloat(); syncViewTransform()
    }

    private fun snap90() {
        pushUndo()
        val snapRad = (Math.round(Math.toDegrees(worldRot.toDouble()) / 90.0) * 90.0)
        worldRot = Math.toRadians(snapRad).toFloat(); syncViewTransform()
    }

    private fun syncViewTransform() {
        composerView.offsetX     = worldOx
        composerView.offsetZ     = worldOz
        composerView.rotationRad = worldRot
    }

    private fun confirmCancel() { showContinueScanDialog() }

    private fun confirmSave() {
        // 1. Salva world transform nel grafo
        CompositionGraph.addTransform(this, RoomWorldTransform(
            roomId       = newRoomId,
            parentId     = parentRoomId,
            worldOffsetX = worldOx,
            worldOffsetZ = worldOz,
            worldRotRad  = worldRot,
            confirmedAt  = System.currentTimeMillis()
        ))

        // 2. Calcola area totale di tutti gli ambienti
        val roomRecords = RoomHistoryManager.loadAll(this).associateBy { it.id }
        val totalArea   = (fixedRoomIds + listOf(newRoomId))
            .mapNotNull { roomRecords[it]?.area }.sum()

        // 3. Genera PNG + GLB dell'intera componente (fissi + nuovo)
        val (combinedPath, combinedGlbPath) = generateCombinedFloorPlan(totalArea)
        Log.d("Composer", "saved newRoom=$newRoomId parent=$parentRoomId combinedPng=$combinedPath glb=$combinedGlbPath area=$totalArea")

        // 4. Notifica JS
        if (combinedPath != null) {
            ScanningActivity.onScanComplete?.invoke(JSObject().apply {
                put("success",           true)
                put("floorPlanPath",     combinedPath)
                put("combinedFloorPlan", true)
                put("newRoomId",         newRoomId)
                put("parentRoomId",      parentRoomId)
                put("totalArea",         totalArea)
                if (combinedGlbPath != null) put("glbPath", combinedGlbPath)
            })
        }

        // 4. Dialog "Vuoi scansionare un altro ambiente?"
        showContinueScanDialog()
    }

    // ── Eliminazione stanza ───────────────────────────────────────────────────

    private fun confirmDeleteRoom() {
        val idx = composerView.selectedFixedIndex
        if (idx < 0 || idx >= fixedRoomIds.size) return
        val roomId   = fixedRoomIds[idx]
        val roomName = composerView.fixedPolygons.getOrNull(idx)?.first ?: roomId.take(6)
        AlertDialog.Builder(this)
            .setTitle("Elimina stanza")
            .setMessage("Elimini \"$roomName\" e tutti gli ambienti collegati a essa?\nLe porte abbinate torneranno disponibili.")
            .setPositiveButton("Elimina") { _, _ -> deleteRoomCascade(roomId) }
            .setNegativeButton("Annulla", null)
            .show()
    }

    /**
     * Elimina la stanza indicata + tutti i suoi discendenti nel grafo (cascade BFS).
     * Ripristina le aperture del parent nell'UnlinkedOpeningStore.
     */
    private fun deleteRoomCascade(rootId: String) {
        val toDelete = collectSubtree(rootId)
        val roomRecords = RoomHistoryManager.loadAll(this).associateBy { it.id }

        for (roomId in toDelete) {
            // Trova il parent di questa stanza nel grafo
            val parentId = CompositionGraph.getTransform(this, roomId)?.parentId
            if (parentId != null) {
                val parentName = roomRecords[parentId]?.name ?: parentId.take(6)
                restoreParentOpening(parentId, parentName, roomId)
            }
            RoomHistoryManager.delete(this, roomId)
            CompositionGraph.removeTransform(this, roomId)
            Log.d("HUB_DIAG", "deleteRoomCascade: removed $roomId")
        }

        // Se abbiamo eliminato il parentRoomId, non possiamo più comporre — chiudi
        if (toDelete.contains(parentRoomId)) {
            finish(); return
        }

        // Ricarica i dati fissi e ridisegna
        composerView.selectedFixedIndex = -1
        deleteRoomBtn.visibility = android.view.View.GONE
        reloadFixedData()
    }

    /** BFS discendenti nel grafo a partire da rootId (incluso). */
    private fun collectSubtree(rootId: String): List<String> {
        val result = mutableListOf<String>()
        val queue  = ArrayDeque<String>()
        queue.add(rootId)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            result.add(id)
            queue.addAll(CompositionGraph.getChildIds(this, id))
        }
        return result
    }

    /**
     * Cerca nel JSON del parent l'apertura che puntava a deletedRoomId
     * e la aggiunge di nuovo all'UnlinkedOpeningStore (PENDING).
     */
    private fun restoreParentOpening(parentId: String, parentName: String, deletedRoomId: String) {
        val json  = RoomHistoryManager.loadRoomData(this, parentId) ?: return
        val walls = json.optJSONArray("walls") ?: return
        var changed = false
        for (i in 0 until walls.length()) {
            val wall = walls.optJSONObject(i) ?: continue
            val ops  = wall.optJSONArray("openings") ?: continue
            for (j in 0 until ops.length()) {
                val op = ops.optJSONObject(j) ?: continue
                if (op.optString("linkedRoomId") != deletedRoomId) continue
                // Ripristina come PENDING nel store
                val uop = UnlinkedOpening(
                    id             = "uop_${UUID.randomUUID()}",
                    sourceRoomId   = parentId,
                    sourceRoomName = parentName,
                    openingId      = op.optString("id"),
                    kind           = runCatching { OpeningKind.valueOf(op.optString("kind", "DOOR")) }
                                        .getOrDefault(OpeningKind.DOOR),
                    width          = op.optDouble("width",  0.80).toFloat(),
                    height         = op.optDouble("height", 2.10).toFloat(),
                    bottom         = op.optDouble("bottom", 0.00).toFloat(),
                    wallIndex      = i,
                    customLabel    = op.optString("connectionLabel", "")
                )
                UnlinkedOpeningStore.add(this, uop)
                // Aggiorna JSON: torna PENDING
                op.put("connectionStatus", ConnectionStatus.PENDING.name)
                op.put("linkedRoomId",    "")
                op.put("connectionLabel", "")
                changed = true
            }
        }
        if (changed) {
            File(filesDir, "hub_room_$parentId.json").writeText(json.toString())
            Log.d("HUB_DIAG", "restoreParentOpening: restored opening in $parentId → deleted $deletedRoomId")
        }
    }

    /** Ricarica i poligoni fissi nel composerView dopo una cancellazione. */
    private fun reloadFixedData() {
        val componentIds = CompositionGraph.getComponentRoomIds(this, parentRoomId)
        val roomRecords  = RoomHistoryManager.loadAll(this).associateBy { it.id }
        val newFixedPolys = mutableListOf<Pair<String, List<Pair<Float, Float>>>>()
        val newFixedWalls = mutableListOf<ExportWall>()
        fixedRoomIds.clear()
        for (roomId in componentIds) {
            fixedRoomIds.add(roomId)
            val json   = RoomHistoryManager.loadRoomData(this, roomId) ?: continue
            val worldT = CompositionGraph.getTransform(this, roomId)
            val rawWalls = parseExportWalls(json)
            val worldWalls = if (worldT != null)
                transformWalls(rawWalls, worldT.worldOffsetX, worldT.worldOffsetZ, worldT.worldRotRad)
            else rawWalls
            newFixedWalls.addAll(worldWalls)
            val rawPoly  = parsePolygon(json)
            val worldPoly = if (worldT != null)
                rawPoly.map { (x, z) -> tp(x, z, worldT.worldOffsetX, worldT.worldOffsetZ, worldT.worldRotRad) }
            else rawPoly
            val name = roomRecords[roomId]?.name ?: roomId.take(6)
            newFixedPolys.add(name to worldPoly)
        }
        fixedExportWalls         = newFixedWalls
        composerView.fixedPolygons = newFixedPolys
    }

    private fun showContinueScanDialog() {
        AlertDialog.Builder(this)
            .setTitle("Scan completata")
            .setMessage("Vuoi scansionare un altro ambiente?")
            .setPositiveButton("Sì") { _, _ ->
                startActivity(Intent(this, ScanningActivity::class.java).apply {
                    putExtra("isContinuation", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                finish()
            }
            .setNegativeButton("No") { _, _ ->
                // Sessione terminata esplicitamente → al prossimo startScan() i dati vengono rimossi
                getSharedPreferences("hub_session", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("sessionEnded", true).apply()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    // ── Generazione PNG + GLB combinato ──────────────────────────────────────

    /** Restituisce (pngPath, glbPath) — entrambi nullable. */
    private fun generateCombinedFloorPlan(totalArea: Double = 0.0): Pair<String?, String?> {
        return try {
            val transformedNew = transformWalls(newExportWalls, worldOx, worldOz, worldRot)
            val allWalls = fixedExportWalls + transformedNew
            if (allWalls.isEmpty()) return Pair(null, null)

            val allX = allWalls.flatMap { listOf(it.startX.toDouble(), it.endX.toDouble()) }
            val allZ = allWalls.flatMap { listOf(it.startZ.toDouble(), it.endZ.toDouble()) }
            val dims = RoomDimensions(
                width     = (allX.max() - allX.min()).coerceAtLeast(0.0),
                length    = (allZ.max() - allZ.min()).coerceAtLeast(0.0),
                height    = allWalls.maxOf { it.height.toDouble() },
                area      = totalArea,
                perimeter = 0.0
            )

            // Poligoni per stanza (world space) per le etichette nella PNG e il pavimento nel GLB
            val c = cos(worldRot); val s = sin(worldRot)
            val newRoomName = RoomHistoryManager.loadAll(this).find { it.id == newRoomId }?.name
                ?: newRoomId.take(6)
            val transformedNewPoly = composerView.newRoomPolygon.map { (x, z) ->
                Pair(x * c - z * s + worldOx, x * s + z * c + worldOz)
            }
            val allRoomPolygons = composerView.fixedPolygons + listOf(newRoomName to transformedNewPoly)

            val exportData = RoomExportData(allWalls, dims, allRoomPolygons)
            val pngPath = FloorPlanExporter.export(exportData, cacheDir)
            if (pngPath != null) {
                Thread {
                    val pdfPath = FloorPlanExporter.exportPdf(exportData, cacheDir)
                    if (pdfPath != null) {
                        getSharedPreferences("hub_session", android.content.Context.MODE_PRIVATE)
                            .edit().putString("lastPdfPath", pdfPath).apply()
                        Log.d("HUB_DIAG", "Composer: PDF A3 generato in background: $pdfPath")
                    }
                }.start()
            }
            val glbPath = GlbExporter.export(exportData, cacheDir)
            Pair(pngPath, glbPath)
        } catch (e: Exception) {
            Log.e("Composer", "generateCombinedFloorPlan failed: ${e.message}"); Pair(null, null)
        }
    }

    // ── Allineamento iniziale in world space ──────────────────────────────────

    /**
     * Calcola il world transform del nuovo ambiente in modo che la sua apertura
     * si allinei (normale opposta, centro coincidente) all'apertura del parent in world space.
     */
    private fun computeInitialWorldAlignment(
        wallParent: WallData, openParent: OpenData,
        parentWorldT: RoomWorldTransform?,
        wallNew: WallData, openNew: OpenData
    ): Triple<Float, Float, Float> {
        val pOx  = parentWorldT?.worldOffsetX ?: 0f
        val pOz  = parentWorldT?.worldOffsetZ ?: 0f
        val pRot = parentWorldT?.worldRotRad  ?: 0f

        // Centro e normale dell'apertura del parent in world space
        val (cPxL, cPzL) = openingCenterOf(wallParent, openParent)
        val (cPxW, cPzW) = tp(cPxL, cPzL, pOx, pOz, pRot)
        val (nPxL, nPzL) = normalOf(wallParent)
        val cosP = cos(pRot); val sinP = sin(pRot)
        val nPxW = nPxL * cosP - nPzL * sinP
        val nPzW = nPxL * sinP + nPzL * cosP

        // Normale del nuovo ambiente in spazio locale
        val (nNx, nNz) = normalOf(wallNew)
        val targetAngle  = atan2(-nPzW.toDouble(), -nPxW.toDouble())
        val currentAngle = atan2(nNz.toDouble(), nNx.toDouble())
        val theta        = (targetAngle - currentAngle).toFloat()

        // Centro apertura del nuovo ambiente ruotato → traslazione per far coincidere con cPW
        val (cNxL, cNzL) = openingCenterOf(wallNew, openNew)
        val cosT = cos(theta); val sinT = sin(theta)
        val cNrotX = cNxL * cosT - cNzL * sinT
        val cNrotZ = cNxL * sinT + cNzL * cosT

        return Triple(cPxW - cNrotX, cPzW - cNrotZ, theta)
    }

    // ── Parser JSON ───────────────────────────────────────────────────────────

    private fun parsePolygon(json: JSONObject): List<Pair<Float, Float>> =
        try {
            val vertices = json.getJSONObject("floor").getJSONArray("vertices")
            (0 until vertices.length()).map { i ->
                val v = vertices.getJSONObject(i)
                Pair(v.optDouble("x", 0.0).toFloat(), v.optDouble("z", 0.0).toFloat())
            }
        } catch (e: Exception) {
            Log.e("Composer", "parsePolygon failed: ${e.message}"); emptyList()
        }

    private fun parseExportWalls(json: JSONObject): List<ExportWall> =
        try {
            val walls = json.getJSONArray("walls")
            (0 until walls.length()).map { i ->
                val w  = walls.getJSONObject(i)
                val sp = w.getJSONObject("startPoint")
                val ep = w.getJSONObject("endPoint")
                val sx = sp.optDouble("x", 0.0).toFloat(); val sz = sp.optDouble("z", 0.0).toFloat()
                val ex = ep.optDouble("x", 0.0).toFloat(); val ez = ep.optDouble("z", 0.0).toFloat()
                val dx = ex - sx; val dz = ez - sz
                val len = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-6f)
                val h   = w.optDouble("height", 2.5).toFloat()
                val opsArr = w.optJSONArray("openings")
                val openings: List<ExportOpening> = if (opsArr != null) {
                    (0 until opsArr.length()).mapNotNull { j ->
                        val o    = opsArr.optJSONObject(j) ?: return@mapNotNull null
                        val kind = runCatching { OpeningKind.valueOf(o.optString("kind", "DOOR")) }
                            .getOrDefault(OpeningKind.DOOR)
                        ExportOpening(
                            kind            = kind,
                            offsetAlongWall = o.optDouble("offsetAlongWall", 0.0).toFloat(),
                            width           = o.optDouble("width",  0.8).toFloat(),
                            bottom          = o.optDouble("bottom", 0.0).toFloat(),
                            height          = o.optDouble("height", 2.1).toFloat()
                        )
                    }
                } else emptyList()
                ExportWall(
                    id       = w.optString("id", "w$i"),
                    startX   = sx, startZ = sz, endX = ex, endZ = ez,
                    length   = len, height = h,
                    normalX  = dz / len, normalZ = -(dx / len),
                    dirX     = dx / len, dirZ    = dz / len,
                    openings = openings
                )
            }
        } catch (e: Exception) {
            Log.e("Composer", "parseExportWalls failed: ${e.message}"); emptyList()
        }

    data class WallData(val sx: Float, val sz: Float, val ex: Float, val ez: Float)
    data class OpenData(val offset: Float, val width: Float)

    private fun findLinkOpening(
        json: JSONObject, kind: String, width: Float, openingId: String = ""
    ): Pair<WallData?, OpenData?> {
        return try {
            val walls = json.getJSONArray("walls")
            // Prima passata: match per ID esatto (evita ambiguità con porte stessa larghezza)
            if (openingId.isNotEmpty()) {
                for (i in 0 until walls.length()) {
                    val wall = walls.getJSONObject(i)
                    val ops  = wall.optJSONArray("openings") ?: continue
                    for (j in 0 until ops.length()) {
                        val op = ops.getJSONObject(j)
                        if (op.optString("id") == openingId) {
                            val sp = wall.getJSONObject("startPoint")
                            val ep = wall.getJSONObject("endPoint")
                            Log.d("HUB_DIAG", "findLinkOpening: match per ID $openingId kind=${op.optString("kind")} w=${op.optDouble("width")}")
                            return Pair(
                                WallData(sp.optDouble("x",0.0).toFloat(), sp.optDouble("z",0.0).toFloat(),
                                         ep.optDouble("x",0.0).toFloat(), ep.optDouble("z",0.0).toFloat()),
                                OpenData(op.optDouble("offsetAlongWall", 0.0).toFloat(),
                                         op.optDouble("width", 0.0).toFloat())
                            )
                        }
                    }
                }
                Log.w("HUB_DIAG", "findLinkOpening: ID $openingId non trovato, fallback kind+width")
            }
            // Fallback: match per kind + width (comportamento precedente)
            for (i in 0 until walls.length()) {
                val wall = walls.getJSONObject(i)
                val ops  = wall.optJSONArray("openings") ?: continue
                for (j in 0 until ops.length()) {
                    val op = ops.getJSONObject(j)
                    if (op.optString("kind") == kind && abs(op.optDouble("width", 0.0).toFloat() - width) < 0.12f) {
                        val sp = wall.getJSONObject("startPoint")
                        val ep = wall.getJSONObject("endPoint")
                        return Pair(
                            WallData(sp.optDouble("x",0.0).toFloat(), sp.optDouble("z",0.0).toFloat(),
                                     ep.optDouble("x",0.0).toFloat(), ep.optDouble("z",0.0).toFloat()),
                            OpenData(op.optDouble("offsetAlongWall", 0.0).toFloat(),
                                     op.optDouble("width", 0.0).toFloat())
                        )
                    }
                }
            }
            Pair(null, null)
        } catch (e: Exception) {
            Log.e("Composer", "findLinkOpening failed: ${e.message}"); Pair(null, null)
        }
    }

    // ── Geometria helpers ─────────────────────────────────────────────────────

    private fun openingCenterOf(wall: WallData, open: OpenData): Pair<Float, Float> {
        val dx = wall.ex - wall.sx; val dz = wall.ez - wall.sz
        val len = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-6f)
        val t = open.offset + open.width / 2f
        return Pair(wall.sx + (dx / len) * t, wall.sz + (dz / len) * t)
    }

    private fun normalOf(wall: WallData): Pair<Float, Float> {
        val dx = wall.ex - wall.sx; val dz = wall.ez - wall.sz
        val len = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-6f)
        return Pair(dz / len, -(dx / len))
    }

    /** Trasforma un punto locale nel sistema world con il dato offset/rot. */
    private fun tp(x: Float, z: Float, ox: Float, oz: Float, rot: Float): Pair<Float, Float> {
        val c = cos(rot); val s = sin(rot)
        return Pair(x * c - z * s + ox, x * s + z * c + oz)
    }

    private fun transformWalls(walls: List<ExportWall>, ox: Float, oz: Float, rot: Float): List<ExportWall> {
        val c = cos(rot); val s = sin(rot)
        fun tpt(x: Float, z: Float) = Pair(x * c - z * s + ox, x * s + z * c + oz)
        return walls.map { w ->
            val (sx, sz) = tpt(w.startX, w.startZ)
            val (ex, ez) = tpt(w.endX,   w.endZ)
            val dx = ex - sx; val dz = ez - sz
            val len = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-6f)
            w.copy(startX = sx, startZ = sz, endX = ex, endZ = ez, length = len,
                   dirX = dx/len, dirZ = dz/len, normalX = dz/len, normalZ = -(dx/len))
        }
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    private fun dp(n: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics).toInt()
}
