package de.aweiss.delaycam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import de.aweiss.delaycam.ui.DelayCamScreen

/**
 * Einstieg: Vollbild-Landscape, Bildschirm an, Immersive Mode.
 * Lifecycle nach Plan §11: onStop reißt die komplette Pipeline ab,
 * onStart baut sie neu auf und startet in LIVE.
 */
class MainActivity : ComponentActivity() {

    private lateinit var engine: DelayCamEngine
    private val settingsState = mutableStateOf(AppSettings())
    private var hasCameraPermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = DelayCamEngine(this)
        settingsState.value = AppSettings.load(this)
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        insets.hide(WindowInsetsCompat.Type.systemBars())
        insets.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (hasCameraPermission) {
                    DelayCamScreen(engine, settingsState)
                } else {
                    PermissionScreen {
                        hasCameraPermission = true
                        engine.start()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasCameraPermission) engine.start()
    }

    override fun onStop() {
        engine.stop()
        super.onStop()
    }
}

@Composable
private fun PermissionScreen(onGranted: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onGranted() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Delay-Cam braucht die Kamera, um das Spielfeld zu filmen.", fontSize = 18.sp)
        Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
            Text("Kamera-Berechtigung erteilen", fontSize = 16.sp)
        }
    }
}
