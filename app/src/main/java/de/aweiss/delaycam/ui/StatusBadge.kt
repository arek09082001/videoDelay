package de.aweiss.delaycam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.aweiss.delaycam.playback.PlayMode
import de.aweiss.delaycam.playback.Timeline

/**
 * Zustandsanzeige oben links (Plan §5) — **immer sichtbar**, auch wenn die Bedienleisten
 * ausgeblendet sind. Wer aufs Tablet schaut, muss ohne Nachdenken wissen, ob das Bild live,
 * eingefroren oder in der Wiederholung ist.
 */
@Composable
fun StatusBadge(
    modeOrdinal: Int,
    delayUs: Long,
    speedPermille: Int,
    loopAUs: Long,
    loopBUs: Long,
    warmingUp: Boolean,
    showCaughtUp: Boolean,
    thermalNote: String?,
    modifier: Modifier = Modifier,
) {
    val mode = PlayMode.entries.getOrElse(modeOrdinal) { PlayMode.LIVE_DELAY }
    val headline = when (mode) {
        PlayMode.LIVE_DELAY -> "● LIVE " + Timeline.formatDelayBadge(delayUs)
        PlayMode.PAUSED -> "⏸ PAUSE"
        PlayMode.REPLAY -> "▶ REPLAY " + speedLabel(speedPermille)
        PlayMode.LOOP -> {
            val length = if (loopAUs >= 0 && loopBUs >= 0) loopBUs - loopAUs else 0L
            "⟳ LOOP " + Timeline.formatSeconds(length) + " " + speedLabel(speedPermille)
        }
    }
    val headlineColor = when (mode) {
        PlayMode.LIVE_DELAY -> UiTokens.Accent
        PlayMode.LOOP -> UiTokens.Loop
        else -> UiTokens.OnPanel
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(UiTokens.Panel)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = headline,
            color = headlineColor,
            fontSize = UiTokens.BadgeTextSize,
            fontWeight = FontWeight.Bold,
        )
        if (warmingUp && mode == PlayMode.LIVE_DELAY) {
            Text("Puffer füllt sich …", color = UiTokens.Warn, fontSize = UiTokens.TextSizeSmall)
        }
        if (showCaughtUp) {
            Text("Puffer aufgeholt", color = UiTokens.Warn, fontSize = UiTokens.TextSizeSmall)
        }
        if (thermalNote != null) {
            Text(thermalNote, color = UiTokens.Warn, fontSize = UiTokens.TextSizeSmall)
        }
    }
}
