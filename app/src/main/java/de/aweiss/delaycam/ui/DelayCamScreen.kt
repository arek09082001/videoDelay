package de.aweiss.delaycam.ui

import android.content.Intent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.aweiss.delaycam.AppSettings
import de.aweiss.delaycam.DelayCamEngine
import de.aweiss.delaycam.playback.PlayMode
import de.aweiss.delaycam.playback.Timeline
import de.aweiss.delaycam.ui.drawing.DrawingBoard
import de.aweiss.delaycam.ui.drawing.DrawingColors
import de.aweiss.delaycam.ui.drawing.DrawingOverlayView
import de.aweiss.delaycam.ui.drawing.DrawingToolbar
import de.aweiss.delaycam.ui.drawing.DrawingWidths
import kotlinx.coroutines.delay

/**
 * Wurzel-Composable (Plan §5, Vertrag §9). Aufbau von unten nach oben:
 * Video-`SurfaceView` → Zeichenebene → Gesten → Bedienelemente → Panels.
 *
 * Zustandsanzeige über Polling: Die `@Volatile`-Felder des PlaybackControllers werden einmal
 * pro Frame gelesen. Das ist billiger und ruckelfreier als ein Flow pro Wert — der Controller
 * darf im Timing-Pfad keine Objekte erzeugen (Plan §10 Regel 1).
 */
@Composable
fun DelayCamScreen(engine: DelayCamEngine, settings: MutableState<AppSettings>) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    // ── Gepollter Zustand ─────────────────────────────────────────────────────
    var modeOrdinal by remember { mutableIntStateOf(PlayMode.LIVE_DELAY.ordinal) }
    var delayUs by remember { mutableLongStateOf(settings.value.delayUs) }
    var speedPermille by remember { mutableIntStateOf(1000) }
    var positionPtsUs by remember { mutableLongStateOf(-1L) }
    var oldestPtsUs by remember { mutableLongStateOf(-1L) }
    var newestPtsUs by remember { mutableLongStateOf(-1L) }
    var warmingUp by remember { mutableStateOf(true) }
    var showCaughtUp by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var thermalNote by remember { mutableStateOf<String?>(null) }
    var videoAspect by remember { mutableFloatStateOf(16f / 9f) }

    // ── UI-eigener Zustand ────────────────────────────────────────────────────
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showProbe by remember { mutableStateOf(false) }
    var penActive by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(DrawingOverlayView.Tool.PFEIL) }
    var penColor by remember { mutableStateOf(DrawingColors[1]) }
    var penWidth by remember { mutableFloatStateOf(DrawingWidths[1]) }
    val board = remember { DrawingBoard() }
    var markerA by remember { mutableLongStateOf(-1L) }
    var markerB by remember { mutableLongStateOf(-1L) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            val p = engine.playback
            modeOrdinal = p.modeOrdinal
            delayUs = p.delayUs
            speedPermille = p.speedPermille
            positionPtsUs = p.positionPtsUs
            oldestPtsUs = p.bufOldestPtsUs
            newestPtsUs = p.bufNewestPtsUs
            warmingUp = p.warmingUp
            showCaughtUp = p.hintCaughtUpUntilMs > android.os.SystemClock.uptimeMillis()
            errorMessage = engine.errorMessage
            thermalNote = engine.thermalNote
            videoAspect = engine.videoAspect
        }
    }

    // Delay verzögert sichern: der Slider feuert sonst bei jedem Pixel in die Preferences.
    LaunchedEffect(delayUs) {
        delay(1000)
        if (settings.value.delayUs != delayUs) {
            settings.value = settings.value.copy(delayUs = delayUs)
            settings.value.save(context)
        }
    }

    // Bedienleisten nach 4 s ohne Interaktion ausblenden (Plan §5).
    LaunchedEffect(interactionTick, showSettings) {
        if (showSettings) return@LaunchedEffect
        delay(4000)
        controlsVisible = false
    }

    fun touched() {
        controlsVisible = true
        interactionTick++
    }

    fun goLive() {
        engine.playback.goLive()
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (settings.value.clearDrawingOnLive) board.clearAll()
        penActive = false
        markerA = -1
        markerB = -1
        engine.playback.clearLoop()
        touched()
    }

    fun applySettings(next: AppSettings) {
        settings.value = next
        next.save(context)
        engine.applySettings(next)
    }

    val loopA = if (markerA >= 0) markerA else engine.playback.loopAPtsUs
    val loopB = if (markerB >= 0) markerB else engine.playback.loopBPtsUs

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)) {

        // ── 1. Video, in der Bildmitte auf das Seitenverhältnis gelettert ─────
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val screenAspect = maxWidth / maxHeight
            val videoModifier = if (videoAspect >= screenAspect) {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(videoAspect)
            } else {
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(videoAspect, matchHeightConstraintsFirst = true)
            }

            Box(modifier = videoModifier) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) = Unit

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) {
                                    engine.setVideoSurface(holder.surface, width, height)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    engine.setVideoSurface(null, 0, 0)
                                }
                            })
                        }
                    },
                )

                // ── 2. Zeichenebene direkt über dem Bild ──────────────────────
                // Nur bei aktivem Stift hängt hier eine echte View: dann liegt sie zuoberst und
                // bekommt die Berührungen sicher. Ist der Stift aus, zeichnet Compose dieselben
                // Striche — ohne Touch-Ziel, damit die Wischgesten unangetastet bleiben.
                if (penActive) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx -> DrawingOverlayView(ctx).apply { this.board = board } },
                        update = { view ->
                            view.board = board
                            view.setTool(tool)
                            view.setColor(penColor.toArgb())
                            view.setStrokeWidthDp(penWidth)
                            view.onStrokeStarted = {
                                if (settings.value.freezeOnDraw) engine.playback.freeze()
                            }
                            view.onTwoFingerTap = { goLive() }
                        },
                    )
                } else {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        board.version // Lesezugriff: neu zeichnen, sobald sich etwas ändert
                        drawIntoCanvas { canvas -> board.draw(canvas.nativeCanvas) }
                    }
                }
            }
        }

        // ── 3. Gesten — bei aktivem Stift gar nicht erst im Baum, sonst läge eine
        // Touch-Schicht über der Zeichenebene. Der Zwei-Finger-Tipp für „LIVE" wird in
        // diesem Fall von der Zeichen-View selbst erkannt.
        if (!penActive) GestureLayer(
            enabled = true,
            onTap = {
                if (modeOrdinal == PlayMode.LIVE_DELAY.ordinal) {
                    engine.playback.freeze()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    controlsVisible = true
                    interactionTick++
                } else {
                    controlsVisible = !controlsVisible
                    interactionTick++
                }
            },
            onSkip = { deltaUs ->
                engine.playback.scrubBy(deltaUs)
                touched()
            },
            onScrubBy = { deltaUs ->
                engine.playback.scrubBy(deltaUs)
                controlsVisible = true
            },
            onLoopStart = {
                engine.playback.freeze()
                markerA = if (positionPtsUs >= 0) positionPtsUs else newestPtsUs
                markerB = markerA
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                touched()
            },
            onLoopDrag = { deltaUs ->
                if (markerA >= 0) {
                    markerB = (markerB + deltaUs).coerceIn(oldestPtsUs, newestPtsUs)
                    engine.playback.scrubTo(markerB)
                }
            },
            onLoopEnd = {
                if (markerA >= 0 && markerB >= 0 && markerB != markerA) {
                    engine.playback.setLoop(markerA, markerB)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
            onTwoFingerTap = { goLive() },
        )

        // ── 4. Mini-Vorschau zum Ausrichten ──────────────────────────────────
        if (settings.value.miniPreview) {
            AndroidView(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(width = 160.dp, height = 90.dp)
                    .clip(RoundedCornerShape(8.dp)),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        // Über dem Haupt-Video, aber unter der Compose-Oberfläche.
                        setZOrderMediaOverlay(true)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = Unit

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) {
                                engine.setPreviewSurface(holder.surface)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                engine.setPreviewSurface(null)
                            }
                        })
                    }
                },
            )
        }

        // ── 5. Statusanzeige (immer sichtbar) ────────────────────────────────
        StatusBadge(
            modeOrdinal = modeOrdinal,
            delayUs = delayUs,
            speedPermille = speedPermille,
            loopAUs = loopA,
            loopBUs = loopB,
            warmingUp = warmingUp,
            showCaughtUp = showCaughtUp,
            thermalNote = thermalNote,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        )

        // ── 6. Zeichen-Werkzeuge ──────────────────────────────────────────────
        // Der Stift-Knopf ist immer da: Aus LIVE heraus wäre er sonst unerreichbar, und dann
        // käme man nie zum Zeichnen. Das Antippen friert das Bild ein (Plan §6).
        DrawingToolbar(
            penActive = penActive,
            tool = tool,
            color = penColor,
            widthDp = penWidth,
            onTogglePen = {
                penActive = !penActive
                if (penActive && settings.value.freezeOnDraw) engine.playback.freeze()
                touched()
            },
            onTool = { tool = it },
            onColor = { penColor = it },
            onWidth = { penWidth = it },
            onUndo = { board.undo() },
            onRedo = { board.redo() },
            onClear = { board.clearAll() },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
        )

        // ── 7. Timeline + Bedienleiste, ausblendbar ───────────────────────────
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TimelineBar(
                    oldestPtsUs = oldestPtsUs,
                    newestPtsUs = newestPtsUs,
                    positionPtsUs = positionPtsUs,
                    loopAUs = loopA,
                    loopBUs = loopB,
                    onScrubTo = {
                        engine.playback.scrubTo(it)
                        touched()
                    },
                )
                ControlBar(
                    modeOrdinal = modeOrdinal,
                    delayUs = delayUs,
                    speedPermille = speedPermille,
                    maxDelayUs = 30_000_000L,
                    loopAUs = loopA,
                    loopBUs = loopB,
                    onDelayChange = {
                        engine.playback.setDelayUs(it)
                        touched()
                    },
                    onSpeed = {
                        engine.playback.play(it)
                        touched()
                    },
                    onStep = {
                        engine.playback.stepFrame(it)
                        touched()
                    },
                    onSetA = {
                        markerA = positionPtsUs
                        if (markerB >= 0 && markerB > markerA) {
                            engine.playback.setLoop(markerA, markerB)
                        }
                        touched()
                    },
                    onSetB = {
                        markerB = positionPtsUs
                        if (markerA >= 0 && markerB > markerA) {
                            engine.playback.setLoop(markerA, markerB)
                        }
                        touched()
                    },
                    onClearLoop = {
                        markerA = -1
                        markerB = -1
                        engine.playback.clearLoop()
                        touched()
                    },
                    onLive = { goLive() },
                    onOpenSettings = {
                        showSettings = true
                        touched()
                    },
                )
            }
        }

        // Wenn die Leisten weg sind, bleibt der wichtigste Knopf trotzdem erreichbar.
        if (!controlsVisible && !showSettings) {
            LiveButton(
                active = modeOrdinal == PlayMode.LIVE_DELAY.ordinal,
                onClick = { goLive() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }

        // ── 8. Fehlerbanner ───────────────────────────────────────────────────
        val error = errorMessage
        if (error != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 90.dp)
                    .fillMaxWidth(0.8f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(UiTokens.Danger.copy(alpha = 0.92f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = error,
                    color = Color.Black,
                    fontSize = UiTokens.TextSize,
                    modifier = Modifier.weight(1f),
                )
                PadButton(label = "Neu starten", compact = true) {
                    engine.stop()
                    engine.start()
                }
            }
        }

        // ── 9. Einstellungen ──────────────────────────────────────────────────
        if (showSettings) {
            SettingsSheet(
                settings = settings.value,
                activeProfileLabel = engine.profile.label,
                bufferInfo = if (oldestPtsUs >= 0 && newestPtsUs > oldestPtsUs) {
                    Timeline.formatSeconds(newestPtsUs - oldestPtsUs) + " gefüllt"
                } else {
                    "leer"
                },
                canExport = loopA >= 0 && loopB > loopA,
                onChange = { applySettings(it) },
                onExport = {
                    engine.saveClip(loopA, loopB, speedPermille) { result ->
                        result.onSuccess { uri ->
                            Toast.makeText(
                                context,
                                "Clip gespeichert in Movies/DelayCam",
                                Toast.LENGTH_LONG,
                            ).show()
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Clip teilen"))
                        }
                        result.onFailure { e ->
                            Toast.makeText(
                                context,
                                e.message ?: "Export fehlgeschlagen",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                onShowProbe = {
                    showProbe = true
                    showSettings = false
                },
                onClose = {
                    showSettings = false
                    touched()
                },
            )
        }

        // ── 10. Geräte-Check als Vollbild-Overlay ─────────────────────────────
        if (showProbe) {
            Box(Modifier.fillMaxSize()) {
                ProbeScreen()
                PadButton(
                    label = "Zurück",
                    compact = true,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                ) { showProbe = false }
            }
        }

        // ── 11. Onboarding beim ersten Start ─────────────────────────────────
        if (!settings.value.firstRunDone) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xE0000000)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .width(620.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(UiTokens.PanelSolid)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Delay-Cam",
                        color = UiTokens.Accent,
                        fontSize = UiTokens.BadgeTextSize,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        "Die Kamera läuft dauerhaft — du siehst das Bild um die eingestellte Zeit verzögert.",
                        color = UiTokens.OnPanel,
                        fontSize = UiTokens.TextSize,
                    )
                    Text(
                        "Ins Bild tippen friert es ein, Wischen spult, langes Drücken setzt einen Loop.",
                        color = UiTokens.OnPanel,
                        fontSize = UiTokens.TextSize,
                    )
                    Text(
                        "Der große grüne LIVE-Knopf bringt dich aus jedem Zustand zurück.",
                        color = UiTokens.OnPanel,
                        fontSize = UiTokens.TextSize,
                    )
                    Box(Modifier.height(8.dp))
                    PadButton(label = "Los geht's") {
                        applySettings(settings.value.copy(firstRunDone = true))
                    }
                }
            }
        }
    }
}
