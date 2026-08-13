# Delay-Cam — Umsetzungsplan (Vorlage für Claude Code)

**Ziel:** Native Android-App für ein Samsung-Tablet, die dauerhaft filmt und das Bild **zeitverzögert** anzeigt (einstellbare Verzögerung). Zusätzlich: bis zu 2 Minuten zurückspulen, Aktion in Slowmotion wiederholen, Bereich loopen, ins Bild zeichnen. Ergebnis: installierbare **APK** (Sideload, kein Play Store).

Typischer Einsatz: Trainer/Sportler sieht sich direkt nach der Aktion selbst — ohne Bedienung durch eine zweite Person.

---

## 1. Technologie-Entscheidung

| | Wahl | Begründung |
|---|---|---|
| Plattform | **Native Android**, Kotlin | Zwei parallele Hardware-Codecs + frame-genaues Timing gibt es nur nativ |
| Min SDK | 29 (besser 31) | `MediaFormat.KEY_LOW_LATENCY` ab 30, moderne Codec-APIs |
| Kamera | **Camera2** | Direkter Zugriff auf die Encoder-Input-Surface + feste FPS-Range (siehe Risiken) |
| Encode/Decode | **MediaCodec** (H.264/AVC, Hardware) | Zwei Instanzen: 1× Encoder (Kamera → Speicher), 1× Decoder (Speicher → Bildschirm) |
| Puffer | **Ring-Buffer im RAM** (encodierte Samples) | Kein Disk-I/O, sofortiger Random-Access, kein Dateisystem-Müll |
| Anzeige | `SurfaceView` (Decoder rendert direkt darauf) | Kein Bitmap-Umweg, niedrigste Latenz |
| UI | Jetpack Compose für Controls, `AndroidView` für die `SurfaceView` | Schnelle Iteration am UI |
| Zeichenebene | eigene `View` (kein Compose Canvas) | Low-Latency-Stylus/S-Pen via `requestUnbufferedDispatch()` |
| Audio | **weggelassen** | Für einen Delay-Trainer irrelevant, spart Sync-Komplexität. Optional in Phase 7 |

**Bewusst verworfen:**

- **Web/PWA:** `MediaRecorder` + MSE kann auf Android-Chrome keine 2 Minuten frame-genau puffern, keine kontrollierte Abspielrate, kein verlässlicher Vollbild-/Kiosk-Betrieb, keine APK.
- **Flutter/React Native:** Der ganze schwierige Teil müsste trotzdem als Kotlin-Plugin geschrieben werden — nur Zusatzkomplexität.
- **Frames als Bitmaps puffern:** 1080p YUV = ~3 MB/Frame → 2 Min bei 30 fps ≈ 11 GB. Unmöglich. Deshalb wird der **encodierte** Stream gepuffert.
- **ExoPlayer für den Live-Delay-Pfad:** kein Zugriff auf Frame-Pacing/Delay-Offset. (Für gespeicherte Clips später gern.)

**Referenz-Implementierung zum Abschauen:** Grafikas `ContinuousCaptureActivity` / `CircularEncoderBuffer` (Andy McFadden, google/grafika) — genau dieses Ring-Buffer-Prinzip, nur mit Camera1 und ohne Playback. Als Orientierung lesen, nicht kopieren.

---

## 2. Architektur

```
 ┌──────────┐   Surface   ┌───────────────┐  encodierte Samples  ┌──────────────┐
 │ Camera2  ├────────────►│ MediaCodec    ├─────────────────────►│ RingBuffer   │
 │ (RECORD) │             │ Encoder H.264 │  (H.264 + PTS + KF)  │ ~120 s, RAM  │
 └────┬─────┘             └───────────────┘                      └──────┬───────┘
      │ 2. Output (optional)                                            │
      ▼                                                                 │ read(pts)
 ┌──────────────┐                                            ┌────────────────────┐
 │ Mini-Preview │                                            │ PlaybackController │
 │ (Ausrichten) │                                            │ Mode/Speed/Loop/A-B│
 └──────────────┘                                            └─────────┬──────────┘
 ┌─────────────────────┐      ┌──────────────────────┐                 │ queueInput
 │ DrawingOverlay View │      │  Compose Controls    │                 ▼
 │ (Pfeile, Kreise)    │      │  Slider/Timeline     │      ┌────────────────────┐
 └──────────┬──────────┘      └──────────┬───────────┘      │ MediaCodec Decoder │
            │                            │                  └─────────┬──────────┘
            └────────► z-Order über ◄────┘                            │ releaseOutputBuffer(idx, renderAtNs)
                       ┌──────────────────────────────────────────────▼──┐
                       │            SurfaceView (Vollbild)               │
                       └─────────────────────────────────────────────────┘
```

**Threads (strikt trennen):**

| Thread | Aufgabe |
|---|---|
| `camera` (HandlerThread) | Camera2-Callbacks, Session-Requests |
| `encoder-out` (async MediaCodec-Callback) | Samples in den RingBuffer schreiben |
| `playback` | Sample-Auswahl, Timing, Decoder füttern |
| `decoder-out` (async Callback) | `releaseOutputBuffer(idx, renderAtNs)` |
| Main/UI | Compose-State, Gesten, Zeichnen |

Kein `runBlocking` und keine Allokation in `encoder-out`/`playback`/`decoder-out`.

---

## 3. Ring-Buffer (das Herzstück)

```kotlin
class EncodedRingBuffer(capacityBytes: Int, maxSamples: Int) {
    private val data = ByteBuffer.allocateDirect(capacityBytes) // EINE Allokation, off-heap
    private val offsets = IntArray(maxSamples)
    private val sizes   = IntArray(maxSamples)
    private val ptsUs   = LongArray(maxSamples)
    private val keyFlag = BooleanArray(maxSamples)
    // head/tail + wrap-around
}
```

**Regeln:**

1. Ein Schreiber (`encoder-out`), ein Leser (`playback`). Kritische Abschnitte klein halten (Lock nur um Index-Updates, nicht um Kopiervorgänge).
2. **Beim Verdrängen immer ganze GOPs löschen**, damit das älteste verbleibende Sample immer ein Keyframe ist. Sonst ist der Pufferanfang nicht dekodierbar.
3. `csd-0`/`csd-1` (SPS/PPS) separat speichern — nötig für Decoder-Konfiguration und für den MP4-Export.
4. API: `oldestPtsUs()`, `newestPtsUs()`, `indexOfKeyframeAtOrBefore(ptsUs)`, `copySampleInto(index, dst)`, `sampleAfter(index)`.
5. Wenn der Leser von der Schreibposition überholt wird: nach vorn auf den nächsten Keyframe klemmen und im UI kurz „Puffer aufgeholt" anzeigen.

**Dimensionierung (Bitrate ÷ 8 × Sekunden × 1,2 Reserve):**

| Profil | Bitrate | 60 s | 120 s |
|---|---|---|---|
| 720p60 | 10 Mbit/s | ~90 MB | ~180 MB |
| 1080p30 | 12 Mbit/s | ~108 MB | ~216 MB |
| 1080p60 | 16 Mbit/s | ~144 MB | ~288 MB |

→ Puffersekunden (30/60/120) und Profil in den Einstellungen; Default **1080p60 / 60 s**, weil 60 fps die Slowmotion deutlich besser macht. Tablet sollte ≥ 8 GB RAM haben; sonst 720p60.

**Encoder-Format:**

```
MIME              video/avc
KEY_COLOR_FORMAT  COLOR_FormatSurface
KEY_BIT_RATE      s. Tabelle,  KEY_BITRATE_MODE = CBR   (planbarer Speicherbedarf)
KEY_FRAME_RATE    30 oder 60
KEY_I_FRAME_INTERVAL  0.5f   (setFloat, API 25+; sonst 1)  → feine Seek-Granularität
KEY_PRIORITY      0 (realtime),  KEY_OPERATING_RATE = fps
```

Kurzes GOP (0,5 s) ist hier wichtiger als Bitrate-Effizienz: Es bestimmt, wie schnell Scrubben und Loop-Sprünge reagieren.

---

## 4. Playback-Engine

**Zustandsmaschine:**

```
LIVE_DELAY ──tap──► PAUSED ──play(speed)──► REPLAY ──A+B gesetzt──► LOOP
     ▲                 │  ◄──────────────────┘                        │
     └──── „LIVE" ─────┴──────────────────────────────────────────────┘
                    (2-Finger-Tap oder Button, immer erreichbar)
```

**Delay:** `targetPts = newestPts − delayUs`. Mehr ist es nicht — der Puffer enthält alles, der Delay ist nur ein Leseoffset. Slider 0–30 s, Presets 0 / 3 / 5 / 8 / 12 / 20 s, Feinjustierung ±0,5 s.

**Seek (bei Delay-Änderung, Scrub, Loop-Sprung):**
1. `decoder.flush()`
2. `idx = indexOfKeyframeAtOrBefore(targetPts)`
3. Samples füttern; alle Frames mit `pts < targetPts` mit `releaseOutputBuffer(idx, false)` **verwerfen** (nicht rendern)
4. Ab `targetPts` rendern, neuen Timing-Anker setzen

**Frame-Pacing — der entscheidende Trick:** Nicht selbst `sleep()`, sondern dem System sagen, *wann* ein Frame erscheinen soll:

```kotlin
val renderAtNs = anchorSystemNs + ((framePtsUs - anchorPtsUs) * 1000L / speed).toLong()
codec.releaseOutputBuffer(index, renderAtNs)   // vsync-genau, kein Ruckeln
```

Daraus folgt fast alles kostenlos:
- **Slowmotion** = `speed = 0.5 / 0.25 / 0.1` (kein Re-Encode, keine Interpolation)
- **Einzelbild** = ein Frame füttern, sofort rendern
- **Rückwärts-Scrub** = Seek zum Keyframe + gezieltes Weiterdekodieren (nicht flüssig rückwärts abspielen — bewusst so, echtes Reverse-Play wäre teuer)
- **Ratenwechsel** = nur Anker neu setzen, **kein** Flush

**Drift-Kontrolle in LIVE_DELAY:** Läuft die Ausgabe mehr als ~300 ms hinter dem Soll-Delay, auf den nächsten Keyframe nach vorn springen. Ist der Puffer zu knapp (Start der App), warten, bis `newestPts − oldestPts > delay`.

**Decoder:** async Callbacks, `KEY_LOW_LATENCY = true` (API 30+), konfiguriert mit den gespeicherten `csd`-Buffern, Output direkt auf die `SurfaceView`-Surface.

---

## 5. UI/UX

**Rahmen:** Landscape gesperrt, Immersive Mode, `keepScreenOn`, dunkles Theme, Controls nach 4 s ausblenden (Tap = einblenden).

```
┌────────────────────────────────────────────────────────────────┐
│  ◉ LIVE −5,0 s                                    [Mini-Prev]  │  ← Statusbadge + kleine
│                                                                │    Live-Vorschau zum Ausrichten
│                      verzögertes Vollbild                      │
│                                                            ┌──┐│
│                                                            │✏️││  ← Stift-Werkzeuge
│                                                            │↩️││    (nur wenn Bild steht)
│                                                            └──┘│
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ ├──A═══════╡▮╞═════════B──────────────────────┤ LIVE     │  │  ← Timeline: gesamter Puffer,
│  └──────────────────────────────────────────────────────────┘  │    Playhead, A/B-Marker
│  Delay ◄ 5,0 s ►   [1×][½×][¼×][⅒×]  [◀|] [|▶]  [A|B] [LIVE]  │
└────────────────────────────────────────────────────────────────┘
```

**Gesten (alles mit einer Hand, ohne Hinsehen):**

| Geste | Aktion |
|---|---|
| Tap ins Bild | Bild einfrieren → Replay-Modus |
| Horizontal ziehen | Scrubben (1 Fingerbreite ≈ 1 s, mit Beschleunigung) |
| Doppeltap links/rechts | −2 s / +2 s |
| Long-Press | A-Marker setzen, Ziehen bis B → Loop startet sofort |
| 2-Finger-Tap | zurück zu LIVE (überall verfügbar) |
| Swipe von rechts | Einstellungen |

**Regeln:** Touch-Targets ≥ 64 dp, kein Text unter 16 sp, Kontrast für Hallenlicht, kurze Haptik bei jedem Moduswechsel, Statusbadge immer sichtbar („LIVE −5,0 s" / „REPLAY ¼×" / „LOOP 3,2 s"). Der **LIVE-Button ist der größte Button im UI** — der häufigste Wunsch ist „zurück zum Normalzustand".

---

## 6. Zeichnen

- Eigene `View` über der `SurfaceView`, `Path`-Liste, Hardware-Layer, `requestUnbufferedDispatch()` für Stylus-Latenz; `MotionEvent.TOOL_TYPE_STYLUS` + Druck auswerten (S Pen).
- Werkzeuge: Freihand, Linie, **Pfeil**, Kreis/Ellipse, Rechteck. 5 Farben, 3 Strichstärken, Undo/Redo, Alles löschen.
- Zeichnen friert das Bild automatisch ein (abschaltbar). Beim Zurückkehren zu LIVE wird die Zeichnung geleert (abschaltbar).
- **Stretch:** Zeichnung an die Frame-PTS binden, damit sie beim Loopen an derselben Stelle wieder erscheint.

---

## 7. Speichern & Export (Phase 7, optional)

- „Clip sichern": Bereich `[A,B]` aus dem RingBuffer per **`MediaMuxer` remuxen** — ab dem Keyframe vor A, PTS auf 0 normieren. Kein Re-Encode, dauert Millisekunden.
- **Slowmo-Export ohne Re-Encode:** beim Schreiben `pts * (1/speed)` — ergibt eine echte Slowmo-Datei.
- Ziel: `MediaStore` → `Movies/DelayCam/`, danach Share-Intent.
- Zeichnung einbrennen erfordert GL + Re-Encode → bewusst Stretch-Goal.

---

## 8. Projektstruktur

```
app/src/main/java/de/<paket>/delaycam/
├── MainActivity.kt
├── capture/
│   ├── CameraController.kt        // Camera2, Session, feste FPS-Range, AE/AF
│   ├── VideoEncoder.kt            // MediaCodec Encoder + InputSurface
│   └── CaptureProfile.kt          // Auflösung/fps/Bitrate/Pufferlänge + Fallback-Leiter
├── buffer/
│   ├── EncodedRingBuffer.kt
│   └── SampleRef.kt
├── playback/
│   ├── PlaybackController.kt      // Zustandsmaschine, Delay, Speed, Loop, Drift
│   ├── VideoDecoder.kt            // MediaCodec Decoder + Pacing
│   └── Timeline.kt                // PTS ↔ Pixel, A/B-Marker
├── ui/
│   ├── DelayCamScreen.kt          // Compose
│   ├── TimelineBar.kt
│   ├── ControlBar.kt
│   ├── GestureLayer.kt
│   └── drawing/DrawingOverlayView.kt
├── device/
│   ├── CapabilityProbe.kt         // Codec-/Kamera-Fähigkeiten, max. Instanzen
│   └── ThermalWatcher.kt
└── export/ClipExporter.kt
```

---

## 9. Phasenplan (jede Phase abschließen und testen, bevor die nächste beginnt)

### Phase 0 — Setup & Geräte-Check
Leeres Projekt, Kotlin, Compose, Permissions (`CAMERA`). Ein Debug-Screen, der ausgibt: Kamera-Hardware-Level, verfügbare Auflösungen + FPS-Ranges, `MediaCodecInfo` für AVC-Encoder/Decoder inkl. `getMaxSupportedInstances()`, RAM, Android-Version.
**DoD:** Screen zeigt die Werte des echten Tablets; daraus wird das Default-Profil festgelegt.

### Phase 1 — Der Delay-Kern (die eigentliche Arbeit)
Camera2 → Encoder → RingBuffer → Decoder → SurfaceView mit **fest verdrahteten 5 s Delay**. Kein UI außer Vollbild.
**DoD:** 10 Minuten Dauerbetrieb bei 1080p, sichtbarer Delay 5 s ±100 ms, keine wachsende Speichernutzung (Profiler: flache Kurve), < 2 verworfene Frames pro Minute, `adb shell dumpsys gfxinfo` ohne Jank-Häufung. **Erst wenn das steht, weitergehen.**

### Phase 2 — Delay einstellbar
Slider + Presets, Live-Wechsel ohne Aussetzer, Drift-Kontrolle, Statusbadge, Puffer-Aufwärmphase beim Start.
**DoD:** Delay 0 → 20 s → 3 s ohne Schwarzbild und ohne Absturz; nach 30 Min noch stabil.

### Phase 3 — Pause, Scrub, Replay
Zustandsmaschine, Tap-Freeze, Scrub-Geste, Geschwindigkeiten 1× / ½× / ¼× / ⅒×, Einzelbild vor/zurück, Timeline-Leiste.
**DoD:** Aus LIVE tippen → Bild steht in < 100 ms; 2 Minuten zurückscrubben möglich; ¼× läuft ruckelfrei; LIVE-Button funktioniert aus jedem Zustand.

### Phase 4 — Loop
A/B setzen (Long-Press + Ziehen, oder Buttons), sichtbare Marker, sauberer Sprung von B nach A, Loop mit jeder Geschwindigkeit.
**DoD:** 2-Sekunden-Loop bei ¼× läuft 5 Minuten ohne Drift und ohne Speicherwachstum.

### Phase 5 — Zeichnen
Overlay, Werkzeuge, Undo, Auto-Clear, S-Pen-Druck.
**DoD:** Pfeil zeichnen während Loop-Pause; Zeichnung ruckelt nicht; Wechsel zu LIVE räumt auf.

### Phase 6 — Robustheit & Politur
Lifecycle (App in Hintergrund / Bildschirm aus / Anruf), Thermal-Watcher mit Abstufung, Fehlerzustände mit Klartextmeldung, Einstellungs-Screen (Profil, Pufferlänge, Sport-Belichtung, Auto-Clear), App-Icon, Onboarding in 3 Sätzen.
**DoD:** 90 Minuten Dauerbetrieb im Hallenszenario ohne Absturz; nach Hintergrund + Rückkehr wieder LIVE.

### Phase 7 — Optional
Clip-Export, Slowmo-Export, digitaler Zoom, zweite Kamera / Weitwinkel-Umschaltung, Audio, Zeichnung an Frames gebunden.

---

## 10. Harte Performance-Regeln

1. Keine Allokation in `encoder-out`, `playback`, `decoder-out` — alle Buffer/Arrays einmalig vorab anlegen.
2. Kein `Bitmap`, kein `ImageReader`, kein Pixel-Zugriff im Live-Pfad. Nur Surfaces.
3. Codecs nicht neu erzeugen für Delay-/Speed-/Loop-Änderungen. Neu konfiguriert wird nur bei Profilwechsel.
4. `SurfaceView`, nicht `TextureView` (weniger Latenz, kein zusätzlicher Compositing-Schritt).
5. Kamera-Bildstabilisierung (`CONTROL_VIDEO_STABILIZATION_MODE`) **aus** — kostet Latenz und beschneidet das Bild. HDR/Beauty-Modi aus.
6. Messen statt raten: Perfetto-Trace pro Phase, `dumpsys gfxinfo`, Memory-Profiler über ≥ 10 Min.
7. Zielwerte: Ende-zu-Ende-Zusatzlatenz (über den eingestellten Delay hinaus) < 150 ms, Speicher konstant, Akku-Temperatur unter Dauerlast beobachten.

---

## 11. Risiken & Gegenmaßnahmen

| Risiko | Gegenmaßnahme |
|---|---|
| **Hallenlicht → variable Bildrate** (ist bei bestehendem Handball-Setup bereits aufgetreten) | `CONTROL_AE_TARGET_FPS_RANGE` fest auf `Range(60,60)` bzw. `(30,30)`, `CONTROL_AE_ANTIBANDING_MODE = 50HZ`. Zusätzlich optionaler „Sport-Modus": `CONTROL_AE_MODE_OFF` + `SENSOR_EXPOSURE_TIME ≤ 1/500 s` + höherer ISO — scharfe Einzelbilder für die Slowmotion, dafür mehr Rauschen |
| Encoder+Decoder gleichzeitig nicht in 1080p60 möglich | Fallback-Leiter aus Phase 0: 1080p60 → 1080p30 → 720p60 → 720p30, automatisch + im UI anzeigen |
| Throttling nach langer Laufzeit in warmer Halle | `PowerManager.addThermalStatusListener`; ab `THROTTLING_MODERATE` Bitrate senken, ab `SEVERE` auf 720p30 wechseln und warnen. Tablet nicht in der Sonne/Tasche, nicht gleichzeitig schnellladen |
| Leser wird vom Schreiber überholt | Auf nächsten Keyframe klemmen, UI-Hinweis, kein Crash |
| RAM zu knapp / OOM | Puffergröße aus `ActivityManager.MemoryInfo` begrenzen; Direct-Buffer (off-heap), nicht Java-Heap |
| Lifecycle (Anruf, Screen-Lock) | Bei `onStop` Session + Codecs sauber freigeben, bei `onStart` neu aufbauen und in LIVE starten. Puffer verwerfen ist okay |
| Bedienung während des Trainings | Auto-Hide-Controls, riesiger LIVE-Button, Haptik, keine verschachtelten Menüs |

---

## 12. Build & Installation

```bash
# Debug-APK (Signatur egal, für Sideload völlig ausreichend)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release mit eigenem Keystore
keytool -genkey -v -keystore delaycam.jks -keyalg RSA -keysize 2048 -validity 10000 -alias delaycam
./gradlew assembleRelease
```

Auf dem Tablet: Entwickleroptionen aktivieren (7× auf Build-Nummer), USB-Debugging an. Ohne Kabel: APK per Datei-Freigabe kopieren und „Unbekannte Apps installieren" für die Datei-App erlauben.

---

## 13. Startprompt für Claude Code

> Lies `delay-cam-plan.md` komplett. Wir bauen die App phasenweise nach Abschnitt 9.
>
> Regeln für dich:
> - Arbeite **nur an einer Phase** und melde dich mit dem Ergebnis, bevor du die nächste beginnst.
> - Halte die Definition-of-Done der jeweiligen Phase ein und sag mir konkret, wie ich sie auf dem Tablet prüfe (Befehle, Beobachtung, Zahlen).
> - Halte die harten Performance-Regeln aus Abschnitt 10 ein. Wenn eine Regel im Weg steht, frag nach, statt sie stillschweigend zu brechen.
> - Weiche nicht von der Architektur in Abschnitt 2 ab, ohne es zu begründen und mich zu fragen.
> - Kommentiere die kniffligen Stellen (Ring-Buffer-Wrap, GOP-Verdrängung, Seek-Logik, Pacing-Berechnung) auf Deutsch.
>
> Starte mit **Phase 0**. Danach nenne mir die Werte, die der Geräte-Check ausgibt, und wir legen das Default-Profil zusammen fest.

---

**Zum Vergleich/Benchmarken der Bedienung:** Es gibt fertige Apps aus dem Sportbereich mit Delay-Funktion (z. B. „BaM Video Delay"). Sinnvoll, eine davon einmal auszuprobieren — nicht als Vorlage, sondern um zu sehen, welche Bedienschritte im Training tatsächlich schnell gehen müssen.
