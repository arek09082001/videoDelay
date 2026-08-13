package de.aweiss.delaycam.ui.drawing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import de.aweiss.delaycam.ui.PadButton
import de.aweiss.delaycam.ui.UiTokens

/** Die fünf Farben aus dem Plan — kräftig genug für Hallenlicht. */
val DrawingColors = listOf(
    Color.White,
    Color(0xFFFFEB3B),
    Color(0xFFFF5252),
    Color(0xFF40C4FF),
    Color(0xFF69F0AE),
)

/** Drei Strichstärken in dp (Plan §6). */
val DrawingWidths = listOf(3f, 6f, 10f)

/**
 * Werkzeugspalte rechts (Plan §6). Sichtbar, sobald das Bild steht oder der Stift aktiv ist —
 * während LIVE läuft, ist sie im Weg.
 */
@Composable
fun DrawingToolbar(
    penActive: Boolean,
    tool: DrawingOverlayView.Tool,
    color: Color,
    widthDp: Float,
    onTogglePen: () -> Unit,
    onTool: (DrawingOverlayView.Tool) -> Unit,
    onColor: (Color) -> Unit,
    onWidth: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(UiTokens.Panel)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PadButton(label = "✏", active = penActive, compact = true, onClick = onTogglePen)

        if (penActive) {
            for ((label, t) in TOOLS) {
                PadButton(label = label, active = tool == t, compact = true) { onTool(t) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in DrawingColors) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(
                                width = if (c == color) 3.dp else 1.dp,
                                color = if (c == color) UiTokens.Accent else UiTokens.OnPanelDim,
                                shape = CircleShape,
                            )
                            .clickable { onColor(c) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (w in DrawingWidths) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (w == widthDp) UiTokens.AccentDim else Color(0xFF262B30))
                            .clickable { onWidth(w) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(width = 20.dp, height = w.dp)
                                .clip(CircleShape)
                                .background(UiTokens.OnPanel)
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PadButton(label = "↩", compact = true, onClick = onUndo)
                PadButton(label = "↪", compact = true, onClick = onRedo)
            }
            PadButton(label = "✕ alles", compact = true, onClick = onClear)
        }
    }
}

/** Compose-Farbe → ARGB-Int für die klassische View. */
fun Color.toAndroidColor(): Int = this.toArgb()

private val TOOLS = listOf(
    "〜" to DrawingOverlayView.Tool.FREIHAND,
    "／" to DrawingOverlayView.Tool.LINIE,
    "➔" to DrawingOverlayView.Tool.PFEIL,
    "◯" to DrawingOverlayView.Tool.KREIS,
    "▭" to DrawingOverlayView.Tool.RECHTECK,
)
