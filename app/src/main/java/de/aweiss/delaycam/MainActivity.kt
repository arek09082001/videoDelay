package de.aweiss.delaycam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import de.aweiss.delaycam.ui.ProbeScreen

/**
 * Phase 0: Die App besteht nur aus dem Geräte-Check-Screen.
 * Ab Phase 1 wird hier der eigentliche Delay-Screen eingehängt.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ProbeScreen()
            }
        }
    }
}
