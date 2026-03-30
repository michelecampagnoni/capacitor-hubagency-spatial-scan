package it.hubagency.spatialscan

/**
 * Gestisce i messaggi di guida mostrati all'utente durante la scansione.
 * Logica stateless — restituisce Guidance basata sullo stato corrente.
 */
object GuidanceManager {

    data class Guidance(
        val headline: String,
        val subtext: String,
        val isReady: Boolean = false
    )

    fun get(
        walls: Int,
        trackingState: String,
        elapsedSeconds: Int,
        coveragePercent: Int
    ): Guidance = when {
        trackingState == "STOPPED" -> Guidance(
            headline = "Inizializzazione…",
            subtext = "Punta la camera verso il pavimento o una parete"
        )
        trackingState == "PAUSED" -> Guidance(
            headline = "Tracking in pausa",
            subtext = "Muoviti più lentamente · Assicurati di avere abbastanza luce"
        )
        elapsedSeconds < 4 -> Guidance(
            headline = "Inizia dalla stanza",
            subtext = "Muoviti lentamente puntando la camera verso le pareti"
        )
        walls == 0 -> Guidance(
            headline = "Cerca le pareti",
            subtext = "Avvicinati a una parete e puntala direttamente"
        )
        walls == 1 -> Guidance(
            headline = "1 parete rilevata ✓",
            subtext = "Ruota lentamente per coprire il resto della stanza"
        )
        walls in 2..3 -> Guidance(
            headline = "$walls pareti rilevate",
            subtext = "Continua verso gli angoli e i lati opposti"
        )
        walls in 4..5 -> Guidance(
            headline = "$walls pareti rilevate",
            subtext = "Ottimo! Copri gli angoli per maggiore precisione"
        )
        coveragePercent >= 80 -> Guidance(
            headline = "Stanza quasi completa!",
            subtext = "Puoi fermare la scansione quando sei pronto",
            isReady = true
        )
        else -> Guidance(
            headline = "$walls pareti · $coveragePercent% copertura",
            subtext = "Continua a muoverti per coprire l'intera stanza",
            isReady = walls >= 4
        )
    }
}
