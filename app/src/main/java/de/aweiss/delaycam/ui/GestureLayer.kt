package de.aweiss.delaycam.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Gestenschicht über dem Video (Plan §5). Alles ist einhändig und ohne Hinsehen bedienbar —
 * im Training steht niemand daneben, der die App bedient.
 *
 * | Geste | Wirkung |
 * |---|---|
 * | Tap | Bild einfrieren bzw. Bedienleisten ein/aus |
 * | Horizontal ziehen | Scrubben, 56 dp ≈ 1 s, ab 2000 dp/s dreifach |
 * | Doppeltipp links/rechts | −2 s / +2 s |
 * | Long-Press + ziehen | A setzen, bis B ziehen → Loop |
 * | Zwei-Finger-Tipp | zurück zu LIVE |
 *
 * Die Gestenerkenner liegen in getrennten `pointerInput`-Blöcken: Wer zuerst konsumiert,
 * gewinnt. Bewegt der Finger sich sofort, greift das Scrubben; bleibt er liegen, greift der
 * Long-Press für die Loop-Auswahl. Der Zwei-Finger-Tipp lauscht in der `Initial`-Phase und
 * konsumiert nie — er funktioniert deshalb zusätzlich zu allem anderen.
 */
@Composable
fun GestureLayer(
    enabled: Boolean,
    onTap: () -> Unit,
    onSkip: (Long) -> Unit,
    onScrubBy: (Long) -> Unit,
    onLoopStart: () -> Unit,
    onLoopDrag: (Long) -> Unit,
    onLoopEnd: () -> Unit,
    onTwoFingerTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // Zwei-Finger-Tipp: immer aktiv, auch bei aktivem Stift.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var maxPointers = 1
                    var moved = false
                    var lastUptime = first.uptimeMillis
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (pressed > maxPointers) maxPointers = pressed
                        if (event.changes.any {
                                (it.position - it.previousPosition).getDistance() > slop
                            }
                        ) {
                            moved = true
                        }
                        event.changes.forEach { if (it.uptimeMillis > lastUptime) lastUptime = it.uptimeMillis }
                        if (event.changes.none { it.pressed }) break
                    }
                    val quick = lastUptime - first.uptimeMillis < TWO_FINGER_MAX_MS
                    if (maxPointers >= 2 && !moved && quick) onTwoFingerTap()
                }
            }
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = { offset ->
                                // Linke Hälfte zurück, rechte Hälfte vor — wie bei jedem Player.
                                onSkip(if (offset.x < size.width / 2f) -2_000_000L else 2_000_000L)
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        // 56 dp Fingerbreite ≈ 1 s; schnelle Wischer beschleunigen dreifach.
                        val pxPerSecond = 56.dp.toPx()
                        var lastUptime = 0L
                        detectHorizontalDragGestures(
                            onDragStart = { lastUptime = 0L },
                            onHorizontalDrag = { change, dragAmount ->
                                val dtMs = if (lastUptime == 0L) 16L
                                else (change.uptimeMillis - lastUptime).coerceAtLeast(1L)
                                lastUptime = change.uptimeMillis
                                val dpPerSecond =
                                    kotlin.math.abs(dragAmount) / pxPerSecond * 56f * 1000f / dtMs
                                val factor = if (dpPerSecond > 2000f) 3L else 1L
                                onScrubBy((dragAmount / pxPerSecond * 1_000_000f).toLong() * factor)
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        val pxPerSecond = 56.dp.toPx()
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onLoopStart() },
                            onDrag = { _, dragAmount ->
                                onLoopDrag((dragAmount.x / pxPerSecond * 1_000_000f).toLong())
                            },
                            onDragEnd = { onLoopEnd() },
                            onDragCancel = { onLoopEnd() },
                        )
                    }
            )
    )
}

private const val TWO_FINGER_MAX_MS = 600L
