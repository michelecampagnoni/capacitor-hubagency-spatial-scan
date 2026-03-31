package it.hubagency.spatialscan

/**
 * Modello stanza rettangolare — output di RoomBoxEstimator.
 *
 * Il box è definito da:
 *  - center: centroide del perimetro in coordinate XZ world
 *  - width:  dimensione lungo headingRad (asse principale PCA), quantizzata a 25cm
 *  - depth:  dimensione lungo headingRad + 90°, quantizzata a 25cm
 *  - headingRad: angolo del lato width rispetto all'asse X world, in [0, PI)
 *  - floorY / ceilingY: Y world pavimento e soffitto
 */
data class RoomBox(
    val centerX:    Float,
    val centerZ:    Float,
    val width:      Float,
    val depth:      Float,
    val headingRad: Float,
    val floorY:     Float,
    val ceilingY:   Float
) {
    val area: Float get() = width * depth

    /**
     * I 4 angoli del box in senso antiorario, in coordinate XZ world.
     * corners[0] = front-right, [1] = front-left, [2] = back-left, [3] = back-right
     * dove "front" = direzione headingRad positiva, "right" = direzione perp positiva.
     */
    fun corners(): Array<FloatArray> {
        val cosH = kotlin.math.cos(headingRad.toDouble()).toFloat()
        val sinH = kotlin.math.sin(headingRad.toDouble()).toFloat()
        val hw = width / 2f
        val hd = depth / 2f
        // axis1 = (cosH, sinH) → width direction
        // axis2 = (-sinH, cosH) → depth direction
        return arrayOf(
            floatArrayOf(centerX + cosH * hw - sinH * hd,   centerZ + sinH * hw + cosH * hd),
            floatArrayOf(centerX - cosH * hw - sinH * hd,   centerZ - sinH * hw + cosH * hd),
            floatArrayOf(centerX - cosH * hw + sinH * hd,   centerZ - sinH * hw - cosH * hd),
            floatArrayOf(centerX + cosH * hw + sinH * hd,   centerZ + sinH * hw - cosH * hd)
        )
    }
}
