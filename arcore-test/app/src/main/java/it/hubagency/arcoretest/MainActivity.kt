package it.hubagency.arcoretest

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.ar.core.ArCoreApk

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val result = when (ArCoreApk.getInstance().checkAvailability(this)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED ->
                Pair("✅ ARCore disponibile e installato!", Color.parseColor("#1a7a1a"))
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ->
                Pair("⚠️ ARCore vecchio — aggiorna dal Play Store", Color.parseColor("#b36200"))
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ->
                Pair("❌ ARCore non installato\nScarica 'Google Play Services for AR'", Color.RED)
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
                Pair("❌ Device non supporta ARCore", Color.RED)
            else ->
                Pair("❓ Stato ARCore sconosciuto", Color.GRAY)
        }

        val tv = TextView(this).apply {
            text = result.first
            textSize = 24f
            setTextColor(result.second)
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }

        setContentView(tv)
    }
}
