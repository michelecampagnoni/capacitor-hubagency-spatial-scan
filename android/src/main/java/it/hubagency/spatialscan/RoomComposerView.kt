package it.hubagency.spatialscan

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * Custom View per la composizione planimetrica multi-stanza.
 *
 * fixedPolygons: ambienti già consolidati nel grafo, già in world space (sfondo immutabile).
 * newRoomPolygon: nuovo ambiente da aggiungere, in spazio locale.
 *
 * offsetX/offsetZ/rotationRad: world transform corrente del nuovo ambiente.
 * fixedLinkCenter: punto di aggancio (centro apertura di collegamento) in world space.
 * newRoomLinkCenter: punto di aggancio del nuovo ambiente in spazio locale.
 */
class RoomComposerView(context: Context) : View(context) {

    // Ambienti già compositi — (nome, lista vertici) in world space
    var fixedPolygons:    List<Pair<String, List<Pair<Float, Float>>>> = emptyList()
        set(v) { field = v; invalidate() }

    // Nuovo ambiente da aggiungere — vertici in spazio locale
    var newRoomPolygon:    List<Pair<Float, Float>> = emptyList()
        set(v) { field = v; invalidate() }

    // Punto di aggancio lato ambienti fissi (world space)
    var fixedLinkCenter:   Pair<Float, Float>? = null; set(v) { field = v; invalidate() }
    // Punto di aggancio lato nuovo ambiente (spazio locale, trasformato prima del disegno)
    var newRoomLinkCenter: Pair<Float, Float>? = null; set(v) { field = v; invalidate() }

    // World transform del nuovo ambiente (aggiornato dall'Activity)
    var offsetX:     Float = 0f; set(v) { field = v; invalidate() }
    var offsetZ:     Float = 0f; set(v) { field = v; invalidate() }
    var rotationRad: Float = 0f; set(v) { field = v; invalidate() }

    // Indice del poligono fisso selezionato (-1 = nessuno)
    var selectedFixedIndex: Int = -1; set(v) { field = v; invalidate() }
    // Callback invocata quando l'utente tappa un poligono fisso
    var onFixedRoomTapped: ((index: Int) -> Unit)? = null

    // Parametri dell'ultimo draw — usati per hit-test in onTouchEvent
    private var lastMinX  = 0f; private var lastMinZ  = 0f; private var lastScale = 1f
    private val lastMargin = 70f

    // Zoom / pan — gestione manuale multi-touch
    private var zoomScale   = 1f
    private var panX        = 0f
    private var panY        = 0f
    private var lastTouchX  = 0f
    private var lastTouchY  = 0f
    private var isDragging  = false
    private var ptr1Id      = -1
    private var ptr2Id      = -1
    private var prevPinchDist = 0f

    private fun pinchDist(e: MotionEvent, i1: Int, i2: Int): Float {
        val dx = e.getX(i1) - e.getX(i2)
        val dy = e.getY(i1) - e.getY(i2)
        return sqrt(dx * dx + dy * dy)
    }

    // Paint
    private val fillFixed    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 30, 140, 255);  style = Paint.Style.FILL }
    private val fillSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 220, 60, 60); style = Paint.Style.FILL }
    private val wallSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 80, 80); style = Paint.Style.STROKE; strokeWidth = 5f }
    private val wallFixed  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 20, 100, 210); style = Paint.Style.STROKE; strokeWidth = 5f }
    private val fillNew    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 50, 200, 80);   style = Paint.Style.FILL }
    private val wallNew    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 30, 160, 60);  style = Paint.Style.STROKE; strokeWidth = 5f }
    private val linkDot    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 255, 160, 0);  style = Paint.Style.FILL }
    private val linkLine   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 160, 0); style = Paint.Style.STROKE; strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val bgPaint    = Paint().apply { color = Color.argb(255, 18, 18, 28) }
    private val labelFixed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 100, 180, 255); textSize = 34f; typeface = Typeface.DEFAULT_BOLD }
    private val labelNew   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 80,  200, 100); textSize = 34f; typeface = Typeface.DEFAULT_BOLD }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        canvas.save()
        val cx = width / 2f; val cy = height / 2f
        canvas.translate(panX + cx, panY + cy)
        canvas.scale(zoomScale, zoomScale)
        canvas.translate(-cx, -cy)

        val transformedNew = newRoomPolygon.map { (x, z) -> applyTransform(x, z) }
        val allVerts = fixedPolygons.flatMap { it.second } + transformedNew
        if (allVerts.isEmpty()) return

        val margin = lastMargin
        val minX = allVerts.minOf { it.first };  val maxX = allVerts.maxOf { it.first }
        val minZ = allVerts.minOf { it.second }; val maxZ = allVerts.maxOf { it.second }
        val scale = minOf(
            (width  - margin * 2).coerceAtLeast(1f) / (maxX - minX).coerceAtLeast(0.5f),
            (height - margin * 2).coerceAtLeast(1f) / (maxZ - minZ).coerceAtLeast(0.5f)
        )
        lastMinX = minX; lastMinZ = minZ; lastScale = scale

        fun wx(x: Float) = margin + (x - minX) * scale
        fun wz(z: Float) = margin + (z - minZ) * scale

        // Disegna ambienti fissi (già in world space)
        for ((idx, entry) in fixedPolygons.withIndex()) {
            val (name, poly) = entry
            if (poly.size < 3) continue
            val path = Path().apply {
                moveTo(wx(poly[0].first), wz(poly[0].second))
                for (i in 1 until poly.size) lineTo(wx(poly[i].first), wz(poly[i].second))
                close()
            }
            val isSelected = idx == selectedFixedIndex
            canvas.drawPath(path, if (isSelected) fillSelected else fillFixed)
            canvas.drawPath(path, if (isSelected) wallSelected else wallFixed)
            val cx = poly.map { wx(it.first)  }.average().toFloat()
            val cz = poly.map { wz(it.second) }.average().toFloat()
            canvas.drawText(name, cx - labelFixed.measureText(name) / 2f, cz + 12f, labelFixed)
        }

        // Disegna nuovo ambiente (trasformato)
        if (transformedNew.size >= 3) {
            val path = Path().apply {
                moveTo(wx(transformedNew[0].first), wz(transformedNew[0].second))
                for (i in 1 until transformedNew.size) lineTo(wx(transformedNew[i].first), wz(transformedNew[i].second))
                close()
            }
            canvas.drawPath(path, fillNew)
            canvas.drawPath(path, wallNew)
            val cx = transformedNew.map { wx(it.first)  }.average().toFloat()
            val cz = transformedNew.map { wz(it.second) }.average().toFloat()
            canvas.drawText("NUOVO", cx - labelNew.measureText("NUOVO") / 2f, cz + 12f, labelNew)
        }

        // Marcatori apertura di collegamento
        val cFixed = fixedLinkCenter
        val cNewRaw = newRoomLinkCenter
        if (cFixed != null) {
            canvas.drawCircle(wx(cFixed.first), wz(cFixed.second), 14f, linkDot)
        }
        if (cNewRaw != null) {
            val (tnx, tnz) = applyTransform(cNewRaw.first, cNewRaw.second)
            canvas.drawCircle(wx(tnx), wz(tnz), 14f, linkDot)
            if (cFixed != null) {
                canvas.drawLine(wx(cFixed.first), wz(cFixed.second), wx(tnx), wz(tnz), linkLine)
            }
        }
        canvas.restore()
    }

    /** Applica il world transform corrente a un punto in spazio locale del nuovo ambiente. */
    fun applyTransform(x: Float, z: Float): Pair<Float, Float> {
        val c = cos(rotationRad); val s = sin(rotationRad)
        return Pair(x * c - z * s + offsetX, x * s + z * c + offsetZ)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ptr1Id = event.getPointerId(0)
                lastTouchX = event.x; lastTouchY = event.y
                isDragging = false; ptr2Id = -1; prevPinchDist = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                ptr2Id = event.getPointerId(event.actionIndex)
                val i1 = event.findPointerIndex(ptr1Id)
                val i2 = event.findPointerIndex(ptr2Id)
                if (i1 >= 0 && i2 >= 0) prevPinchDist = pinchDist(event, i1, i2)
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (ptr2Id >= 0) {
                    // Pinch → zoom
                    val i1 = event.findPointerIndex(ptr1Id)
                    val i2 = event.findPointerIndex(ptr2Id)
                    if (i1 >= 0 && i2 >= 0 && prevPinchDist > 0f) {
                        val dist = pinchDist(event, i1, i2)
                        zoomScale = (zoomScale * dist / prevPinchDist).coerceIn(0.3f, 8f)
                        prevPinchDist = dist
                        invalidate()
                    }
                } else {
                    // Un dito → pan
                    val dx = event.x - lastTouchX; val dy = event.y - lastTouchY
                    if (abs(dx) > 8f || abs(dy) > 8f) isDragging = true
                    if (isDragging) { panX += dx; panY += dy; invalidate() }
                    lastTouchX = event.x; lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val liftedId = event.getPointerId(event.actionIndex)
                if (liftedId == ptr2Id) { ptr2Id = -1 } else { ptr1Id = ptr2Id; ptr2Id = -1 }
                prevPinchDist = 0f; isDragging = false
                val remaining = if (ptr1Id >= 0) event.findPointerIndex(ptr1Id) else -1
                if (remaining >= 0) { lastTouchX = event.getX(remaining); lastTouchY = event.getY(remaining) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP && !isDragging && ptr2Id < 0) {
                    val cx = width / 2f; val cy = height / 2f
                    val canvasX = (event.x - panX - cx) / zoomScale + cx
                    val canvasZ = (event.y - panY - cy) / zoomScale + cy
                    val wx = (canvasX - lastMargin) / lastScale + lastMinX
                    val wz = (canvasZ - lastMargin) / lastScale + lastMinZ
                    for ((idx, entry) in fixedPolygons.withIndex()) {
                        if (pointInPolygon(wx, wz, entry.second)) {
                            selectedFixedIndex = if (selectedFixedIndex == idx) -1 else idx
                            onFixedRoomTapped?.invoke(selectedFixedIndex)
                            return true
                        }
                    }
                    if (selectedFixedIndex != -1) { selectedFixedIndex = -1; onFixedRoomTapped?.invoke(-1) }
                }
                ptr1Id = -1; ptr2Id = -1; prevPinchDist = 0f; isDragging = false
            }
        }
        return true
    }

    /** Ray casting: true se (px, pz) è dentro il poligono. */
    private fun pointInPolygon(px: Float, pz: Float, poly: List<Pair<Float, Float>>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val xi = poly[i].first; val zi = poly[i].second
            val xj = poly[j].first; val zj = poly[j].second
            if ((zi > pz) != (zj > pz) && px < (xj - xi) * (pz - zi) / (zj - zi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
