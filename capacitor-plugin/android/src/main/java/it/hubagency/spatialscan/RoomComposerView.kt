package it.hubagency.spatialscan

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

/**
 * Custom View che disegna la composizione di due stanze in pianta (XZ).
 * Room A è fissa. Room B è trasformata da (offsetX, offsetZ, rotationRad).
 * Le aperture di collegamento sono evidenziate in arancione.
 */
class RoomComposerView(context: Context) : View(context) {

    // Geometria
    var polygonA:    List<Pair<Float, Float>> = emptyList(); set(v) { field = v; invalidate() }
    var polygonB:    List<Pair<Float, Float>> = emptyList(); set(v) { field = v; invalidate() }
    var linkCenterA: Pair<Float, Float>? = null;             set(v) { field = v; invalidate() }
    var linkCenterB: Pair<Float, Float>? = null;             set(v) { field = v; invalidate() }

    // Trasformazione Room B (aggiornata dall'Activity)
    var offsetX:     Float = 0f; set(v) { field = v; invalidate() }
    var offsetZ:     Float = 0f; set(v) { field = v; invalidate() }
    var rotationRad: Float = 0f; set(v) { field = v; invalidate() }

    // Paint
    private val fillA  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 30, 140, 255); style = Paint.Style.FILL }
    private val wallA  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 20, 100, 210); style = Paint.Style.STROKE; strokeWidth = 5f }
    private val fillB  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 50, 200, 80);  style = Paint.Style.FILL }
    private val wallB  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 30, 160, 60); style = Paint.Style.STROKE; strokeWidth = 5f }
    private val linkDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 255, 160, 0); style = Paint.Style.FILL }
    private val linkLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 255, 160, 0); style = Paint.Style.STROKE; strokeWidth = 3f; pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f) }
    private val bgPaint = Paint().apply { color = Color.argb(255, 18, 18, 28) }
    private val labelA = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 100, 180, 255); textSize = 38f; typeface = Typeface.DEFAULT_BOLD }
    private val labelB = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 80, 200, 100); textSize = 38f; typeface = Typeface.DEFAULT_BOLD }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (polygonA.isEmpty() && polygonB.isEmpty()) return

        val transformedB = polygonB.map { (x, z) -> applyTransformB(x, z) }

        // Bounding box su tutti i vertici
        val allVerts = polygonA + transformedB
        if (allVerts.isEmpty()) return

        val margin = 70f
        val minX = allVerts.minOf { it.first }
        val maxX = allVerts.maxOf { it.first }
        val minZ = allVerts.minOf { it.second }
        val maxZ = allVerts.maxOf { it.second }
        val rangeX = (maxX - minX).coerceAtLeast(0.5f)
        val rangeZ = (maxZ - minZ).coerceAtLeast(0.5f)
        val drawW = (width  - margin * 2).coerceAtLeast(1f)
        val drawH = (height - margin * 2).coerceAtLeast(1f)
        val scale = minOf(drawW / rangeX, drawH / rangeZ)

        fun wx(x: Float) = margin + (x - minX) * scale
        fun wz(z: Float) = margin + (z - minZ) * scale

        // Draw Room A
        if (polygonA.size >= 3) {
            val path = Path()
            path.moveTo(wx(polygonA[0].first), wz(polygonA[0].second))
            for (i in 1 until polygonA.size) path.lineTo(wx(polygonA[i].first), wz(polygonA[i].second))
            path.close()
            canvas.drawPath(path, fillA)
            canvas.drawPath(path, wallA)
            // Label
            val cx = polygonA.map { wx(it.first) }.average().toFloat()
            val cz = polygonA.map { wz(it.second) }.average().toFloat()
            canvas.drawText("A", cx - 12f, cz + 14f, labelA)
        }

        // Draw Room B (transformed)
        if (transformedB.size >= 3) {
            val path = Path()
            path.moveTo(wx(transformedB[0].first), wz(transformedB[0].second))
            for (i in 1 until transformedB.size) path.lineTo(wx(transformedB[i].first), wz(transformedB[i].second))
            path.close()
            canvas.drawPath(path, fillB)
            canvas.drawPath(path, wallB)
            val cx = transformedB.map { wx(it.first) }.average().toFloat()
            val cz = transformedB.map { wz(it.second) }.average().toFloat()
            canvas.drawText("B", cx - 12f, cz + 14f, labelB)
        }

        // Draw link markers + dashed line between them
        val cA = linkCenterA
        val cBraw = linkCenterB
        if (cA != null) {
            canvas.drawCircle(wx(cA.first), wz(cA.second), 14f, linkDot)
        }
        if (cBraw != null) {
            val (tbx, tbz) = applyTransformB(cBraw.first, cBraw.second)
            canvas.drawCircle(wx(tbx), wz(tbz), 14f, linkDot)
            if (cA != null) {
                canvas.drawLine(wx(cA.first), wz(cA.second), wx(tbx), wz(tbz), linkLine)
            }
        }
    }

    fun applyTransformB(x: Float, z: Float): Pair<Float, Float> {
        val cosR = cos(rotationRad); val sinR = sin(rotationRad)
        return Pair(x * cosR - z * sinR + offsetX, x * sinR + z * cosR + offsetZ)
    }
}
