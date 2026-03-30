package it.hubagency.spatialscan

import android.graphics.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Genera una planimetria PNG (vista dall'alto) dai dati delle pareti.
 * Usa Android Canvas/Bitmap — nessuna dipendenza esterna.
 * Salvato in cacheDir, restituisce path "file://...".
 */
object FloorPlanExporter {

    private const val BMP_SIZE = 1200
    private const val MARGIN = 90f

    fun export(walls: List<Wall>, roomDim: RoomDimensions, cacheDir: File): String? {
        if (walls.isEmpty()) return null
        return try {
            val bitmap = Bitmap.createBitmap(BMP_SIZE, BMP_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            draw(canvas, walls, roomDim)
            val file = File(cacheDir, "floorplan_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 95, it) }
            bitmap.recycle()
            "file://${file.absolutePath}"
        } catch (_: Exception) {
            null
        }
    }

    private fun draw(canvas: Canvas, walls: List<Wall>, roomDim: RoomDimensions) {
        // Sfondo bianco
        canvas.drawColor(Color.WHITE)

        val allX = walls.flatMap { listOf(it.startPoint.x.toFloat(), it.endPoint.x.toFloat()) }
        val allZ = walls.flatMap { listOf(it.startPoint.z.toFloat(), it.endPoint.z.toFloat()) }
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

        // Griglia 1m
        drawGrid(canvas, minX, maxX, minZ, maxZ, scale, ::toPixX, ::toPixZ)

        // Floor fill (convex hull dei vertici parete)
        drawFloorFill(canvas, walls, ::toPixX, ::toPixZ)

        // Pareti — linee spesse
        val wallPaint = Paint().apply {
            color = Color.argb(255, 28, 28, 55)
            strokeWidth = 11f
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        for (wall in walls) {
            canvas.drawLine(
                toPixX(wall.startPoint.x.toFloat()), toPixZ(wall.startPoint.z.toFloat()),
                toPixX(wall.endPoint.x.toFloat()),   toPixZ(wall.endPoint.z.toFloat()),
                wallPaint
            )
        }

        // Etichette dimensioni
        drawDimensionLabels(canvas, roomDim, minX, maxX, minZ, maxZ, ::toPixX, ::toPixZ)

        // Intestazione
        drawHeader(canvas, walls, roomDim)

        // Barra scala
        drawScaleBar(canvas, scale)
    }

    private fun drawGrid(
        canvas: Canvas,
        minX: Float, maxX: Float, minZ: Float, maxZ: Float,
        scale: Float,
        toPixX: (Float) -> Float, toPixZ: (Float) -> Float
    ) {
        val gridPaint = Paint().apply {
            color = Color.argb(35, 0, 60, 200)
            strokeWidth = 1f
            style = Paint.Style.STROKE
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
        walls: List<Wall>,
        toPixX: (Float) -> Float, toPixZ: (Float) -> Float
    ) {
        val pts = walls.flatMap {
            listOf(
                PointF(toPixX(it.startPoint.x.toFloat()), toPixZ(it.startPoint.z.toFloat())),
                PointF(toPixX(it.endPoint.x.toFloat()),   toPixZ(it.endPoint.z.toFloat()))
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
            color = Color.argb(80, 40, 100, 220)
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
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
            color = Color.argb(220, 20, 80, 200)
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val bgPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }

        // Larghezza (asse X) — etichetta in basso
        val midPx = (toPixX(minX) + toPixX(maxX)) / 2f
        val labelY = BMP_SIZE - MARGIN + 55f
        val wLabel = "%.1fm".format(roomDim.width)
        val tw = paint.measureText(wLabel)
        canvas.drawRect(midPx - tw / 2 - 8, labelY - 36, midPx + tw / 2 + 8, labelY + 6, bgPaint)
        canvas.drawText(wLabel, midPx, labelY, paint)

        // Linea dimensione larghezza
        val arrowPaint = Paint().apply {
            color = Color.argb(180, 20, 80, 200); strokeWidth = 1.5f; style = Paint.Style.STROKE
        }
        val arrowY = BMP_SIZE - MARGIN + 20f
        canvas.drawLine(toPixX(minX), arrowY, toPixX(maxX), arrowY, arrowPaint)

        // Profondità (asse Z) — etichetta a destra
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

    private fun drawHeader(canvas: Canvas, walls: List<Wall>, roomDim: RoomDimensions) {
        canvas.drawText("Planimetria ARCore", MARGIN, 50f, Paint().apply {
            color = Color.argb(210, 20, 20, 65)
            textSize = 38f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        })
        canvas.drawText(
            "${walls.size} pareti · %.1f m² · alt. %.1fm".format(roomDim.area, roomDim.height),
            MARGIN, 84f, Paint().apply {
                color = Color.argb(160, 60, 60, 130)
                textSize = 27f
                isAntiAlias = true
            }
        )
    }

    private fun drawScaleBar(canvas: Canvas, scale: Float) {
        // Barra scala da 1m
        val barX = MARGIN
        val barY = BMP_SIZE - MARGIN + 20f
        val barLen = scale  // 1 metro in pixel
        if (barLen < 20f) return
        val p = Paint().apply { color = Color.argb(160, 40, 40, 40); strokeWidth = 3f }
        canvas.drawLine(barX, barY, barX + barLen, barY, p)
        canvas.drawLine(barX, barY - 6f, barX, barY + 6f, p)
        canvas.drawLine(barX + barLen, barY - 6f, barX + barLen, barY + 6f, p)
        canvas.drawText("1 m", barX + barLen / 2, barY - 10f, Paint().apply {
            color = Color.argb(160, 40, 40, 40)
            textSize = 22f
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
