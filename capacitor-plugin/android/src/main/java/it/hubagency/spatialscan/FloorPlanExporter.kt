package it.hubagency.spatialscan

import android.graphics.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Genera una planimetria PNG (vista dall'alto) dai dati delle pareti.
 * Usa Android Canvas/Bitmap — nessuna dipendenza esterna.
 * Salvato in cacheDir, restituisce path assoluto.
 * Le aperture (porte/finestre) appaiono come gap nelle linee delle pareti.
 */
object FloorPlanExporter {

    private const val BMP_SIZE = 1200
    private const val MARGIN = 90f

    fun export(data: RoomExportData, cacheDir: File): String? {
        if (data.walls.isEmpty()) return null
        return try {
            val bitmap = Bitmap.createBitmap(BMP_SIZE, BMP_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            draw(canvas, data)
            val file = File(cacheDir, "floorplan_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 95, it) }
            bitmap.recycle()
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun draw(canvas: Canvas, data: RoomExportData) {
        canvas.drawColor(Color.WHITE)

        val walls  = data.walls
        val roomDim = data.dimensions

        val allX = walls.flatMap { listOf(it.startX, it.endX) }
        val allZ = walls.flatMap { listOf(it.startZ, it.endZ) }
        val minX = allX.min()
        val maxX = allX.max()
        val minZ = allZ.min()
        val maxZ = allZ.max()
        val rangeX = max(maxX - minX, 0.1f)
        val rangeZ = max(maxZ - minZ, 0.1f)
        val drawArea = BMP_SIZE - MARGIN * 2
        val scale = drawArea / max(rangeX, rangeZ)

        fun toPixX(x: Float) = MARGIN + (x - minX) * scale
        fun toPixZ(z: Float) = MARGIN + (z - minZ) * scale

        drawGrid(canvas, minX, maxX, minZ, maxZ, scale, ::toPixX, ::toPixZ)
        drawFloorFill(canvas, walls, ::toPixX, ::toPixZ)
        drawWalls(canvas, walls, ::toPixX, ::toPixZ)
        drawDimensionLabels(canvas, roomDim, minX, maxX, minZ, maxZ, ::toPixX, ::toPixZ)
        drawHeader(canvas, walls, roomDim)
        drawScaleBar(canvas, scale)
    }

    /**
     * Draws each wall as one or more segments, with gaps where openings are.
     * Openings are sorted by offsetAlongWall; solid intervals between them are drawn.
     */
    private fun drawWalls(
        canvas: Canvas,
        walls: List<ExportWall>,
        toPixX: (Float) -> Float,
        toPixZ: (Float) -> Float
    ) {
        val wallPaint = Paint().apply {
            color       = Color.argb(255, 28, 28, 55)
            strokeWidth = 11f
            strokeCap   = Paint.Cap.ROUND
            style       = Paint.Style.STROKE
            isAntiAlias = true
        }
        val openingPaint = Paint().apply {
            color       = Color.argb(200, 100, 170, 255)
            strokeWidth = 5f
            strokeCap   = Paint.Cap.ROUND
            style       = Paint.Style.STROKE
            isAntiAlias = true
            pathEffect  = DashPathEffect(floatArrayOf(4f, 6f), 0f)
        }

        for (wall in walls) {
            val solidIntervals = solidWallIntervals(wall)
            for ((t0, t1) in solidIntervals) {
                val x0 = wall.startX + wall.dirX * t0
                val z0 = wall.startZ + wall.dirZ * t0
                val x1 = wall.startX + wall.dirX * t1
                val z1 = wall.startZ + wall.dirZ * t1
                canvas.drawLine(toPixX(x0), toPixZ(z0), toPixX(x1), toPixZ(z1), wallPaint)
            }

            // Draw a thin dashed line at opening positions
            for (o in wall.openings) {
                val t0 = o.offsetAlongWall
                val t1 = o.offsetAlongWall + o.width
                val x0 = wall.startX + wall.dirX * t0
                val z0 = wall.startZ + wall.dirZ * t0
                val x1 = wall.startX + wall.dirX * t1
                val z1 = wall.startZ + wall.dirZ * t1
                canvas.drawLine(toPixX(x0), toPixZ(z0), toPixX(x1), toPixZ(z1), openingPaint)
            }
        }
    }

    /**
     * Returns the solid (non-opening) intervals [t0, t1] along the wall in metres.
     * t is distance from wall start.
     */
    private fun solidWallIntervals(wall: ExportWall): List<Pair<Float, Float>> {
        val intervals = mutableListOf<Pair<Float, Float>>()
        val sorted = wall.openings.sortedBy { it.offsetAlongWall }
        var cursor = 0f
        for (o in sorted) {
            val gapStart = o.offsetAlongWall.coerceIn(0f, wall.length)
            val gapEnd   = (o.offsetAlongWall + o.width).coerceIn(0f, wall.length)
            if (gapStart > cursor) intervals.add(cursor to gapStart)
            cursor = maxOf(cursor, gapEnd)
        }
        if (cursor < wall.length) intervals.add(cursor to wall.length)
        return intervals
    }

    private fun drawGrid(
        canvas: Canvas,
        minX: Float, maxX: Float, minZ: Float, maxZ: Float,
        scale: Float,
        toPixX: (Float) -> Float, toPixZ: (Float) -> Float
    ) {
        val gridPaint = Paint().apply {
            color      = Color.argb(35, 0, 60, 200)
            strokeWidth = 1f
            style      = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(6f, 8f), 0f)
        }
        var gx = Math.floor(minX.toDouble()).toFloat()
        while (gx <= maxX + 0.01f) {
            val px = toPixX(gx)
            if (px in MARGIN..(BMP_SIZE - MARGIN))
                canvas.drawLine(px, MARGIN, px, BMP_SIZE - MARGIN, gridPaint)
            gx += 1f
        }
        var gz = Math.floor(minZ.toDouble()).toFloat()
        while (gz <= maxZ + 0.01f) {
            val pz = toPixZ(gz)
            if (pz in MARGIN..(BMP_SIZE - MARGIN))
                canvas.drawLine(MARGIN, pz, BMP_SIZE - MARGIN, pz, gridPaint)
            gz += 1f
        }
    }

    private fun drawFloorFill(
        canvas: Canvas,
        walls: List<ExportWall>,
        toPixX: (Float) -> Float, toPixZ: (Float) -> Float
    ) {
        val pts = walls.flatMap {
            listOf(
                PointF(toPixX(it.startX), toPixZ(it.startZ)),
                PointF(toPixX(it.endX),   toPixZ(it.endZ))
            )
        }
        if (pts.size < 3) return
        val hull = convexHull(pts)
        if (hull.size < 3) return
        val path = Path().apply {
            moveTo(hull[0].x, hull[0].y)
            hull.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        canvas.drawPath(path, Paint().apply {
            color = Color.argb(30, 80, 140, 255)
            style = Paint.Style.FILL
        })
        canvas.drawPath(path, Paint().apply {
            color      = Color.argb(80, 40, 100, 220)
            strokeWidth = 1.5f
            style      = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
        })
    }

    private fun drawDimensionLabels(
        canvas: Canvas,
        roomDim: RoomDimensions,
        minX: Float, maxX: Float, minZ: Float, maxZ: Float,
        toPixX: (Float) -> Float, toPixZ: (Float) -> Float
    ) {
        val paint = Paint().apply {
            color     = Color.argb(220, 20, 80, 200)
            textSize  = 34f
            typeface  = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val bgPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }

        val midPx = (toPixX(minX) + toPixX(maxX)) / 2f
        val labelY = BMP_SIZE - MARGIN + 55f
        val wLabel = "%.1fm".format(roomDim.width)
        val tw = paint.measureText(wLabel)
        canvas.drawRect(midPx - tw / 2 - 8, labelY - 36, midPx + tw / 2 + 8, labelY + 6, bgPaint)
        canvas.drawText(wLabel, midPx, labelY, paint)

        val arrowPaint = Paint().apply {
            color = Color.argb(180, 20, 80, 200); strokeWidth = 1.5f; style = Paint.Style.STROKE
        }
        val arrowY = BMP_SIZE - MARGIN + 20f
        canvas.drawLine(toPixX(minX), arrowY, toPixX(maxX), arrowY, arrowPaint)

        val midPz = (toPixZ(minZ) + toPixZ(maxZ)) / 2f
        val lLabel = "%.1fm".format(roomDim.length)
        canvas.save()
        canvas.rotate(90f, BMP_SIZE - MARGIN + 40f, midPz)
        val tl = paint.measureText(lLabel)
        canvas.drawRect(
            BMP_SIZE - MARGIN + 40f - tl / 2 - 8, midPz - 36,
            BMP_SIZE - MARGIN + 40f + tl / 2 + 8, midPz + 6, bgPaint
        )
        canvas.drawText(lLabel, BMP_SIZE - MARGIN + 40f, midPz, paint)
        canvas.restore()
    }

    private fun drawHeader(canvas: Canvas, walls: List<ExportWall>, roomDim: RoomDimensions) {
        val openingCount = walls.sumOf { it.openings.size }
        canvas.drawText("Planimetria ARCore", MARGIN, 50f, Paint().apply {
            color     = Color.argb(210, 20, 20, 65)
            textSize  = 38f
            typeface  = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        })
        val subtitle = buildString {
            append("${walls.size} pareti · %.1f m² · alt. %.1fm".format(roomDim.area, roomDim.height))
            if (openingCount > 0) append(" · $openingCount aperture")
        }
        canvas.drawText(subtitle, MARGIN, 84f, Paint().apply {
            color     = Color.argb(160, 60, 60, 130)
            textSize  = 27f
            isAntiAlias = true
        })
    }

    private fun drawScaleBar(canvas: Canvas, scale: Float) {
        val barX  = MARGIN
        val barY  = BMP_SIZE - MARGIN + 20f
        val barLen = scale
        if (barLen < 20f) return
        val p = Paint().apply { color = Color.argb(160, 40, 40, 40); strokeWidth = 3f }
        canvas.drawLine(barX, barY, barX + barLen, barY, p)
        canvas.drawLine(barX, barY - 6f, barX, barY + 6f, p)
        canvas.drawLine(barX + barLen, barY - 6f, barX + barLen, barY + 6f, p)
        canvas.drawText("1 m", barX + barLen / 2, barY - 10f, Paint().apply {
            color     = Color.argb(160, 40, 40, 40)
            textSize  = 22f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        })
    }

    private fun convexHull(points: List<PointF>): List<PointF> {
        if (points.size <= 2) return points
        val s = points.sortedWith(compareBy({ it.x }, { it.y }))
        fun cross(o: PointF, a: PointF, b: PointF) =
            (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lo = mutableListOf<PointF>()
        for (p in s) {
            while (lo.size >= 2 && cross(lo[lo.size - 2], lo.last(), p) <= 0) lo.removeLast()
            lo.add(p)
        }
        val up = mutableListOf<PointF>()
        for (p in s.reversed()) {
            while (up.size >= 2 && cross(up[up.size - 2], up.last(), p) <= 0) up.removeLast()
            up.add(p)
        }
        lo.removeLast(); up.removeLast()
        return lo + up
    }
}
