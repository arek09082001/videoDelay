package de.aweiss.delaycam.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import de.aweiss.delaycam.device.CapabilityProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase-0-Screen: zeigt den Geräte-Check-Report und bietet
 * Kopieren/Teilen an, damit die Werte leicht weitergegeben werden können.
 */
@Composable
fun ProbeScreen() {
    val context = LocalContext.current

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> permissionGranted = granted }

    var report by remember { mutableStateOf<String?>(null) }
    var reloadTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(permissionGranted, reloadTrigger) {
        report = withContext(Dispatchers.Default) {
            CapabilityProbe.buildReport(context, permissionGranted)
        }.also { CapabilityProbe.logToLogcat(it) }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(16.dp)
        ) {
            Text("Delay-Cam — Geräte-Check (Phase 0)", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                Button(onClick = {
                    val r = report ?: return@Button
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("DelayCam Geräte-Check", r))
                    // Ab API 33 zeigt das System selbst eine Bestätigung an.
                    if (Build.VERSION.SDK_INT < 33) {
                        Toast.makeText(context, "Report kopiert", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Kopieren") }

                Button(onClick = {
                    val r = report ?: return@Button
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "DelayCam Geräte-Check")
                        putExtra(Intent.EXTRA_TEXT, r)
                    }
                    context.startActivity(Intent.createChooser(send, "Report teilen"))
                }) { Text("Teilen") }

                OutlinedButton(onClick = { reloadTrigger++ }) { Text("Neu einlesen") }

                if (!permissionGranted) {
                    OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Kamera-Berechtigung erteilen")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text = report ?: "Lese Gerätedaten …",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}
