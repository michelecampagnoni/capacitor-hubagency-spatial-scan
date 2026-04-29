package it.hubagency.spatialscan

import android.graphics.*
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow

/**
 * Genera planimetrie PNG e PDF A3 (vista dall'alto) dai dati spaziali delle pareti.
 * Entrambi i formati sono renderizzati vettorialmente dalle coordinate reali.
 */
object FloorPlanExporter {

    private const val BMP_SIZE = 1200   // PNG quadrato
    private const val PDF_W    = 1748   // A3 portrait 150dpi
    private const val PDF_H    = 2480

    /** Contesto di rendering: dimensioni canvas + scala font/stroke. */
    private data class RC(
        val W: Int, val H: Int,
        val margin: Float,    // margine sx/dx/basso
        val marginTop: Float, // margine superiore (header)
        val fs: Float         // scala font/stroke: 1.0 = base 1200px
    )

    private fun makeRC(W: Int, H: Int): RC {
        val base = minOf(W, H).toFloat()
        val m    = (base * 0.10f).coerceAtLeast(80f)
        return RC(W, H, m, m * 1.25f, base / 1200f)
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    fun export(data: RoomExportData, cacheDir: File): String? {
        if (data.walls.isEmpty()) return null
        return try {
            val bmp    = Bitmap.createBitmap(BMP_SIZE, BMP_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            draw(canvas, data, makeRC(BMP_SIZE, BMP_SIZE))
            val file = File(cacheDir, "floorplan_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
            bmp.recycle()
            file.absolutePath
        } catch (_: Exception) { null }
    }

    fun exportPdf(data: RoomExportData, cacheDir: File): String? {
        if (data.walls.isEmpty()) return null
        return try {
            val rc  = makeRC(PDF_W, PDF_H)
            val doc = PdfDocument()
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PDF_W, PDF_H, 1).create())
            draw(page.canvas, data, rc)
            doc.finishPage(page)
            val file = File(cacheDir, "floorplan_a3_${System.currentTimeMillis()}.pdf")
            FileOutputStream(file).use { doc.writeTo(it) }
            doc.close()
            file.absolutePath
        } catch (_: Exception) { null }
    }

    // ── Core draw ──────────────────────────────────────────────────────────────

    private fun draw(canvas: Canvas, data: RoomExportData, r: RC) {
        canvas.drawColor(Color.WHITE)

        val roomDim = data.dimensions

        // Rotazione auto-allineamento (min bounding box)
        val alignRot = computeAlignmentRotation(data.walls)
        val ca = cos(alignRot); val sa = sin(alignRot)
        fun rp(x: Float, z: Float) = Pair(x * ca - z * sa, x * sa + z * ca)

        val walls = data.walls.map { w ->
            val (sx, sz) = rp(w.startX, w.startZ); val (ex, ez) = rp(w.endX, w.endZ)
            val dx = ex - sx; val dz = ez - sz
            val len = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-6f)
            w.copy(startX = sx, startZ = sz, endX = ex, endZ = ez, length = len,
                   dirX = dx / len, dirZ = dz / len, normalX = dz / len, normalZ = -(dx / len))
        }
        val roomPolygons = data.roomPolygons.map { (name, poly) ->
            name to poly.map { (x, z) -> rp(x, z) }
        }

        val allX  = walls.flatMap { listOf(it.startX, it.endX) }
        val allZ  = walls.flatMap { listOf(it.startZ, it.endZ) }
        val minX  = allX.min(); val maxX = allX.max()
        val minZ  = allZ.min(); val maxZ = allZ.max()
        val rangeX = max(maxX - minX, 0.1f)
        val rangeZ = max(maxZ - minZ, 0.1f)

        // Scala e origine centrata nel canvas disponibile
        val drawAreaX = r.W - r.margin * 2
        val drawAreaZ = r.H - r.marginTop - r.margin
        val scale     = min(drawAreaX / rangeX, drawAreaZ / rangeZ)
        val originX   = r.margin    + (drawAreaX - rangeX * scale) / 2f
        val originZ   = r.marginTop + (drawAreaZ - rangeZ * scale) / 2f

        fun toPixX(x: Float) = originX + (x - minX) * scale
        fun toPixZ(z: Float) = originZ + (z - minZ) * scale

        val centroidPx = PointF(
            walls.flatMap { listOf(toPixX(it.startX), toPixX(it.endX)) }.average().toFloat(),
            walls.flatMap { listOf(toPixZ(it.startZ), toPixZ(it.endZ)) }.average().toFloat()
        )

        // Lista condivisa anti-sovrapposizione: pre-registra header e bottom bar
        val placed = mutableListOf<RectF>()
        placed.add(RectF(0f, 0f, r.W.toFloat(), r.marginTop))
        placed.add(RectF(0f, r.H - r.margin, r.W.toFloat(), r.H.toFloat()))

        drawGrid(canvas, minX, maxX, minZ, maxZ, originX, originZ,
                 originX + rangeX * scale, originZ + rangeZ * scale, r, ::toPixX, ::toPixZ)
        drawFloorFill(canvas, walls, r, ::toPixX, ::toPixZ)
        drawWalls(canvas, walls, r, ::toPixX, ::toPixZ)
        drawRoomLabels(canvas, roomPolygons, placed, r, ::toPixX, ::toPixZ)
        drawWallQuotes(canvas, walls, centroidPx, placed, r, ::toPixX, ::toPixZ)
        drawDimensionLabels(canvas, roomDim, minX, maxX, minZ, maxZ, r, ::toPixX, ::toPixZ)
        drawHeader(canvas, walls, roomDim, r)
        drawScaleBar(canvas, scale, r)
        drawBranding(canvas, r)
    }

    // ── Alignment rotation ─────────────────────────────────────────────────────

    private fun computeAlignmentRotation(walls: List<ExportWall>): Float {
        val pts = walls.flatMap { listOf(it.startX to it.startZ, it.endX to it.endZ) }
        if (pts.size < 2) return 0f
        var bestAngle = 0f; var bestArea = Float.MAX_VALUE
        for (deg in 0..89) {
            val theta = Math.toRadians(deg.toDouble()).toFloat()
            val c = cos(theta); val s = sin(theta)
            val xs = pts.map { (x, z) -> x * c - z * s }
            val zs = pts.map { (x, z) -> x * s + z * c }
            val area = (xs.max() - xs.min()) * (zs.max() - zs.min())
            if (area < bestArea) { bestArea = area; bestAngle = theta }
        }
        return bestAngle
    }

    // ── Drawing helpers ────────────────────────────────────────────────────────

    private fun drawGrid(
        canvas: Canvas,
        minX: Float, maxX: Float, minZ: Float, maxZ: Float,
        x0pix: Float, z0pix: Float, x1pix: Float, z1pix: Float,
        r: RC,
        toPixX: (Float) -> Float, toPixZ: (Float) -> Float
    ) {
        val paint = Paint().apply {
            color = Color.argb(35, 0, 60, 200); strokeWidth = r.fs
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(6f, 8f), 0f)
        }
        var gx = Math.floor(minX.toDouble()).toFloat()
        while (gx <= maxX + 0.01f) {
            val px = toPixX(gx)
            if (px in x0pix..x1pix) canvas.drawLine(px, z0pix, px, z1pix, paint)
            gx += 1f
        }
        var gz = Math.floor(minZ.toDouble()).toFloat()
        while (gz <= maxZ + 0.01f) {
            val pz = toPixZ(gz)
            if (pz in z0pix..z1pix) canvas.drawLine(x0pix, pz, x1pix, pz, paint)
            gz += 1f
        }
    }

    private fun drawFloorFill(
        canvas: Canvas, walls: List<ExportWall>, r: RC,
        toPixX: (Float) -> Float, toPixZ: (Float) -> Float
    ) {
        val pts = walls.flatMap {
            listOf(PointF(toPixX(it.startX), toPixZ(it.startZ)),
                   PointF(toPixX(it.endX),   toPixZ(it.endZ)))
        }
        if (pts.size < 3) return
        val hull = convexHull(pts); if (hull.size < 3) return
        val path = Path().apply {
            moveTo(hull[0].x, hull[0].y); hull.drop(1).forEach { lineTo(it.x, it.y) }; close()
        }
        canvas.drawPath(path, Paint().apply { color = Color.argb(30, 80, 140, 255); style = Paint.Style.FILL })
        canvas.drawPath(path, Paint().apply {
            color = Color.argb(80, 40, 100, 220); strokeWidth = 1.5f * r.fs; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
        })
    }

    private fun drawWalls(
        canvas: Canvas, walls: List<ExportWall>, r: RC,
        toPixX: (Float) -> Float, toPixZ: (Float) -> Float
    ) {
        val wallPaint = Paint().apply {
            color = Color.argb(255, 28, 28, 55); strokeWidth = 16f * r.fs
            strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE; isAntiAlias = true
        }
        val openingPaint = Paint().apply {
            color = Color.argb(200, 100, 170, 255); strokeWidth = 5f * r.fs
            strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE; isAntiAlias = true
            pathEffect = DashPathEffect(floatArrayOf(4f, 6f), 0f)
        }
        for (wall in walls) {
            for ((t0, t1) in solidWallIntervals(wall)) {
                canvas.drawLine(
                    toPixX(wall.startX + wall.dirX * t0), toPixZ(wall.startZ + wall.dirZ * t0),
                    toPixX(wall.startX + wall.dirX * t1), toPixZ(wall.startZ + wall.dirZ * t1),
                    wallPaint)
            }
            for (o in wall.openings) {
                canvas.drawLine(
                    toPixX(wall.startX + wall.dirX * o.offsetAlongWall),
                    toPixZ(wall.startZ + wall.dirZ * o.offsetAlongWall),
                    toPixX(wall.startX + wall.dirX * (o.offsetAlongWall + o.width)),
                    toPixZ(wall.startZ + wall.dirZ * (o.offsetAlongWall + o.width)),
                    openingPaint)
            }
        }
    }

    private fun solidWallIntervals(wall: ExportWall): List<Pair<Float, Float>> {
        val sorted = wall.openings.sortedBy { it.offsetAlongWall }
        val out = mutableListOf<Pair<Float, Float>>(); var cursor = 0f
        for (o in sorted) {
            val gs = o.offsetAlongWall.coerceIn(0f, wall.length)
            val ge = (o.offsetAlongWall + o.width).coerceIn(0f, wall.length)
            if (gs > cursor) out.add(cursor to gs)
            cursor = maxOf(cursor, ge)
        }
        if (cursor < wall.length) out.add(cursor to wall.length)
        return out
    }

    private fun drawRoomLabels(
        canvas: Canvas, roomPolygons: List<Pair<String, List<Pair<Float, Float>>>>,
        placed: MutableList<RectF>, r: RC,
        toPixX: (Float) -> Float, toPixZ: (Float) -> Float
    ) {
        if (roomPolygons.isEmpty()) return
        val tp = Paint().apply {
            color = Color.argb(240, 18, 30, 90); textSize = 38f * r.fs
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; isAntiAlias = true
        }
        val bg = Paint().apply { color = Color.argb(210, 255, 255, 255); style = Paint.Style.FILL }
        for ((name, poly) in roomPolygons) {
            if (poly.isEmpty()) continue
            val cx = poly.map { toPixX(it.first)  }.average().toFloat()
            val cz = poly.map { toPixZ(it.second) }.average().toFloat()
            val tw = tp.measureText(name); val th = tp.textSize; val pad = 12f * r.fs
            val rect = RectF(cx - tw / 2f - pad, cz - th, cx + tw / 2f + pad, cz + 10f * r.fs)
            if (placed.any { RectF.intersects(it, rect) }) continue
            placed.add(rect)
            canvas.drawRoundRect(rect, 8f * r.fs, 8f * r.fs, bg)
            canvas.drawText(name, cx, cz, tp)
        }
    }

    private fun drawWallQuotes(
        canvas: Canvas, walls: List<ExportWall>, centroid: PointF,
        placed: MutableList<RectF>, r: RC,
        toPixX: (Float) -> Float, toPixZ: (Float) -> Float
    ) {
        val MIN_PX = 70f * r.fs; val OFF = 48f * r.fs; val TICK = 9f * r.fs

        val lp = Paint().apply {
            color = Color.argb(190, 25, 55, 145); strokeWidth = 1.8f * r.fs
            style = Paint.Style.STROKE; isAntiAlias = true
        }
        val tp = Paint().apply {
            color = Color.argb(230, 18, 48, 130); textSize = 24f * r.fs
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; isAntiAlias = true
        }
        val bg = Paint().apply { color = Color.argb(215, 255, 255, 255); style = Paint.Style.FILL }

        for (wall in walls.sortedByDescending { it.length }) {
            val p0x = toPixX(wall.startX); val p0z = toPixZ(wall.startZ)
            val p1x = toPixX(wall.endX);   val p1z = toPixZ(wall.endZ)
            val pxLen = sqrt((p1x - p0x).pow(2) + (p1z - p0z).pow(2))
            if (pxLen < MIN_PX) continue

            val wdx = (p1x - p0x) / pxLen; val wdz = (p1z - p0z) / pxLen
            val ndx = -wdz; val ndz = wdx
            val midPx = (p0x + p1x) / 2f; val midPz = (p0z + p1z) / 2f
            val dot = ndx * (midPx - centroid.x) + ndz * (midPz - centroid.y)
            val ox = if (dot >= 0f) ndx else -ndx; val oz = if (dot >= 0f) ndz else -ndz

            val d0x = p0x + ox * OFF; val d0z = p0z + oz * OFF
            val d1x = p1x + ox * OFF; val d1z = p1z + oz * OFF
            val dmx = (d0x + d1x) / 2f; val dmz = (d0z + d1z) / 2f
            val pad = 14f

            if (d0x < pad || d0x > r.W - pad || d0z < pad || d0z > r.H - pad) continue
            if (d1x < pad || d1x > r.W - pad || d1z < pad || d1z > r.H - pad) continue

            val label = "%.2fm".format(wall.length)
            val tw = tp.measureText(label); val th = tp.textSize; val lpad = 7f * r.fs
            val lr = RectF(dmx - tw / 2f - lpad, dmz - th, dmx + tw / 2f + lpad, dmz + 6f * r.fs)
            if (placed.any { RectF.intersects(it, lr) }) continue
            placed.add(lr)

            canvas.drawLine(p0x, p0z, d0x, d0z, lp); canvas.drawLine(p1x, p1z, d1x, d1z, lp)
            canvas.drawLine(d0x, d0z, d1x, d1z, lp)
            canvas.drawLine(d0x - wdx * TICK, d0z - wdz * TICK, d0x + wdx * TICK, d0z + wdz * TICK, lp)
            canvas.drawLine(d1x - wdx * TICK, d1z - wdz * TICK, d1x + wdx * TICK, d1z + wdz * TICK, lp)
            canvas.drawRoundRect(lr, 4f * r.fs, 4f * r.fs, bg)
            canvas.drawText(label, dmx, dmz, tp)
        }
    }

    private fun drawDimensionLabels(
        canvas: Canvas, roomDim: RoomDimensions,
        minX: Float, maxX: Float, minZ: Float, maxZ: Float,
        r: RC, toPixX: (Float) -> Float, toPixZ: (Float) -> Float
    ) {
        val tp = Paint().apply {
            color = Color.argb(220, 20, 80, 200); textSize = 34f * r.fs
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; isAntiAlias = true
        }
        val bg     = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        val arrow  = Paint().apply { color = Color.argb(180, 20, 80, 200); strokeWidth = 1.5f * r.fs; style = Paint.Style.STROKE }
        val lp     = 8f * r.fs

        // Larghezza (bottom)
        val midPx  = (toPixX(minX) + toPixX(maxX)) / 2f
        val labelY = r.H - r.margin + 55f * r.fs
        val arrowY = r.H - r.margin + 20f * r.fs
        val wLabel = "%.1fm".format(roomDim.width)
        val tw = tp.measureText(wLabel)
        canvas.drawRect(midPx - tw / 2 - lp, labelY - 36f * r.fs, midPx + tw / 2 + lp, labelY + 6f * r.fs, bg)
        canvas.drawText(wLabel, midPx, labelY, tp)
        canvas.drawLine(toPixX(minX), arrowY, toPixX(maxX), arrowY, arrow)

        // Lunghezza (right, ruotato 90°)
        val rightX = r.W - r.margin + 40f * r.fs
        val midPz  = (toPixZ(minZ) + toPixZ(maxZ)) / 2f
        val lLabel = "%.1fm".format(roomDim.length)
        val tl = tp.measureText(lLabel)
        canvas.save()
        canvas.rotate(90f, rightX, midPz)
        canvas.drawRect(rightX - tl / 2 - lp, midPz - 36f * r.fs, rightX + tl / 2 + lp, midPz + 6f * r.fs, bg)
        canvas.drawText(lLabel, rightX, midPz, tp)
        canvas.restore()
    }

    private fun drawHeader(canvas: Canvas, walls: List<ExportWall>, roomDim: RoomDimensions, r: RC) {
        val openings = walls.sumOf { it.openings.size }
        canvas.drawText("Ultimo Rilievo", r.margin, 50f * r.fs, Paint().apply {
            color = Color.argb(210, 20, 20, 65); textSize = 38f * r.fs
            typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
        })
        val sub = buildString {
            append("${walls.size} pareti · %.1f m² · alt. %.1fm".format(roomDim.area, roomDim.height))
            if (openings > 0) append(" · $openings aperture")
        }
        canvas.drawText(sub, r.margin, 84f * r.fs, Paint().apply {
            color = Color.argb(160, 60, 60, 130); textSize = 27f * r.fs; isAntiAlias = true
        })
    }

    private fun drawScaleBar(canvas: Canvas, scale: Float, r: RC) {
        val barX = r.margin; val barY = r.H - r.margin + 20f * r.fs
        if (scale < 10f) return
        val p = Paint().apply { color = Color.argb(160, 40, 40, 40); strokeWidth = 3f * r.fs }
        canvas.drawLine(barX, barY, barX + scale, barY, p)
        canvas.drawLine(barX, barY - 6f * r.fs, barX, barY + 6f * r.fs, p)
        canvas.drawLine(barX + scale, barY - 6f * r.fs, barX + scale, barY + 6f * r.fs, p)
        canvas.drawText("1 m", barX + scale / 2, barY - 10f * r.fs, Paint().apply {
            color = Color.argb(160, 40, 40, 40); textSize = 22f * r.fs
            textAlign = Paint.Align.CENTER; isAntiAlias = true
        })
    }

    private fun drawBranding(canvas: Canvas, r: RC) {
        canvas.drawText("Powered by HubAgency", r.W - 24f * r.fs, r.H - 24f * r.fs, Paint().apply {
            color = Color.argb(110, 60, 60, 160); textSize = 22f * r.fs
            textAlign = Paint.Align.RIGHT; isAntiAlias = true
        })
    }

    // ── Geometry utils ─────────────────────────────────────────────────────────

    private fun convexHull(pts: List<PointF>): List<PointF> {
        if (pts.size <= 2) return pts
        val s = pts.sortedWith(compareBy({ it.x }, { it.y }))
        fun cross(o: PointF, a: PointF, b: PointF) = (a.x-o.x)*(b.y-o.y)-(a.y-o.y)*(b.x-o.x)
        val lo = mutableListOf<PointF>()
        for (p in s) { while (lo.size >= 2 && cross(lo[lo.size-2], lo.last(), p) <= 0) lo.removeLast(); lo.add(p) }
        val up = mutableListOf<PointF>()
        for (p in s.reversed()) { while (up.size >= 2 && cross(up[up.size-2], up.last(), p) <= 0) up.removeLast(); up.add(p) }
        lo.removeLast(); up.removeLast()
        return lo + up
    }
}
