package it.hubagency.spatialscan

import android.content.Context
import android.graphics.*
import android.view.View

/**
 * Vista 2D circolare che mostra la mappa dall'alto della stanza in tempo reale.
 * Aggiornata via update() dal GL thread (tramite mainHandler.post).
 */
class MinimapView(context: Context) : View(context) {

    private val bgPaint = Paint().apply {
        color = Color.argb(190, 8, 12, 28)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = Color.argb(140, 50, 180, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }
    private val wallPaint = Paint().apply {
        color = Color.argb(240, 30, 235, 120)
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val camPaint = Paint().apply {
        color = Color.argb(255, 255, 220, 50)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val noDataPaint = Paint().apply {
        color = Color.argb(130, 180, 180, 200)
        textSize = 22f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.argb(160, 100, 210, 255)
        textSize = 18f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    @Volatile private var walls: List<Wall> = emptyList()
    @Volatile private var cameraX = 0f
    @Volatile private var cameraZ = 0f

    /** Chiamare da mainHandler.post per aggiornare la mappa ogni ~500ms. */
    fun update(walls: List<Wall>, camX: Float, camZ: Float) {
        this.walls = walls
        this.cameraX = camX
        this.cameraZ = camZ
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - 2f

        // Background circolare
        canvas.drawCircle(cx, cy, r, bgPaint)
        canvas.drawCircle(cx, cy, r, borderPaint)

        // Label "MAPPA"
        canvas.drawText("MAPPA", cx, 20f, labelPaint)

        val localWalls = walls
        if (localWalls.isEmpty()) {
            canvas.drawText("…", cx, cy + 8f, noDataPaint)
            return
        }

        // Bounding box di pareti + posizione camera
        val allX = localWalls.flatMap { listOf(it.startPoint.x.toFloat(), it.endPoint.x.toFloat()) } + listOf(cameraX)
        val allZ = localWalls.flatMap { listOf(it.startPoint.z.toFloat(), it.endPoint.z.toFloat()) } + listOf(cameraZ)
        val minX = allX.min()
        val maxX = allX.max()
        val minZ = allZ.min()
        val maxZ = allZ.max()
        val rangeX = maxOf(maxX - minX, 0.5f)
        val rangeZ = maxOf(maxZ - minZ, 0.5f)
        val scale = (r * 1.55f) / maxOf(rangeX, rangeZ)
        val midX = (minX + maxX) / 2f
        val midZ = (minZ + maxZ) / 2f

        fun mapX(x: Float) = cx + (x - midX) * scale
        fun mapZ(z: Float) = cy + (z - midZ) * scale

        // Clip al cerchio
        val clipPath = Path().apply { addCircle(cx, cy, r - 2f, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clipPath)

        // Disegna pareti
        for (wall in localWalls) {
            val x1 = mapX(wall.startPoint.x.toFloat())
            val z1 = mapZ(wall.startPoint.z.toFloat())
            val x2 = mapX(wall.endPoint.x.toFloat())
            val z2 = mapZ(wall.endPoint.z.toFloat())
            canvas.drawLine(x1, z1, x2, z2, wallPaint)
        }

        // Punto camera
        canvas.drawCircle(mapX(cameraX), mapZ(cameraZ), 5.5f, camPaint)

        canvas.restore()
    }
}
