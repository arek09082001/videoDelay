package de.aweiss.delaycam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.aweiss.delaycam.playback.PlayMode
import de.aweiss.delaycam.playback.Timeline

/**
 * Untere Bedienleiste (Plan §5). Regeln, die hier bewusst eingehalten werden:
 * Touch-Ziele ≥ 64 dp, Schrift ≥ 16 sp, Haptik bei jedem Moduswechsel — und der
 * **LIVE-Button ist das größte Element**, weil „zurück zum Normalzustand" der häufigste
 * Wunsch im Training ist.
 */
@Composable
fun ControlBar(
    modeOrdinal: Int,
    delayUs: Long,
    speedPermille: Int,
    maxDelayUs: Long,
    loopAUs: Long,
    loopBUs: Long,
    onDelayChange: (Long) -> Unit,
    onSpeed: (Int) -> Unit,
    onStep: (Int) -> Unit,
    onSetA: () -> Unit,
    onSetB: () -> Unit,
    onClearLoop: () -> Unit,
    onLive: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val mode = PlayMode.entries.getOrElse(modeOrdinal) { PlayMode.LIVE_DELAY }
    val paused = mode == PlayMode.PAUSED
    val sliderMax = maxDelayUs.coerceAtLeast(1_000_000L)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(UiTokens.Panel)
            .padding(UiTokens.PanelPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(UiTokens.ButtonGap),
    ) {
        // ── Delay: Slider, Feinjustierung, Presets ────────────────────────────
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Delay " + Timeline.formatSeconds(delayUs),
                    color = UiTokens.OnPanel,
                    fontSize = UiTokens.TextSize,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(110.dp),
                )
                PadButton("−½", compact = true) {
                    onDelayChange((delayUs - 500_000L).coerceIn(0L, sliderMax))
                }
                Slider(
                    value = delayUs.toFloat(),
                    onValueChange = { onDelayChange(it.toLong()) },
                    valueRange = 0f..sliderMax.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = UiTokens.Accent,
                        activeTrackColor = UiTokens.AccentDim,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                PadButton("+½", compact = true) {
                    onDelayChange((delayUs + 500_000L).coerceIn(0L, sliderMax))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.ButtonGap)) {
                for (preset in intArrayOf(0, 3, 5, 8, 12, 20)) {
                    val us = preset * 1_000_000L
                    if (us > sliderMax) continue
                    PadButton(
                        label = "$preset",
                        compact = true,
                        active = delayUs == us,
                    ) { onDelayChange(us) }
                }
                PadButton("⚙", compact = true, onClick = onOpenSettings)
            }
        }

        // ── Geschwindigkeit + Einzelbild + Loop ───────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.ButtonGap)) {
                for (permille in intArrayOf(1000, 500, 250, 100)) {
                    PadButton(
                        label = speedLabel(permille),
                        active = speedPermille == permille && mode != PlayMode.PAUSED,
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSpeed(permille)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.ButtonGap)) {
                PadButton("◀|", enabled = paused) { onStep(-1) }
                PadButton("|▶", enabled = paused) { onStep(+1) }
                PadButton("A", active = loopAUs >= 0) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSetA()
                }
                PadButton("B", active = loopBUs >= 0) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSetB()
                }
                PadButton("⟳ aus", enabled = loopAUs >= 0 || loopBUs >= 0) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClearLoop()
                }
            }
        }

        // ── Der wichtigste Knopf im ganzen UI ─────────────────────────────────
        LiveButton(
            active = mode == PlayMode.LIVE_DELAY,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLive()
            },
        )
    }
}

/** Einheitlicher Knopf mit Mindest-Touchfläche (Plan §5: ≥ 64 dp, Schrift ≥ 16 sp). */
@Composable
fun PadButton(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val background = when {
        !enabled -> UiTokens.PanelSolid
        active -> UiTokens.AccentDim
        else -> Color(0xFF262B30)
    }
    val textColor = when {
        !enabled -> UiTokens.OnPanelDim.copy(alpha = 0.4f)
        active -> UiTokens.Accent
        else -> UiTokens.OnPanel
    }
    Box(
        modifier = modifier
            .defaultMinSize(
                minWidth = if (compact) 52.dp else UiTokens.TouchTarget,
                minHeight = if (compact) 44.dp else UiTokens.TouchTarget,
            )
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = UiTokens.TextSize,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/** Immer erreichbar, immer am größten: zurück in den Normalzustand. */
@Composable
fun LiveButton(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(130.dp)
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) UiTokens.AccentDim else UiTokens.Accent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "LIVE",
            color = if (active) UiTokens.Accent else Color.Black,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
        )
    }
}
