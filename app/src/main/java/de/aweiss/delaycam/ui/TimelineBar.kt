package de.aweiss.delaycam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import de.aweiss.delaycam.playback.Timeline

/**
 * Timeline über den gesamten Pufferinhalt (Plan §5): Balken = gepufferte Zeit, Marke = aktuelle
 * Position, blauer Bereich = A–B-Loop, rechte Kante = LIVE.
 *
 * Tap und Ziehen springen direkt an die getippte Stelle — Scrubben über die Leiste ist die
 * grobe Navigation, die Wischgeste im Bild die feine.
 */
@Composable
fun TimelineBar(
    oldestPtsUs: Long,
    newestPtsUs: Long,
    positionPtsUs: Long,
    loopAUs: Long,
    loopBUs: Long,
    onScrubTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = oldestPtsUs >= 0 && newestPtsUs > oldestPtsUs

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(UiTokens.Panel)
            .pointerInput(oldestPtsUs, newestPtsUs) {
                detectTapGestures { offset ->
                    if (ready) {
                        onScrubTo(
                            Timeline.fractionToPts(
                                offset.x / size.width.toFloat(),
                                oldestPtsUs,
                                newestPtsUs,
                            )
                        )
                    }
                }
            }
            .pointerInput(oldestPtsUs, newestPtsUs) {
                detectHorizontalDragGestures { change, _ ->
                    if (ready) {
                        onScrubTo(
                            Timeline.fractionToPts(
                                change.position.x / size.width.toFloat(),
                                oldestPtsUs,
                                newestPtsUs,
                            )
                        )
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val h = size.height
            val w = size.width
            val trackTop = h * 0.3f
            val trackHeight = h * 0.4f

            // Pufferbereich
            drawRect(
                color = UiTokens.PanelSolid,
                topLeft = Offset(0f, trackTop),
                size = Size(w, trackHeight),
            )

            if (!ready) return@Canvas

            // Loop-Bereich A–B
            if (loopAUs >= 0 && loopBUs > loopAUs) {
                val ax = Timeline.ptsToFraction(loopAUs, oldestPtsUs, newestPtsUs) * w
                val bx = Timeline.ptsToFraction(loopBUs, oldestPtsUs, newestPtsUs) * w
                drawRect(
                    color = UiTokens.Loop.copy(alpha = 0.35f),
                    topLeft = Offset(ax, trackTop),
                    size = Size((bx - ax).coerceAtLeast(2f), trackHeight),
                )
                for (x in listOf(ax, bx)) {
                    drawRect(
                        color = UiTokens.Loop,
                        topLeft = Offset(x - 2f, trackTop - h * 0.12f),
                        size = Size(4f, trackHeight + h * 0.24f),
                    )
                }
            }

            // LIVE-Kante rechts
            drawRect(
                color = UiTokens.Accent,
                topLeft = Offset(w - 6f, trackTop - h * 0.12f),
                size = Size(6f, trackHeight + h * 0.24f),
            )

            // Playhead
            if (positionPtsUs >= 0) {
                val px = Timeline.ptsToFraction(positionPtsUs, oldestPtsUs, newestPtsUs) * w
                drawRect(
                    color = UiTokens.OnPanel,
                    topLeft = Offset(px - 3f, 0f),
                    size = Size(6f, h),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val behind = if (ready && positionPtsUs >= 0) newestPtsUs - positionPtsUs else 0L
            Text(
                text = if (ready) "−" + Timeline.formatClock(behind) else "Puffer leer",
                color = UiTokens.OnPanelDim,
                fontSize = UiTokens.TextSizeSmall,
            )
            Box(Modifier.weight(1f))
            Text(
                text = if (ready) "Puffer " + Timeline.formatSeconds(newestPtsUs - oldestPtsUs) else "",
                color = UiTokens.OnPanelDim,
                fontSize = UiTokens.TextSizeSmall,
            )
        }
    }
}
