package it.hubagency.spatialscan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reticolo sci-fi Hubique — Canvas puro, fissato al centro schermo.
 *
 * Tre stati:
 *  IDLE      → viola/blu tenue, anello esterno lampeggiante lento
 *  TRACKING  → cyan, multi-anello + tick + croce + dot
 *  CONFIRMED → fucsia, flash rapido + glow
 *
 * API invariata: [reticleState] setter uguale a prima.
 */
class ReticleView(context: Context) : View(context) {

    enum class State { IDLE, TRACKING, CONFIRMED }

    @Volatile
    var reticleState: State = State.IDLE
        set(value) {
            if (field != value) { field = value; postInvalidate() }
        }

    private val startMs = System.currentTimeMillis()

    // Pre-allocate paints — zero allocations in onDraw
    private val pRing  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pFill  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // dp-based sizes, set in onSizeChanged
    private var outerR  = 32f
    private var midR    = 21f
    private var innerR  = 11f
    private var crossArm = 18f
    private var crossGap =  7f
    private var dotR    =  3.5f
    private var tickLen =  6f

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        val dp = resources.displayMetrics.density
        outerR   = 42f * dp   // più grande — impatto visivo maggiore
        midR     = 30f * dp
        innerR   = 18f * dp
        crossArm = 24f * dp
        crossGap  =  8f * dp
        dotR      =  4f * dp
        tickLen   =  7f * dp
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width  / 2f
        val cy = height / 2f
        val t  = (System.currentTimeMillis() - startMs) / 1000.0

        // Pulse: 0..1 at 0.65 Hz
        val pulse = (0.5 + 0.5 * sin(t * 2.0 * Math.PI * 0.65)).toFloat()

        when (reticleState) {
            State.IDLE      -> drawIdle(canvas, cx, cy, pulse)
            State.TRACKING  -> drawTracking(canvas, cx, cy, pulse)
            State.CONFIRMED -> drawConfirmed(canvas, cx, cy, pulse)
        }

        if (isAttachedToWindow) postInvalidateDelayed(16L)
    }

    // ── IDLE ─────────────────────────────────────────────────────────────────

    private fun drawIdle(canvas: Canvas, cx: Float, cy: Float, pulse: Float) {
        val a = (55 + 35 * pulse).toInt()
        pRing.color = Color.argb(a, 110, 60, 210)
        pRing.strokeWidth = 1.5f
        canvas.drawCircle(cx, cy, outerR, pRing)
        pRing.color = Color.argb((a * 0.6f).toInt(), 80, 40, 180)
        pRing.strokeWidth = 1f
        canvas.drawCircle(cx, cy, midR, pRing)
        drawTicks(canvas, cx, cy, outerR, 4, Color.argb(a, 110, 60, 210), 1f)
    }

    // ── TRACKING ──────────────────────────────────────────────────────────────

    private fun drawTracking(canvas: Canvas, cx: Float, cy: Float, pulse: Float) {
        // Anello 1 — ghost esterno (lento, molto tenue)
        val ghostA = (30 + 25 * pulse).toInt()
        pRing.color = Color.argb(ghostA, 100, 60, 220)
        pRing.strokeWidth = 1f
        canvas.drawCircle(cx, cy, outerR * 1.35f, pRing)

        // Anello 2 — outer pulsante cyan
        val outerA = (130 + 100 * pulse).toInt()
        val cyanOuter = Color.argb(outerA, 20, 215, 255)
        pRing.color = cyanOuter; pRing.strokeWidth = 2f
        canvas.drawCircle(cx, cy, outerR, pRing)

        // Anello 3 — mid steady
        pRing.color = Color.argb(180, 15, 190, 225); pRing.strokeWidth = 1.5f
        canvas.drawCircle(cx, cy, midR, pRing)

        // Anello 4 — inner dim
        pRing.color = Color.argb(80, 15, 190, 225); pRing.strokeWidth = 1f
        canvas.drawCircle(cx, cy, innerR, pRing)

        // 16 tick marks sul ring esterno
        drawTicks(canvas, cx, cy, outerR, 16, cyanOuter, 1.5f)
        // 4 tick marks lunghi a 90°
        drawTicks(canvas, cx, cy, outerR, 4, Color.argb(outerA, 20, 215, 255), 2f)

        drawCross(canvas, cx, cy, Color.argb(210, 20, 215, 255), 2f)

        pFill.color = Color.argb(255, 20, 220, 255)
        canvas.drawCircle(cx, cy, dotR, pFill)
    }

    // ── CONFIRMED ─────────────────────────────────────────────────────────────

    private fun drawConfirmed(canvas: Canvas, cx: Float, cy: Float, pulse: Float) {
        val outerA = (170 + 80 * pulse).toInt()
        val fucsiaOuter = Color.argb(outerA, 220, 25, 145)

        pRing.color = fucsiaOuter
        pRing.strokeWidth = 2.5f
        canvas.drawCircle(cx, cy, outerR, pRing)

        val midA = (140 + 70 * pulse).toInt()
        pRing.color = Color.argb(midA, 200, 15, 125)
        pRing.strokeWidth = 1.5f
        canvas.drawCircle(cx, cy, midR, pRing)

        drawTicks(canvas, cx, cy, outerR, 4, fucsiaOuter, 2f)
        drawCross(canvas, cx, cy, Color.argb(220, 220, 25, 145), 2f)

        pFill.color = Color.argb(255, 220, 30, 150)
        canvas.drawCircle(cx, cy, dotR + 0.5f, pFill)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, r: Float, count: Int, color: Int, sw: Float) {
        pRing.color = color; pRing.strokeWidth = sw
        val step = (2.0 * Math.PI / count).toFloat()
        for (i in 0 until count) {
            val angle = i * step
            val cosA = cos(angle); val sinA = sin(angle)
            canvas.drawLine(
                cx + cosA * r,         cy + sinA * r,
                cx + cosA * (r + tickLen), cy + sinA * (r + tickLen),
                pRing
            )
        }
    }

    private fun drawCross(canvas: Canvas, cx: Float, cy: Float, color: Int, sw: Float) {
        pRing.color = color; pRing.strokeWidth = sw
        canvas.drawLine(cx - crossArm, cy, cx - crossGap, cy, pRing)
        canvas.drawLine(cx + crossGap, cy, cx + crossArm, cy, pRing)
        canvas.drawLine(cx, cy - crossArm, cx, cy - crossGap, pRing)
        canvas.drawLine(cx, cy + crossGap, cx, cy + crossArm, pRing)
    }
}
