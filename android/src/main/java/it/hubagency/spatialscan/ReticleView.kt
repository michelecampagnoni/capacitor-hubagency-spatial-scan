package it.hubagency.spatialscan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Crosshair Android View (Canvas puro, nessun GL) fissa al centro schermo.
 *
 * Tre stati visivi:
 *  IDLE      → cerchio grigio, nessun dot (floor non ancora acquisito)
 *  TRACKING  → cerchio bianco con croce + dot centrale (pronto per il tap)
 *  CONFIRMED → cerchio verde + dot (punto appena confermato, feedback visivo)
 */
class ReticleView(context: Context) : View(context) {

    enum class State { IDLE, TRACKING, CONFIRMED }

    @Volatile
    var reticleState: State = State.IDLE
        set(value) {
            if (field != value) { field = value; postInvalidate() }
        }

    private val paintRing  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val paintCross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val paintDot   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width  / 2f
        val cy = height / 2f

        val color = when (reticleState) {
            State.IDLE      -> Color.argb(140, 150, 150, 150)
            State.TRACKING  -> Color.argb(220, 255, 255, 255)
            State.CONFIRMED -> Color.argb(255,  40, 220, 100)
        }
        paintRing.color  = color
        paintCross.color = color
        paintDot.color   = color

        val ringR     = 22f
        val crossHalf = 15f
        val gapHalf   =  6f   // gap tra il centro e l'inizio della croce

        // Cerchio esterno
        canvas.drawCircle(cx, cy, ringR, paintRing)

        // Croce con gap centrale (4 segmenti staccati)
        if (reticleState != State.IDLE) {
            canvas.drawLine(cx - crossHalf, cy, cx - gapHalf, cy, paintCross)
            canvas.drawLine(cx + gapHalf,   cy, cx + crossHalf, cy, paintCross)
            canvas.drawLine(cx, cy - crossHalf, cx, cy - gapHalf, paintCross)
            canvas.drawLine(cx, cy + gapHalf,   cx, cy + crossHalf, paintCross)

            // Dot centrale
            canvas.drawCircle(cx, cy, 3f, paintDot)
        }
    }
}
