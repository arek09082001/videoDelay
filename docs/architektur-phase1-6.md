# Delay-Cam — Verbindliche Modul-Verträge (Phase 1–6)

Dieses Dokument ist der Vertrag zwischen den parallel arbeitenden Modulen.
**Signaturen hier sind bindend.** Wer abweichen muss, vermerkt das im Abschlussbericht.
Grundlage: `delay-cam-plan.md` (Architektur §2, Ring-Buffer §3, Playback §4, UI §5/§6, Regeln §10).

Package-Root: `de.aweiss.delaycam`. minSdk 29, compileSdk 35, Kotlin 2.1, Compose (BOM 2025.06.01).
**Keine neuen Dependencies. Keine Gradle-Builds durch Modul-Agenten. Nur die eigenen Dateien anfassen.**
Knifflige Stellen (Wrap, GOP-Verdrängung, Seek, Pacing) auf Deutsch kommentieren.

## Datei-Eigentum

| Agent | Dateien (unter `app/src/main/java/de/aweiss/delaycam/`) |
|---|---|
| buffer | `buffer/EncodedRingBuffer.kt` |
| capture | `capture/CameraController.kt`, `capture/VideoEncoder.kt` |
| playback | `playback/PlaybackController.kt`, `playback/VideoDecoder.kt`, `playback/Timeline.kt` |
| ui | alles unter `ui/` außer `ui/ProbeScreen.kt` (existiert), inkl. `ui/drawing/` |
| aux | `AppSettings.kt`, `device/ThermalWatcher.kt`, `export/ClipExporter.kt` |
| Integrator (Hauptsession) | `capture/CaptureProfile.kt` (fertig — nur lesen!), `DelayCamEngine.kt`, `MainActivity.kt`, Manifest |

## Threads (Plan §2)

| Thread | Besitzer | Aufgabe |
|---|---|---|
| `camera` HandlerThread | CameraController | Camera2-Callbacks |
| `encoder-out` HandlerThread | VideoEncoder | async MediaCodec-Callback → RingBuffer.append |
| `playback` HandlerThread | PlaybackController | Sample-Auswahl, Timing, Decoder füttern |
| `decoder-out` HandlerThread | VideoDecoder | async Callback → render/drop |
| Main | UI | Compose, Gesten, Zeichnen |

**Harte Regel:** keine Objekt-Allokation im Steady-State von `encoder-out`, `playback`, `decoder-out`
(Handler-Messages aus dem Message-Pool sind okay). Alle Arrays/Buffer im Konstruktor bzw. in
`start()`/`configure()` anlegen. Kein `runBlocking`.

---

## 1. `buffer/EncodedRingBuffer.kt`  (Agent: buffer)

Ein Schreiber (`encoder-out`), Leser auf `playback` + kurzzeitig Export-Thread.

```kotlin
class EncodedRingBuffer(val capacityBytes: Int, val maxSamples: Int) {
    // Writer-Seite (nur encoder-out-Thread):
    fun setCsd(csd0: java.nio.ByteBuffer, csd1: java.nio.ByteBuffer?)
    fun append(data: java.nio.ByteBuffer, info: android.media.MediaCodec.BufferInfo)
    // Beide Seiten:
    fun clear()
    fun isEmpty(): Boolean
    fun csd0(): java.nio.ByteBuffer?   // Kopie; null solange nicht gesetzt
    fun csd1(): java.nio.ByteBuffer?
    // Leser-Seite:
    fun oldestSeq(): Long              // -1 wenn leer
    fun newestSeq(): Long              // -1 wenn leer
    fun oldestPtsUs(): Long            // -1 wenn leer
    fun newestPtsUs(): Long            // -1 wenn leer
    fun durationUs(): Long             // 0 wenn leer
    fun ptsAt(seq: Long): Long         // -1 wenn seq verdrängt/ungültig
    fun isKeyframe(seq: Long): Boolean // false wenn ungültig
    fun seqOfKeyframeAtOrBefore(ptsUs: Long): Long  // -1 wenn keiner
    fun seqAtOrAfter(ptsUs: Long): Long             // erster seq mit pts >= ptsUs, sonst -1
    fun copySampleInto(seq: Long, dst: java.nio.ByteBuffer): Int  // Bytes; -1 wenn seq verdrängt
    // Statistik (Debug/Settings-Anzeige):
    fun usedBytes(): Int
    fun sampleCount(): Int
}
```

Semantik (bindend):
- **seq** ist eine ab 0 fortlaufende Nummer über die Lebenszeit; Slot = `(seq % maxSamples)`.
  Verdrängte seqs bleiben für immer ungültig → Leser erkennt Überholt-Werden an `-1`.
- **Eine** `ByteBuffer.allocateDirect(capacityBytes)`-Allokation im Konstruktor; Index-Arrays
  (`IntArray offsets/sizes`, `LongArray ptsUs`, `BooleanArray keyflag`) ebenfalls vorab. Danach nie wieder allozieren.
- `append` kopiert `info.size` Bytes ab `info.offset`. Samples werden **nie gesplittet**: reicht der
  Platz bis zum Pufferende nicht, wird ab 0 geschrieben (Verschnitt am Ende gehört buchhalterisch zum belegten Bereich).
- **GOP-Verdrängung:** Ist zu wenig Platz (Bytes oder Slots), werden vom alten Ende so lange Samples
  entfernt, bis das älteste verbleibende wieder ein Keyframe ist — also immer ganze GOPs. Invariante:
  das älteste Sample im Puffer ist ein Keyframe (sobald der erste Keyframe geschrieben wurde).
- Ein Sample größer als `capacityBytes` → still verwerfen + `Log.w`, kein Throw.
- `info.flags & BUFFER_FLAG_CODEC_CONFIG` wird **nicht** appended (macht der Encoder-Callback; hier defensiv ignorieren).
- pts sind streng monoton steigend → binäre Suche für die pts-Lookups erlaubt (über gültigen seq-Bereich).
- **copySampleInto**, überhol-sicher ohne Lock ums Kopieren:
  1) unter Lock `offset/size` lesen + prüfen, dass seq gültig; 2) ohne Lock kopieren;
  3) unter Lock erneut `seq >= oldestSeq()` prüfen — sonst `-1` (Daten könnten überschrieben sein).
  Konvention: Methode macht `dst.clear()`, schreibt, `dst.flip()`.
- Lock: ein Monitor nur um Index-Buchhaltung; Kopien laufen außerhalb (Plan §3 Regel 1).

## 2. `capture/CaptureProfile.kt` — FERTIG, nur lesen

`CaptureProfile(label,width,height,fps,bitrate,bufferSeconds)` mit `bufferBytes`, `maxSamples`,
`frameDurationUs`, `LADDER`, `byLabel(..)`, `selectBest(context, requestedLabel, bufferSeconds)`.

## 3. `capture/VideoEncoder.kt`  (Agent: capture)

```kotlin
class VideoEncoder(
    private val profile: CaptureProfile,
    private val ring: EncodedRingBuffer,
    private val onError: (String) -> Unit,
) {
    fun start(): android.view.Surface  // Codec konfigurieren, InputSurface erzeugen, Codec starten
    fun stop()                          // idempotent; Codec + HandlerThread freigeben
    fun setVideoBitrate(bps: Int)       // PARAMETER_KEY_VIDEO_BITRATE (Thermal-Drosselung)
}
```

- Format (Plan §3): `video/avc`, `COLOR_FormatSurface`, `KEY_BIT_RATE=profile.bitrate`,
  `KEY_BITRATE_MODE=CBR`, `KEY_FRAME_RATE=profile.fps`, `setFloat(KEY_I_FRAME_INTERVAL, 0.5f)`,
  `KEY_PRIORITY=0`, `KEY_OPERATING_RATE=profile.fps`.
- Async-Callback auf eigenem HandlerThread `encoder-out`.
  `onOutputFormatChanged` → `csd-0`/`csd-1` aus dem Format → `ring.setCsd(...)`.
  `onOutputBufferAvailable` → CODEC_CONFIG überspringen, sonst `ring.append(...)`; immer `releaseOutputBuffer(idx, false)`.
- Annahme (kommentieren): Surface-Encoder liefern keine B-Frames → pts-Reihenfolge == seq-Reihenfolge.

## 4. `capture/CameraController.kt`  (Agent: capture)

```kotlin
class CameraController(private val context: android.content.Context) {
    @Volatile var sensorOrientation: Int = 0   // nach open() gültig
        private set
    fun open(
        profile: CaptureProfile,
        encoderSurface: android.view.Surface,
        previewSurface: android.view.Surface?,   // Mini-Preview, optional
        sportExposure: Boolean,
        onError: (String) -> Unit,
    )
    fun close()                                  // idempotent
    fun setSportExposure(on: Boolean)            // nur Request neu + setRepeatingRequest
}
```

- Rückkamera = erste `LENS_FACING_BACK`. Eigener HandlerThread `camera`.
- `TEMPLATE_RECORD`-Request: `CONTROL_AE_TARGET_FPS_RANGE = Range(fps,fps)`,
  `CONTROL_AE_ANTIBANDING_MODE_50HZ`, `CONTROL_VIDEO_STABILIZATION_MODE_OFF`,
  `CONTROL_AF_MODE_CONTINUOUS_VIDEO`.
- Sport-Belichtung: `CONTROL_AE_MODE_OFF` + `SENSOR_EXPOSURE_TIME = 2_000_000` ns (1/500 s)
  + `SENSOR_SENSITIVITY = 800` (beides auf die Geräte-Ranges clampen).
- Session via `SessionConfiguration`. Alle Fehler → `onError("Klartext deutsch")`, kein Crash.
- Kamera-Disconnect/Error-Callbacks ebenfalls → onError.

## 5. `playback/VideoDecoder.kt`  (Agent: playback)

```kotlin
class VideoDecoder(private val ring: EncodedRingBuffer) {
    interface Listener {
        fun onFrame(ptsUs: Long, bufferIndex: Int)  // läuft auf decoder-out!
        fun onDecoderError(msg: String)
    }
    fun configure(
        surface: android.view.Surface,
        width: Int, height: Int,
        rotationDegrees: Int,          // MediaFormat.KEY_ROTATION
        listener: Listener,
    )
    fun start()
    fun flush()                        // + interne Free-Index-Listen zurücksetzen
    fun stop()                         // idempotent, Thread freigeben
    fun canQueue(): Boolean            // freier Input-Buffer vorhanden?
    fun queueSample(seq: Long): Boolean // false: kein Input frei ODER Sample verdrängt
    fun render(bufferIndex: Int, renderAtNs: Long)  // releaseOutputBuffer(idx, renderAtNs)
    fun drop(bufferIndex: Int)                      // releaseOutputBuffer(idx, false)
}
```

- Format aus `ring.csd0()/csd1()` + Größe; `KEY_ROTATION=rotationDegrees`;
  `KEY_LOW_LATENCY=1` wenn API ≥ 30 und der Codec das Feature kann.
- Async-Callback auf eigenem HandlerThread `decoder-out`. Freie Input-Indizes in vorab
  allozierter Int-Ringliste verwalten (kein `ArrayDeque<Integer>`-Autoboxing im Hot-Path).
- `queueSample`: `ring.copySampleInto(seq, inputBuffer)`; bei `-1` → false. `queueInputBuffer(pts, 0)`.
- `onOutputBufferAvailable` → `listener.onFrame(info.presentationTimeUs, index)`. Rendern/Verwerfen
  entscheidet ausschließlich der PlaybackController (Policy dort, Mechanik hier).

## 6. `playback/PlaybackController.kt`  (Agent: playback)

```kotlin
enum class PlayMode { LIVE_DELAY, PAUSED, REPLAY, LOOP }

class PlaybackController(
    private val ring: EncodedRingBuffer,
    private val decoder: VideoDecoder,
) {
    // ---- UI-Snapshot: @Volatile-Primitive, von Compose per Frame gepollt (KEINE Objekte) ----
    @Volatile var modeOrdinal: Int = PlayMode.LIVE_DELAY.ordinal; private set
    @Volatile var delayUs: Long = 5_000_000; private set
    @Volatile var speedPermille: Int = 1000; private set        // 1000/500/250/100
    @Volatile var positionPtsUs: Long = -1; private set          // zuletzt gerenderter Frame
    @Volatile var bufOldestPtsUs: Long = -1; private set         // Spiegel für die Timeline
    @Volatile var bufNewestPtsUs: Long = -1; private set
    @Volatile var loopAPtsUs: Long = -1; private set
    @Volatile var loopBPtsUs: Long = -1; private set
    @Volatile var warmingUp: Boolean = true; private set         // Puffer < Delay
    @Volatile var hintCaughtUpUntilMs: Long = 0; private set     // „Puffer aufgeholt“ zeigen bis SystemClock.uptimeMillis

    // ---- Lebenszyklus (Engine) ----
    fun start(surface: android.view.Surface, width: Int, height: Int, rotationDegrees: Int)
    fun stop()   // decoder stoppen, Thread beenden, idempotent

    // ---- Kommandos (beliebiger Thread; intern auf playback-Handler serialisiert) ----
    fun setDelayUs(us: Long)          // in LIVE_DELAY: Seek auf newest−delay; sonst nur Sollwert
    fun freeze()                      // → PAUSED (aus jedem Modus)
    fun play(speedPermille: Int)      // PAUSED→REPLAY; in REPLAY/LOOP: nur Speed
    fun setSpeed(speedPermille: Int)  // ohne Flush: nur Anker neu setzen
    fun stepFrame(direction: Int)     // ±1, nur in PAUSED
    fun scrubTo(ptsUs: Long)          // → PAUSED, Frame anzeigen
    fun scrubBy(deltaUs: Long)        // relativ zu positionPtsUs
    fun setLoop(aPtsUs: Long, bPtsUs: Long)  // sortiert/clampt, min 300 ms → LOOP
    fun clearLoop()                   // LOOP→REPLAY (weiterlaufen) bzw. Marker löschen
    fun goLive()                      // → LIVE_DELAY mit aktuellem delayUs
}
```

Kern-Mechanik (bindend, Plan §4):

- **Pacing:** `renderAtNs = anchorSystemNs + (framePtsUs − anchorPtsUs) * 1000L * 1000 / speedPermille`.
  Anker (`anchorSystemNs = System.nanoTime() + 50 ms Vorlauf`, `anchorPtsUs`) wird gesetzt bei:
  Seek, Moduswechsel, Speedwechsel (dort auf aktueller Position, **kein Flush**).
- **Ausgabe-Flow:** `onFrame(pts, idx)` legt (pts, idx) in eine vorab allozierte Halteliste (max 6).
  Eine Pump-Schleife auf dem playback-Handler (Message alle ~4 ms, nur solange aktiv) gibt Frames
  frei, sobald `renderAtNs − now ≤ 50 ms` → `decoder.render(idx, renderAtNs)`. Frames mit
  `pts < seekZielPts` → `decoder.drop(idx)` (Vordekodieren beim Seek). Backpressure: ist die
  Halteliste voll, wird kein Input mehr gequeued.
- **Input-Feeding:** dieselbe Pump-Schleife hält den Decoder 2–4 Samples voraus (`nextFeedSeq`),
  solange `decoder.canQueue()` und Halteliste nicht voll.
- **Seek(targetPts):** `decoder.flush()` → `seq = ring.seqOfKeyframeAtOrBefore(targetPts)`
  (Fallback `oldestSeq`) → feeden; alles mit `pts < targetPts` droppen; erster Frame ≥ target wird
  sofort gerendert (`renderAtNs = now`), Anker setzen.
- **LIVE_DELAY:** Ziel `newestPts − delayUs`. Beim Start/Delay-Erhöhung: solange
  `durationUs < delayUs` → `warmingUp = true`, Wiedergabe klemmt am ältesten Frame (der neueste
  sichtbare Stand ist dann eben jünger verzögert), sobald genug gepuffert → normaler Betrieb.
  **Drift:** hinkt der zuletzt gerenderte Frame > 300 ms hinter `newest − delay` her, oder liefert
  `copySampleInto` `-1` (überholt): Seek nach vorn auf Keyframe ≤ `newest − delay`,
  `hintCaughtUpUntilMs = uptimeMillis() + 2000`.
- **REPLAY:** erreicht die Wiedergabe bei `speedPermille ≥ 1000` die Live-Kante
  (`pts ≥ newest − delayUs`), nahtlos in LIVE_DELAY übergehen (Anker behalten, kein Sprung).
- **LOOP:** bei `pts ≥ loopB` → Seek auf `loopA` (Anker neu). Loop funktioniert mit jeder Speed.
- **stepFrame:** −1 = `scrubTo(ptsAt(seqVonPosition − 1))`; +1 = nächstes Sample feeden, sofort rendern.
- `bufOldestPtsUs`/`bufNewestPtsUs` bei jedem Pump-Tick aus dem Ring spiegeln.
- Decoder-Fehler → einmaliger Reconfigure-Versuch (flush/stop/configure/start), sonst Fehler
  nach oben (Engine-Callback über `onPlaybackError: (String)->Unit` im Konstruktor — optionaler
  zweiter Konstruktorparameter mit Default `{}` ist erlaubt).

## 7. `playback/Timeline.kt`  (Agent: playback)

```kotlin
object Timeline {
    fun ptsToFraction(ptsUs: Long, oldestUs: Long, newestUs: Long): Float  // 0f..1f, clamped
    fun fractionToPts(fraction: Float, oldestUs: Long, newestUs: Long): Long
    fun formatSeconds(us: Long): String       // "12,3 s"  (deutsches Komma)
    fun formatDelayBadge(us: Long): String    // "−5,0 s"
    fun formatClock(us: Long): String         // "1:23,4" (min:sek,zehntel) für Timeline-Beschriftung
}
```

## 8. `DelayCamEngine.kt` — implementiert der Integrator; UI baut gegen diese API

```kotlin
class DelayCamEngine(private val context: android.content.Context) {
    val ring: EncodedRingBuffer            // nach start() gültig
    val playback: PlaybackController       // nach start() gültig
    @Volatile var profile: CaptureProfile  // aktuell aktives Profil
    @Volatile var errorMessage: String?    // Klartext für UI-Banner, null = alles ok
    @Volatile var thermalNote: String?     // z. B. "Hitze: Bitrate reduziert", null = ok
    @Volatile var videoAspect: Float       // Breite/Höhe fürs Letterboxing (nach Rotation!)
    fun start()                            // Settings lesen, Profil wählen, Pipeline aufbauen
    fun stop()
    fun setVideoSurface(surface: android.view.Surface?, width: Int, height: Int)
    fun setPreviewSurface(surface: android.view.Surface?)
    fun applySettings(s: AppSettings)      // Profil-/Puffer-/Preview-Änderung ⇒ interner Restart
    fun saveClip(aPtsUs: Long, bPtsUs: Long, slowmoPermille: Int, onResult: (kotlin.Result<android.net.Uri>) -> Unit)
}
```

## 9. UI  (Agent: ui) — Dateien unter `ui/`

- `DelayCamScreen.kt` — Wurzel-Composable `DelayCamScreen(engine: DelayCamEngine, settings: MutableState<AppSettings>)`:
  - Vollbild-Video: `AndroidView { SurfaceView }`, `SurfaceHolder.Callback` → `engine.setVideoSurface(...)`.
    Letterbox: Box mit schwarzem Grund; Video-Box misst sich an `engine.videoAspect`.
  - Mini-Preview oben rechts (ca. 160×90 dp, nur wenn `settings.miniPreview`): zweite kleine
    SurfaceView mit `setZOrderMediaOverlay(true)` → `engine.setPreviewSurface(...)`.
  - Zustands-Polling: `LaunchedEffect { while(isActive) { withFrameNanos {}; lokale States aus
    engine.playback-@Volatile-Feldern aktualisieren } }` — keine Flows im Frame-Takt.
  - Auto-Hide: Controls (ControlBar+TimelineBar) nach 4 s ohne Interaktion ausblenden; Tap blendet ein.
    StatusBadge bleibt immer sichtbar.
  - Onboarding-Overlay bei `!settings.firstRunDone` (3 Sätze, Button „Los geht’s“ → Setting speichern).
  - Fehler-Banner wenn `engine.errorMessage != null` (+ „Neu starten“-Button → engine.stop()/start()).
  - Zeichen-Werkzeugleiste rechts; Zeichnen nur wenn Bild steht (PAUSED/LOOP) ODER Nutzer aktiviert
    Stift explizit → dann `playback.freeze()` falls `settings.freezeOnDraw`.
  - Bei Wechsel zu LIVE: DrawingOverlay leeren, falls `settings.clearDrawingOnLive`.
- `GestureLayer.kt` — `Modifier`-/Composable-Schicht über dem Video (unter den Controls):
  Tap: in LIVE_DELAY → `freeze()`; sonst Controls ein/aus. Horizontal ziehen → `scrubBy`:
  56 dp ≈ 1 s, bei Geschwindigkeit > 2000 dp/s Faktor 3. Doppeltap links/rechts → `scrubBy(∓2 s)`.
  Long-Press → `loopA = position`, weiterziehen setzt B, loslassen → `setLoop(a,b)`.
  2-Finger-Tap → `goLive()` (funktioniert überall, auch bei aktivem Stift).
  Bei aktivem Zeichenstift: alle 1-Finger-Gesten deaktiviert (Touches gehören dem Overlay).
- `ControlBar.kt` — untere Leiste: Delay-Zeile (Slider 0..min(30 s, Puffer), Presets 0/3/5/8/12/20 s,
  ±0,5 s), Speed-Buttons [1×][½×][¼×][⅒×], Einzelbild [◀|][|▶], [A][B][Loop aus],
  **LIVE-Button als größtes Element** (min. 120×64 dp, Signalfarbe). Touch-Targets ≥ 64 dp,
  Schrift ≥ 16 sp, `performHapticFeedback` bei jedem Moduswechsel.
- `TimelineBar.kt` — `Canvas`: Pufferbereich als Balken, Playhead, A/B-Marker, LIVE-Kante rechts;
  Tap/Drag → `scrubTo(fractionToPts(...))`. Höhe ≥ 48 dp. Nutzt `Timeline`-Helfer.
- `StatusBadge.kt` — oben links, immer sichtbar: „● LIVE −5,0 s“ / „⏸ PAUSE“ / „▶ REPLAY ¼×“ /
  „⟳ LOOP 3,2 s ¼×“; darunter klein: „Puffer aufgeholt“ (solange `hintCaughtUpUntilMs > now`),
  `engine.thermalNote`, „Puffer füllt sich…“ bei `warmingUp`.
- `SettingsSheet.kt` — von rechts einschiebbares Panel (Zahnrad-Button + Edge-Swipe):
  Profil (Auto + 4 Labels), Pufferlänge 30/60/120 s, Sport-Belichtung, Mini-Preview,
  „Zeichnen friert ein“, „LIVE löscht Zeichnung“, Button „Clip A–B exportieren“
  (ruft `engine.saveClip(loopA, loopB, speedPermille, ...)`, nur aktiv wenn A/B gesetzt;
  Ergebnis als Snackbar/Toast mit Teilen-Intent), Button „Geräte-Check anzeigen“ → `ProbeScreen()`
  als Vollbild-Overlay mit Zurück-Button. Änderungen → `AppSettings.save` + `engine.applySettings`.
- `ui/drawing/DrawingOverlayView.kt` — klassische View (KEIN Compose-Canvas):
  `requestUnbufferedDispatch(event)` bei ACTION_DOWN, `LAYER_TYPE_HARDWARE`.
  Werkzeuge: FREIHAND, LINIE, PFEIL, KREIS (Ellipse), RECHTECK. 5 Farben (Weiß, Gelb, Rot, Cyan,
  Grün), 3 Strichbreiten (3/6/10 dp). S-Pen: `TOOL_TYPE_STYLUS` → Druck skaliert Strichbreite
  ×0,6–×1,6 (nur Freihand). Pfeilspitze: zwei Schenkel im 30°-Winkel, Länge = 4×Strichbreite + 12 dp.
  API: `setTool/setColor/setStrokeWidthDp`, `undo()`, `redo()`, `clearAll()`,
  `var enabled: Boolean`, `var onStrokeStarted: (() -> Unit)?`.
- `ui/drawing/DrawingToolbar.kt` — Compose-Spalte rechts: Stift-Toggle, Werkzeuge, Farben,
  Breiten, Undo/Redo/Löschen. Nur sichtbar, wenn Bild steht oder Stift aktiv.

Bindungsregel: UI spricht **nur** mit `DelayCamEngine`, `PlaybackController`-Kommandos/-Feldern,
`AppSettings` und `Timeline`. Keine Direktzugriffe auf Ring/Decoder/Camera.

## 10. Aux  (Agent: aux)

`AppSettings.kt` (Package-Root):
```kotlin
data class AppSettings(
    val profileMode: String = "auto",       // "auto" | "1080p60" | "1080p30" | "720p60" | "720p30"
    val bufferSeconds: Int = 60,            // 30/60/120
    val sportExposure: Boolean = false,
    val miniPreview: Boolean = true,
    val freezeOnDraw: Boolean = true,
    val clearDrawingOnLive: Boolean = true,
    val delayUs: Long = 5_000_000,          // zuletzt genutzter Delay
    val firstRunDone: Boolean = false,
) {
    fun save(context: android.content.Context)
    companion object { fun load(context: android.content.Context): AppSettings }
}
```
SharedPreferences-Name `"delaycam"`, apply() reicht.

`device/ThermalWatcher.kt`:
```kotlin
class ThermalWatcher(
    private val context: android.content.Context,
    private val onStatus: (Int) -> Unit,   // PowerManager.THERMAL_STATUS_*, auf Main-Executor
) { fun start(); fun stop() }
```

`export/ClipExporter.kt`:
```kotlin
object ClipExporter {
    fun export(
        context: android.content.Context,
        ring: EncodedRingBuffer,
        profile: CaptureProfile,
        aPtsUs: Long, bPtsUs: Long,
        slowmoPermille: Int,               // 1000 = Echtzeit; 250 ⇒ Datei läuft 4× so lang
        onResult: (kotlin.Result<android.net.Uri>) -> Unit,   // auf Main-Thread
    )
}
```
- Eigener kurzlebiger Thread. Start beim Keyframe ≤ A (`seqOfKeyframeAtOrBefore`), Ende bei pts ≥ B.
- `MediaFormat` aus `ring.csd0()/csd1()` + Profilgröße; `MediaMuxer` MP4;
  `pts' = (pts − basePts) * 1000L / slowmoPermille` (Slowmo durch PTS-Streckung, kein Re-Encode).
- Keyframe-Flag in `BufferInfo` korrekt setzen. Ziel: `MediaStore.Video`, RELATIVE_PATH
  `Movies/DelayCam`, `IS_PENDING`-Flow, Dateiname `delaycam_yyyyMMdd_HHmmss.mp4`.
- Samples per `copySampleInto` in einen einmalig allozierten Direct-Buffer (Größe ~2 MB) holen;
  liefert ein Sample `-1` (verdrängt), Export mit Fehler „Puffer hat den Bereich überschrieben“ abbrechen.

## 11. Rotation (Info für playback/ui)

Der Integrator berechnet `rotationDegrees = (sensorOrientation − displayRotation + 360) % 360`
und reicht ihn an `PlaybackController.start(...)` durch; `videoAspect` in der Engine ist bereits
rotationsbereinigt. Activity ist auf eine Landscape-Richtung gesperrt.
