package de.aweiss.delaycam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.aweiss.delaycam.AppSettings

/**
 * Einstellungen als einschiebbares Panel von rechts (Plan §5). Bewusst flach: eine Ebene,
 * große Schalter, keine Untermenüs — im Training darf niemand suchen müssen.
 */
@Composable
fun SettingsSheet(
    settings: AppSettings,
    activeProfileLabel: String,
    bufferInfo: String,
    canExport: Boolean,
    onChange: (AppSettings) -> Unit,
    onExport: () -> Unit,
    onShowProbe: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(androidx.compose.ui.graphics.Color(0xB0000000)),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .fillMaxHeight()
                .background(UiTokens.PanelSolid)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Einstellungen",
                    color = UiTokens.OnPanel,
                    fontSize = UiTokens.BadgeTextSize,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                PadButton("✕", compact = true, onClick = onClose)
            }

            SectionTitle("Aufnahmeprofil — aktiv: $activeProfileLabel")
            Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.ButtonGap)) {
                for (label in listOf("auto", "1080p60", "1080p30")) {
                    PadButton(
                        label = if (label == "auto") "Auto" else label,
                        compact = true,
                        active = settings.profileMode == label,
                    ) { onChange(settings.copy(profileMode = label)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.ButtonGap)) {
                for (label in listOf("720p60", "720p30")) {
                    PadButton(
                        label = label,
                        compact = true,
                        active = settings.profileMode == label,
                    ) { onChange(settings.copy(profileMode = label)) }
                }
            }
            Hint("Profilwechsel startet die Aufnahme neu — der Puffer ist danach leer.")

            SectionTitle("Pufferlänge — $bufferInfo")
            Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.ButtonGap)) {
                for (seconds in intArrayOf(30, 60, 120)) {
                    PadButton(
                        label = "$seconds s",
                        compact = true,
                        active = settings.bufferSeconds == seconds,
                    ) { onChange(settings.copy(bufferSeconds = seconds)) }
                }
            }

            ToggleRow(
                title = "Sport-Belichtung",
                subtitle = "Kurze Belichtung: scharfe Einzelbilder in der Slowmotion, dafür mehr Rauschen.",
                checked = settings.sportExposure,
            ) { onChange(settings.copy(sportExposure = it)) }

            ToggleRow(
                title = "Mini-Vorschau",
                subtitle = "Kleines Live-Bild oben rechts zum Ausrichten der Kamera.",
                checked = settings.miniPreview,
            ) { onChange(settings.copy(miniPreview = it)) }

            ToggleRow(
                title = "Zeichnen friert das Bild ein",
                subtitle = "Beim ersten Strich stoppt die Wiedergabe automatisch.",
                checked = settings.freezeOnDraw,
            ) { onChange(settings.copy(freezeOnDraw = it)) }

            ToggleRow(
                title = "LIVE löscht die Zeichnung",
                subtitle = "Zurück zu LIVE räumt das Overlay auf.",
                checked = settings.clearDrawingOnLive,
            ) { onChange(settings.copy(clearDrawingOnLive = it)) }

            SectionTitle("Clip sichern")
            PadButton(
                label = "Bereich A–B exportieren",
                compact = true,
                enabled = canExport,
                onClick = onExport,
            )
            Hint(
                if (canExport) {
                    "Speichert nach Movies/DelayCam — in der aktuellen Geschwindigkeit, ohne neu zu kodieren."
                } else {
                    "Erst A und B setzen (langes Drücken im Bild oder die Knöpfe A/B)."
                }
            )

            SectionTitle("Gerät")
            PadButton(label = "Geräte-Check anzeigen", compact = true, onClick = onShowProbe)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = UiTokens.OnPanel,
        fontSize = UiTokens.TextSize,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text = text, color = UiTokens.OnPanelDim, fontSize = UiTokens.TextSizeSmall)
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = UiTokens.OnPanel, fontSize = UiTokens.TextSize)
            Text(subtitle, color = UiTokens.OnPanelDim, fontSize = UiTokens.TextSizeSmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = UiTokens.Accent,
                checkedTrackColor = UiTokens.AccentDim,
            ),
        )
    }
}
