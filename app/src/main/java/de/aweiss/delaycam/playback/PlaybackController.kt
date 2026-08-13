package de.aweiss.delaycam.playback

import android.util.Log
import android.view.Surface
import de.aweiss.delaycam.buffer.EncodedRingBuffer
import java.util.concurrent.atomic.AtomicInteger

enum class PlayMode { LIVE_DELAY, PAUSED, REPLAY, LOOP }

/**
 * Die Zustandsmaschine der Wiedergabe (Plan §4, Vertrag `docs/architektur-phase1-6.md` §6).
 *
 * Grundgedanke: Der RingBuffer enthält *alles*, was in den letzten Sekunden passiert ist. Der
 * Delay ist deshalb kein Zwischenspeicher, sondern nur ein **Leseoffset**:
 * `targetPts = newestPts − delayUs`. Slowmotion, Loop und Scrubben sind Varianten desselben
 * Prinzips — es wird nie neu encodiert und nie interpoliert.
 *
 * Alles läuft auf genau einem Thread (`playback`): Kommandos werden über [post] serialisiert,
 * eine Pump-Schleife füttert den Decoder und gibt fertige Frames zeitgenau frei. Die UI liest
 * ausschließlich die `@Volatile`-Primitiven — keine Objekte, keine Flows im Frame-Takt.
 */
class PlaybackController(
    private val ring: EncodedRingBuffer,
    private val decoder: DecoderPort,
    private val onPlaybackError: (String) -> Unit = {},
    private val clock: Clock = SystemNanoClock,
    private val newScheduler: () -> PlaybackScheduler = { HandlerScheduler("playback") },
) {

    // ───────────────── UI-Snapshot: nur Primitive, pro Frame gepollt ─────────────────

    @Volatile
    var modeOrdinal: Int = PlayMode.LIVE_DELAY.ordinal
        private set

    @Volatile
    var delayUs: Long = 5_000_000
        private set

    @Volatile
    var speedPermille: Int = 1000
        private set

    /** PTS des zuletzt zur Anzeige freigegebenen Frames; -1 = noch nichts gerendert. */
    @Volatile
    var positionPtsUs: Long = -1
        private set

    @Volatile
    var bufOldestPtsUs: Long = -1
        private set

    @Volatile
    var bufNewestPtsUs: Long = -1
        private set

    @Volatile
    var loopAPtsUs: Long = -1
        private set

    @Volatile
    var loopBPtsUs: Long = -1
        private set

    /** Puffer noch kürzer als der eingestellte Delay — die Anzeige klemmt am ältesten Bild. */
    @Volatile
    var warmingUp: Boolean = true
        private set

    /** „Puffer aufgeholt“ anzeigen, solange `uptimeMillis()` kleiner als dieser Wert ist. */
    @Volatile
    var hintCaughtUpUntilMs: Long = 0
        private set

    // ───────────────── Nur auf dem playback-Thread angefasst ─────────────────

    private var scheduler: PlaybackScheduler? = null

    @Volatile
    private var started = false

    private var mode = PlayMode.LIVE_DELAY

    /** Pacing-Anker: Frame [anchorPtsUs] erscheint zur Systemzeit [anchorSystemNs]. */
    private var anchorSystemNs = 0L
    private var anchorPtsUs = 0L

    /** Nach einem Seek: der erste Frame ≥ Ziel setzt den Anker neu und erscheint sofort. */
    private var anchorPending = true

    /** Frames mit `pts < seekTargetPtsUs` werden verworfen (Vordekodieren ab dem Keyframe). */
    private var seekTargetPtsUs = -1L

    /**
     * Ein Seek läuft noch (Vordekodieren ab dem Keyframe). Solange darf keine Regel einen
     * zweiten Seek auslösen: Zwischen Flush und erstem fertigen Frame vergehen mehrere
     * Pump-Takte, in denen `positionPtsUs` noch den alten Stand zeigt — ohne diese Sperre
     * würde die Wiedergabe sich endlos selbst neu aufsetzen und nie ein Bild zeigen.
     */
    private var seeking = false
    private var seekStartedNs = 0L

    /** Zuletzt gewünschte Scrub-Position, die noch auf ihren Seek wartet; -1 = keine. */
    private var pendingScrubPtsUs = -1L
    private var lastSeekNs = Long.MIN_VALUE / 2

    /**
     * Bezugspunkt für relatives Scrubben: die zuletzt *gewünschte* Position, nicht die zuletzt
     * gerenderte. Beide laufen während einer Wischgeste auseinander (der Seek ist noch
     * unterwegs) — würde man auf die gerenderte Position aufsetzen, ginge bei jedem Seek der
     * seither aufgelaufene Wischweg verloren und die Anzeige bliebe hinter dem Finger zurück.
     * -1 = ungültig, dann gilt wieder die tatsächliche Position.
     */
    private var scrubBasePtsUs = -1L

    /** Nächstes in den Decoder zu schiebendes Sample. */
    private var nextFeedSeq = -1L

    /** Gequeuete, aber noch nicht als Frame zurückgemeldete Samples. */
    private val inFlight = AtomicInteger(0)

    /**
     * Stehendes Bild: nur EIN Frame (der erste ≥ Ziel) wird gerendert, danach passiert nichts
     * mehr, bis [frameShown] zurückgesetzt wird. Gilt für PAUSED **und** für die
     * Aufwärmphase in LIVE_DELAY.
     */
    private var singleFrame = false
    private var frameShown = false

    /** Nächster liveTick soll auf `newest − delay` springen (Start, Delay-Änderung, „LIVE“). */
    private var liveSeekPending = true

    /** In der Aufwärmphase steht das älteste Bild — Merker für den Übergang in den Normalbetrieb. */
    private var showingOldest = false

    // Konfiguration für den einmaligen Neuaufbau nach einem Decoder-Fehler (Vertrag §6).
    private var videoSurface: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoRotation = 0
    private var decoderRetryUsed = false

    // ───────────────── Halteliste: decoder-out → playback ─────────────────
    // Fertig dekodierte, aber noch nicht freigegebene Frames. Vorab alloziert; die Indizes
    // gehören dem Decoder, bis wir render()/drop() rufen. Ist die Liste voll, wird nicht mehr
    // gefüttert (Backpressure) — genau das hält den Speicher konstant.

    private val holdPtsUs = LongArray(HOLD_CAPACITY)
    private val holdIndex = IntArray(HOLD_CAPACITY)
    private var holdCount = 0
    private val holdLock = Any()

    // ───────────────── Lebenszyklus ─────────────────

    /**
     * [surface] ist nur formal nullbar (Abweichung vom Vertrag §6): produktiv kommt hier immer
     * die Surface der `SurfaceView`, im JVM-Test lässt sich keine echte Surface erzeugen.
     */
    fun start(surface: Surface?, width: Int, height: Int, rotationDegrees: Int) {
        if (started) return
        started = true
        scheduler = newScheduler()
        videoSurface = surface
        videoWidth = width
        videoHeight = height
        videoRotation = rotationDegrees
        decoderRetryUsed = false
        try {
            decoder.configure(surface, width, height, rotationDegrees, decoderListener)
            decoder.start()
        } catch (e: Exception) {
            onPlaybackError("Wiedergabe konnte nicht starten: ${e.message}")
            return
        }
        post {
            setMode(PlayMode.LIVE_DELAY)
            liveSeekPending = true
            singleFrame = false
            frameShown = false
            positionPtsUs = -1
            schedule(0)
        }
    }

    /** Idempotent. Danach ist der Controller endgültig aus; die Engine baut bei Bedarf neu auf. */
    fun stop() {
        if (!started) return
        started = false
        val s = scheduler
        scheduler = null
        s?.removeAll()
        // Gehaltene Indizes NICHT freigeben: der Decoder wird gleich gestoppt, die Indizes
        // sind dann ohnehin ungültig.
        synchronized(holdLock) { holdCount = 0 }
        inFlight.set(0)
        decoder.stop()
        s?.quit()
    }

    // ───────────────── Kommandos (beliebiger Thread) ─────────────────

    fun setDelayUs(us: Long) = post {
        delayUs = us.coerceIn(0L, MAX_DELAY_US)
        // Der Delay ist nur ein Leseoffset — in LIVE einfach neu positionieren.
        if (mode == PlayMode.LIVE_DELAY) liveSeekPending = true
    }

    /**
     * Bild einfrieren. Kein Seek, kein Flush: Wir hören einfach auf, neue Frames freizugeben —
     * das zuletzt gerenderte Bild bleibt auf der Surface stehen. Deshalb steht es sofort
     * (Phase-3-DoD: < 100 ms).
     */
    fun freeze() = post {
        if (mode == PlayMode.PAUSED) return@post
        setMode(PlayMode.PAUSED)
        singleFrame = true
        frameShown = true
    }

    fun play(speedPermille: Int) = post {
        applySpeed(speedPermille)
        if (mode == PlayMode.PAUSED) {
            setMode(PlayMode.REPLAY)
            resumeFromPosition()
        }
    }

    /** Ratenwechsel ohne Flush: nur der Anker wird auf die aktuelle Position neu gesetzt. */
    fun setSpeed(speedPermille: Int) = post { applySpeed(speedPermille) }

    fun stepFrame(direction: Int) = post {
        if (mode != PlayMode.PAUSED) return@post
        if (direction > 0) {
            // Vorwärts: die nächsten Frames liegen bereits dekodiert in der Halteliste —
            // Tor aufmachen, der Pump rendert genau den nächsten.
            seekTargetPtsUs = positionPtsUs + 1
            frameShown = false
            anchorPending = true
            scrubBasePtsUs = -1
            schedule(0)
        } else {
            val seq = ring.seqAtOrAfter(positionPtsUs)
            if (seq <= 0) return@post
            val pts = ring.ptsAt(seq - 1)
            if (pts >= 0) seekToPaused(pts)
        }
    }

    fun scrubTo(ptsUs: Long) = post { requestScrub(clampToBuffer(ptsUs)) }

    fun scrubBy(deltaUs: Long) = post {
        val base = when {
            scrubBasePtsUs >= 0 -> scrubBasePtsUs
            positionPtsUs >= 0 -> positionPtsUs
            else -> ring.newestPtsUs()
        }
        if (base < 0) return@post
        requestScrub(clampToBuffer(base + deltaUs))
    }

    fun setLoop(aPtsUs: Long, bPtsUs: Long) = post {
        var a = clampToBuffer(minOf(aPtsUs, bPtsUs))
        var b = clampToBuffer(maxOf(aPtsUs, bPtsUs))
        if (a < 0 || b < 0) return@post
        // Mindestlänge, sonst dreht der Loop im Kreis, ohne dass man etwas erkennt.
        if (b - a < MIN_LOOP_US) {
            b = a + MIN_LOOP_US
            val newest = ring.newestPtsUs()
            if (newest >= 0 && b > newest) {
                b = newest
                a = maxOf(ring.oldestPtsUs(), b - MIN_LOOP_US)
            }
        }
        loopAPtsUs = a
        loopBPtsUs = b
        setMode(PlayMode.LOOP)
        singleFrame = false
        frameShown = false
        seekTo(a)
    }

    fun clearLoop() = post {
        loopAPtsUs = -1
        loopBPtsUs = -1
        if (mode == PlayMode.LOOP) {
            setMode(PlayMode.REPLAY)
            resumeFromPosition()
        }
    }

    fun goLive() = post {
        setMode(PlayMode.LIVE_DELAY)
        singleFrame = false
        frameShown = false
        showingOldest = false
        liveSeekPending = true
        applySpeed(1000)
        schedule(0)
    }

    // ───────────────── Pump-Schleife ─────────────────

    private val pumpTask = Runnable { pump() }

    private fun pump() {
        if (!started) return
        val oldest = ring.oldestPtsUs()
        val newest = ring.newestPtsUs()
        bufOldestPtsUs = oldest
        bufNewestPtsUs = newest

        if (newest < 0) {
            warmingUp = true
            schedule(IDLE_INTERVAL_MS)
            return
        }

        // Gemerktes Scrub-Ziel nachziehen, sobald das Zeitfenster frei ist.
        if (pendingScrubPtsUs >= 0 && clock.nanoTime() - lastSeekNs >= SCRUB_MIN_INTERVAL_NS) {
            val target = pendingScrubPtsUs
            pendingScrubPtsUs = -1
            frameShown = false
            seekTo(target)
        }

        when (mode) {
            PlayMode.LIVE_DELAY -> liveTick(oldest, newest)
            PlayMode.REPLAY -> replayTick(newest)
            PlayMode.LOOP -> loopTick()
            PlayMode.PAUSED -> Unit
        }

        feed()
        val predecoding = releaseDueFrames()

        // Beim Vordekodieren (Seek) sofort weiterpumpen, damit das Bild schnell steht.
        schedule(
            when {
                predecoding -> 0
                mode == PlayMode.PAUSED -> IDLE_INTERVAL_MS
                else -> PUMP_INTERVAL_MS
            }
        )
    }

    /**
     * LIVE_DELAY: Ziel ist immer `newest − delay`.
     *  - Aufwärmphase: Solange der Puffer kürzer als der Delay ist, gibt es das gewünschte
     *    Bild schlicht noch nicht. Wir klemmen am ältesten Frame (stehendes Bild) und starten
     *    den Normalbetrieb, sobald genug gepuffert ist — der Übergang ist nahtlos, weil in
     *    diesem Moment `newest − delay == oldest` gilt.
     *  - Drift: Hinkt die Ausgabe mehr als 300 ms hinter dem Soll her (verpasste Frames,
     *    Thermal-Drossel), einmal nach vorn springen statt für immer hinterherzulaufen.
     */
    private fun liveTick(oldest: Long, newest: Long) {
        val warm = newest - oldest < delayUs
        warmingUp = warm

        if (warm) {
            if (liveSeekPending || !showingOldest) {
                liveSeekPending = false
                showingOldest = true
                singleFrame = true
                frameShown = false
                seekTo(oldest)
            }
            return
        }

        val target = newest - delayUs
        if (liveSeekPending || showingOldest) {
            liveSeekPending = false
            showingOldest = false
            singleFrame = false
            frameShown = false
            seekTo(target)
            return
        }

        if (seekInFlight()) return

        if (positionPtsUs < 0) {
            seekTo(target)
            return
        }

        if (target - positionPtsUs > DRIFT_LIMIT_US) {
            seekTo(target)
            hintCaughtUpUntilMs = clock.uptimeMillis() + HINT_MS
        }
    }

    /**
     * REPLAY: Erreicht die Wiedergabe bei Normalgeschwindigkeit die Live-Kante, geht sie
     * nahtlos in LIVE_DELAY über — **ohne** Seek und ohne Anker-Reset, sonst gäbe es an dieser
     * Stelle einen sichtbaren Sprung.
     */
    private fun replayTick(newest: Long) {
        if (speedPermille >= 1000 && positionPtsUs >= newest - delayUs) {
            setMode(PlayMode.LIVE_DELAY)
            // LIVE läuft immer in Echtzeit: eine Aufhol-Geschwindigkeit (> 1×) würde sonst
            // weiter über die Live-Kante hinauslaufen. Nur der Anker wird neu gesetzt —
            // kein Flush, kein Sprung im Bild.
            applySpeed(1000)
            liveSeekPending = false
            showingOldest = false
        }
    }

    /** LOOP: an der B-Marke zurück auf A springen — mit jeder Geschwindigkeit. */
    private fun loopTick() {
        val b = loopBPtsUs
        val a = loopAPtsUs
        if (a < 0 || b < 0) return
        // Ohne diese Sperre würde jeder Takt zwischen Rücksprung und erstem Bild erneut
        // springen — die Position steht bis dahin noch auf dem alten Wert hinter B.
        if (seekInFlight()) return
        if (positionPtsUs >= b) seekTo(a)
    }

    /**
     * Hält den Decoder ein paar Samples voraus. Zwei Bremsen: die Halteliste (fertige Frames)
     * und `inFlight` (unterwegs im Decoder). Beides zusammen begrenzt, wie weit die Dekodierung
     * der Anzeige vorauslaufen darf.
     */
    private fun feed() {
        if (nextFeedSeq < 0) return
        val oldestSeq = ring.oldestSeq()
        val newestSeq = ring.newestSeq()
        if (oldestSeq < 0 || newestSeq < 0) return

        // Vom Schreiber überholt: das nächste Sample gibt es nicht mehr (Plan §3 Regel 5).
        if (nextFeedSeq < oldestSeq) {
            onOvertaken()
            return
        }

        while (nextFeedSeq <= newestSeq) {
            val held = synchronized(holdLock) { holdCount }
            if (held + inFlight.get() >= LOOKAHEAD) break
            if (!decoder.canQueue()) break
            if (!decoder.queueSample(nextFeedSeq)) break
            inFlight.incrementAndGet()
            nextFeedSeq++
        }
    }

    /**
     * Gibt alle fälligen Frames frei. Rückgabe: true, wenn Frames verworfen wurden — dann läuft
     * gerade ein Seek-Vorlauf und der nächste Pump soll sofort folgen.
     *
     * Pacing (Plan §4): `renderAtNs = anchorSystemNs + (pts − anchorPtsUs) · 1000 · 1000 / speed`.
     * Bei 250 ‰ vergeht viermal so viel Systemzeit pro PTS-Schritt — Slowmotion ohne
     * Re-Encode und ohne Interpolation. Freigegeben wird ein Frame erst kurz (≤ 50 ms) vor
     * seinem Anzeigezeitpunkt; den Rest erledigt der Compositor vsync-genau.
     */
    private fun releaseDueFrames(): Boolean {
        var dropped = false
        val now = clock.nanoTime()
        while (true) {
            var ptsUs: Long
            var index: Int
            synchronized(holdLock) {
                if (holdCount == 0) return dropped
                ptsUs = holdPtsUs[0]
                index = holdIndex[0]
            }

            // Vorlauf-Frames zwischen Keyframe und Seek-Ziel: dekodieren ja, zeigen nein.
            if (ptsUs < seekTargetPtsUs) {
                removeFirstHold()
                decoder.drop(index)
                dropped = true
                continue
            }

            // Stehendes Bild: der Ziel-Frame ist schon gezeigt, alles Weitere wartet in der
            // Halteliste (und dient stepFrame(+1) als Vorrat).
            if (singleFrame && frameShown) return dropped

            if (anchorPending) {
                anchorSystemNs = now + ANCHOR_LEAD_NS
                anchorPtsUs = ptsUs
                anchorPending = false
            }

            val renderAtNs = anchorSystemNs + (ptsUs - anchorPtsUs) * 1_000_000L / speedPermille
            if (renderAtNs - now > RENDER_LEAD_NS) return dropped

            removeFirstHold()
            decoder.render(index, renderAtNs)
            positionPtsUs = ptsUs
            seeking = false // das Ziel steht auf dem Schirm — Regeln dürfen wieder greifen
            if (singleFrame) frameShown = true
        }
    }

    // ───────────────── Seek & Modus-Helfer ─────────────────

    /**
     * Seek (Plan §4): flushen, beim Keyframe **auf oder vor** dem Ziel wieder einsteigen und die
     * Frames davor verwerfen. Das kurze GOP (0,5 s) hält diesen Vorlauf klein — deshalb wirkt
     * Scrubben unmittelbar.
     */
    private fun seekTo(targetPtsUs: Long) {
        decoder.flush()
        // Nach flush() sind alle gemeldeten Buffer-Indizes ungültig: verwerfen, NICHT freigeben.
        synchronized(holdLock) { holdCount = 0 }
        inFlight.set(0)

        var seq = ring.seqOfKeyframeAtOrBefore(targetPtsUs)
        if (seq < 0) seq = ring.oldestSeq()
        nextFeedSeq = seq
        seekTargetPtsUs = targetPtsUs
        anchorPending = true
        seeking = true
        seekStartedNs = clock.nanoTime()
        lastSeekNs = seekStartedNs
    }

    /**
     * Läuft gerade ein Seek? Nach [SEEK_TIMEOUT_NS] ohne Ergebnis wird die Sperre gelöst —
     * falls der Decoder wider Erwarten kein Bild liefert, soll sich die Wiedergabe selbst
     * wieder einfangen statt für immer zu warten.
     */
    private fun seekInFlight(): Boolean {
        if (!seeking) return false
        if (clock.nanoTime() - seekStartedNs > SEEK_TIMEOUT_NS) {
            seeking = false
            return false
        }
        return true
    }

    /** Seek mit stehendem Bild (Einzelbild rückwärts, Timeline-Tipp). */
    private fun seekToPaused(targetPtsUs: Long) {
        if (targetPtsUs < 0) return
        setMode(PlayMode.PAUSED)
        singleFrame = true
        frameShown = false
        pendingScrubPtsUs = -1
        seekTo(targetPtsUs)
        schedule(0)
    }

    /**
     * Scrubben zusammenfassen: Eine Wischgeste liefert bis zu 120 Positionen pro Sekunde. Jede
     * sofort zu seeken hieße, den Decoder zu flushen, bevor er das vorige Ziel überhaupt
     * dekodiert hat — das Bild bliebe während der Geste stehen und spränge erst beim Loslassen.
     * Deshalb: höchstens alle [SCRUB_MIN_INTERVAL_NS] ein echter Seek, dazwischen wird nur das
     * jüngste Ziel gemerkt. Ergebnis sind ~20 Bilder pro Sekunde beim Ziehen.
     */
    private fun requestScrub(targetPtsUs: Long) {
        if (targetPtsUs < 0) return
        setMode(PlayMode.PAUSED)
        singleFrame = true
        scrubBasePtsUs = targetPtsUs
        if (clock.nanoTime() - lastSeekNs < SCRUB_MIN_INTERVAL_NS) {
            pendingScrubPtsUs = targetPtsUs
            return
        }
        pendingScrubPtsUs = -1
        frameShown = false
        seekTo(targetPtsUs)
        schedule(0)
    }

    /**
     * Weiterlaufen ab der aktuellen Position — ohne Flush: Die nächsten Frames liegen bereits
     * dekodiert in der Halteliste, der Decoder ist an der richtigen Stelle.
     */
    private fun resumeFromPosition() {
        singleFrame = false
        frameShown = false
        seekTargetPtsUs = positionPtsUs + 1
        anchorPending = true
        schedule(0)
    }

    private fun applySpeed(permille: Int) {
        val v = permille.coerceIn(MIN_SPEED_PERMILLE, MAX_SPEED_PERMILLE)
        if (v == speedPermille) return
        speedPermille = v
        // Zeitlupe aus dem Live-Bild heraus ist per Definition eine Wiederholung: Wer
        // langsamer abspielt, fällt hinter die Live-Kante zurück. Ohne diesen Wechsel würde
        // die Drift-Regel sofort gegen die Verlangsamung anspringen und nach vorn springen.
        if (v < 1000 && mode == PlayMode.LIVE_DELAY) setMode(PlayMode.REPLAY)
        // Ratenwechsel = nur neuer Anker auf der aktuellen Position. Kein Flush, kein Ruckler.
        if (positionPtsUs >= 0) {
            anchorSystemNs = clock.nanoTime()
            anchorPtsUs = positionPtsUs
            anchorPending = false
        } else {
            anchorPending = true
        }
    }

    /**
     * Der Leser wurde vom Schreiber überholt (sehr langsame Wiedergabe bei vollem Puffer):
     * auf den Pufferanfang klemmen und das der UI mitteilen — statt zu crashen oder Müll zu
     * dekodieren (Plan §3 Regel 5).
     */
    private fun onOvertaken() {
        val oldest = ring.oldestPtsUs()
        if (oldest < 0) return
        seekTo(oldest)
        hintCaughtUpUntilMs = clock.uptimeMillis() + HINT_MS
    }

    private fun setMode(m: PlayMode) {
        mode = m
        modeOrdinal = m.ordinal
        // Jeder Moduswechsel beendet eine laufende Wischgeste: der Bezugspunkt fürs relative
        // Scrubben ist danach wieder die tatsächliche Position.
        scrubBasePtsUs = -1
    }

    private fun clampToBuffer(ptsUs: Long): Long {
        val oldest = ring.oldestPtsUs()
        val newest = ring.newestPtsUs()
        if (oldest < 0 || newest < 0) return -1
        return ptsUs.coerceIn(oldest, newest)
    }

    private fun removeFirstHold() {
        synchronized(holdLock) {
            if (holdCount == 0) return
            for (i in 1 until holdCount) {
                holdPtsUs[i - 1] = holdPtsUs[i]
                holdIndex[i - 1] = holdIndex[i]
            }
            holdCount--
        }
    }

    // Typ explizit: der Listener referenziert sich in der Fehlerbehandlung selbst.
    private val decoderListener: VideoDecoder.Listener = object : VideoDecoder.Listener {
        /** Läuft auf `decoder-out` — hier nur einreihen, keine Entscheidung, keine Allokation. */
        override fun onFrame(ptsUs: Long, bufferIndex: Int) {
            inFlight.decrementAndGet()
            var overflow = -1
            synchronized(holdLock) {
                if (holdCount < HOLD_CAPACITY) {
                    holdPtsUs[holdCount] = ptsUs
                    holdIndex[holdCount] = bufferIndex
                    holdCount++
                } else {
                    overflow = bufferIndex
                }
            }
            // Notventil: Liste voll (dürfte wegen der Backpressure nie passieren) — Buffer
            // sofort zurückgeben, sonst hungert der Decoder aus.
            if (overflow >= 0) decoder.drop(overflow)
        }

        /**
         * Ein Decoder-Fehler bekommt genau einen Rettungsversuch (Vertrag §6): Decoder neu
         * aufbauen und in LIVE weitermachen. Scheitert das oder kommt ein zweiter Fehler,
         * geht die Meldung an die Engine und damit als Klartext in die Oberfläche.
         */
        override fun onDecoderError(msg: String) {
            Log.e(TAG, msg)
            val s = scheduler
            if (s == null || decoderRetryUsed) {
                onPlaybackError(msg)
                return
            }
            s.post {
                if (!started || decoderRetryUsed) return@post
                decoderRetryUsed = true
                val ok = runCatching {
                    decoder.stop()
                    decoder.configure(
                        videoSurface,
                        videoWidth,
                        videoHeight,
                        videoRotation,
                        decoderListener,
                    )
                    decoder.start()
                }.isSuccess
                if (!ok) {
                    onPlaybackError(msg)
                    return@post
                }
                synchronized(holdLock) { holdCount = 0 }
                inFlight.set(0)
                seeking = false
                setMode(PlayMode.LIVE_DELAY)
                liveSeekPending = true
                singleFrame = false
                frameShown = false
                positionPtsUs = -1
                schedule(0)
            }
        }
    }

    // ───────────────── Scheduling ─────────────────

    private fun schedule(delayMs: Long) {
        val s = scheduler ?: return
        if (!started) return
        if (delayMs <= 0) s.post(pumpTask) else s.postDelayed(pumpTask, delayMs)
    }

    /**
     * Kommandos auf den playback-Thread schieben. Vor [start] (die Engine setzt z. B. den
     * gespeicherten Delay) gibt es noch keinen Thread — dann läuft der Block direkt, es
     * arbeitet ohnehin noch niemand nebenläufig.
     */
    private inline fun post(crossinline block: () -> Unit) {
        val s = scheduler
        if (s == null) block() else s.post { block() }
    }

    private companion object {
        const val TAG = "DelayCamPlayback"

        /** Pump-Takt im Betrieb bzw. bei stehendem Bild. */
        const val PUMP_INTERVAL_MS = 4L
        const val IDLE_INTERVAL_MS = 16L

        /** Frames werden bis zu 50 ms vor ihrem Anzeigezeitpunkt an den Compositor übergeben. */
        const val RENDER_LEAD_NS = 50_000_000L
        const val ANCHOR_LEAD_NS = 50_000_000L

        /** Ab 300 ms Rückstand auf den Soll-Delay wird nach vorn gesprungen (Plan §4). */
        const val DRIFT_LIMIT_US = 300_000L

        /** Notausstieg, falls ein Seek kein Bild liefert. */
        const val SEEK_TIMEOUT_NS = 1_000_000_000L

        /** Mindestabstand zwischen zwei Scrub-Seeks — begrenzt die Decoder-Last beim Ziehen. */
        const val SCRUB_MIN_INTERVAL_NS = 50_000_000L

        const val HINT_MS = 2_000L
        const val MIN_LOOP_US = 300_000L
        const val MAX_DELAY_US = 30_000_000L

        /** ⅒× … 2× — mehr gibt die Bedienung nicht her. */
        const val MIN_SPEED_PERMILLE = 100
        const val MAX_SPEED_PERMILLE = 2000

        /** Dekodierte, noch nicht gezeigte Frames + Samples im Decoder. */
        const val LOOKAHEAD = 8
        const val HOLD_CAPACITY = 12
    }
}
