package it.hubagency.spatialscan

import android.content.Intent

/**
 * Dimensioni dell'apertura da riusare nella stanza successiva.
 * Passata via Intent extras (nessun Parcelable, zero nuove dipendenze).
 */
data class LinkedOpeningSpec(
    val sourceRoomId:   String,
    val sourceRoomName: String,
    val kind:           OpeningKind,
    val width:          Float,
    val height:         Float,
    val bottom:         Float
) {
    fun putInto(intent: Intent) {
        intent.putExtra(KEY_ROOM_ID,   sourceRoomId)
        intent.putExtra(KEY_ROOM_NAME, sourceRoomName)
        intent.putExtra(KEY_KIND,      kind.name)
        intent.putExtra(KEY_WIDTH,     width)
        intent.putExtra(KEY_HEIGHT,    height)
        intent.putExtra(KEY_BOTTOM,    bottom)
    }

    companion object {
        private const val KEY_ROOM_ID   = "spec_sourceRoomId"
        private const val KEY_ROOM_NAME = "spec_sourceRoomName"
        private const val KEY_KIND      = "spec_kind"
        private const val KEY_WIDTH     = "spec_width"
        private const val KEY_HEIGHT    = "spec_height"
        private const val KEY_BOTTOM    = "spec_bottom"

        fun fromIntent(intent: Intent): LinkedOpeningSpec? {
            val kindStr = intent.getStringExtra(KEY_KIND) ?: return null
            return runCatching {
                LinkedOpeningSpec(
                    sourceRoomId   = intent.getStringExtra(KEY_ROOM_ID)   ?: "",
                    sourceRoomName = intent.getStringExtra(KEY_ROOM_NAME) ?: "",
                    kind           = OpeningKind.valueOf(kindStr),
                    width          = intent.getFloatExtra(KEY_WIDTH,  0f),
                    height         = intent.getFloatExtra(KEY_HEIGHT, 0f),
                    bottom         = intent.getFloatExtra(KEY_BOTTOM, 0f)
                )
            }.getOrNull()
        }
    }
}
