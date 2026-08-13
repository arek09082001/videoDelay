package de.aweiss.delaycam.playback

import android.media.MediaCodec
import de.aweiss.delaycam.buffer.EncodedRingBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Zustandsmaschine der Wiedergabe mit injizierter Uhr (Plan §13.2a): Delay-Offset, Slowmotion,
 * Ratenwechsel ohne Flush, Loop-Grenzen und die Drift-Regel — alles ohne Emulator, ohne
 * MediaCodec und ohne echtes Warten.
 */
class PlaybackControllerTest {

    private val fps = 30
    private val frameUs = 1_000_000L / fps
    private val frameMs = 1000L / fps

    private lateinit var ring: EncodedRingBuffer
    private lateinit var time: VirtualTime
    private lateinit var decoder: FakeDecoder
    private lateinit var controller: PlaybackController

    private var nextFrame = 0

    @Before
    fun setUp() {
        ring = EncodedRingBuffer(2_000_000, 8192)
        time = VirtualTime()
        decoder = FakeDecoder(ring, time)
        controller = PlaybackController(
            ring = ring,
            decoder = decoder,
            onPlaybackError = {},
            clock = time,
            newScheduler = { time },
        )
    }

    // ── Hilfen ────────────────────────────────────────────────────────────────

    /** Ein Sample anhängen; jedes 15. ist ein Keyframe (0,5 s GOP wie im Encoder-Format). */
    private fun appendFrame() {
        val size = 32
        val data = ByteBuffer.allocateDirect(size)
        repeat(size) { data.put(nextFrame.toByte()) }
        data.position(0)
        val info = MediaCodec.BufferInfo().apply {
            offset = 0
            this.size = size
            presentationTimeUs = nextFrame * frameUs
            flags = if (nextFrame % 15 == 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        }
        ring.append(data, info)
        nextFrame++
    }

    private fun prefill(seconds: Int) {
        repeat(seconds * fps) { appendFrame() }
    }

    /** Zeit vorstellen und dabei wie die Kamera weiter aufnehmen (ein Frame pro Frame-Dauer). */
    private fun advanceRecording(millis: Long) {
        var left = millis
        while (left > 0) {
            val step = minOf(frameMs, left)
            // Erst aufnehmen, dann die Zeit laufen lassen: so hat der Pump den zuletzt
            // angehängten Frame am Ende auch wirklich gesehen.
            appendFrame()
            time.advance(step)
            left -= step
        }
    }

    private fun startLive(delayUs: Long) {
        controller.start(null, 1920, 1080, 0)
        controller.setDelayUs(delayUs)
        time.advance(50)
    }

    private fun assertNear(expected: Long, actual: Long, toleranceUs: Long, what: String) {
        val diff = kotlin.math.abs(expected - actual)
        assertTrue(
            "$what: erwartet ≈ $expected, war $actual (Abweichung ${diff / 1000} ms)",
            diff <= toleranceUs,
        )
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `LIVE haelt den eingestellten Versatz konstant`() {
        prefill(10)
        startLive(3_000_000)
        advanceRecording(1000)

        assertEquals(PlayMode.LIVE_DELAY.ordinal, controller.modeOrdinal)
        assertNear(
            expected = 3_000_000,
            actual = ring.newestPtsUs() - controller.positionPtsUs,
            toleranceUs = 3 * frameUs,
            what = "Versatz nach 1 s",
        )

        // … und bleibt es auch nach fünf weiteren Sekunden (kein Weglaufen, kein Aufholen).
        advanceRecording(5000)
        assertNear(
            expected = 3_000_000,
            actual = ring.newestPtsUs() - controller.positionPtsUs,
            toleranceUs = 3 * frameUs,
            what = "Versatz nach 6 s",
        )
    }

    @Test
    fun `Delay-Aenderung im Betrieb wirkt sofort`() {
        prefill(20)
        startLive(3_000_000)
        advanceRecording(500)

        controller.setDelayUs(10_000_000)
        advanceRecording(300)

        assertNear(
            expected = 10_000_000,
            actual = ring.newestPtsUs() - controller.positionPtsUs,
            toleranceUs = 5 * frameUs,
            what = "Versatz nach Delay-Erhöhung",
        )
    }

    @Test
    fun `Aufwaermphase klemmt am aeltesten Bild`() {
        prefill(1) // nur 1 s Material, Delay 5 s
        startLive(5_000_000)
        advanceRecording(300)

        assertTrue("warmingUp muss gesetzt sein", controller.warmingUp)
        assertNear(
            expected = ring.oldestPtsUs(),
            actual = controller.positionPtsUs,
            toleranceUs = 3 * frameUs,
            what = "Position während der Aufwärmphase",
        )
    }

    @Test
    fun `Einfrieren stoppt die Anzeige sofort`() {
        prefill(10)
        startLive(3_000_000)
        advanceRecording(500)

        controller.freeze()
        time.advance(20)
        val frozenAt = controller.positionPtsUs
        assertEquals(PlayMode.PAUSED.ordinal, controller.modeOrdinal)

        advanceRecording(1000)
        assertEquals("Im Pause-Modus darf sich nichts bewegen", frozenAt, controller.positionPtsUs)
    }

    @Test
    fun `Slowmotion laeuft ein Viertel so schnell`() {
        prefill(10)
        startLive(3_000_000)
        advanceRecording(300)

        controller.freeze()
        time.advance(20)
        val start = controller.positionPtsUs

        controller.play(250)
        advanceRecording(1000) // 1 s Wanduhr

        val advanced = controller.positionPtsUs - start
        assertEquals(PlayMode.REPLAY.ordinal, controller.modeOrdinal)
        assertNear(
            expected = 250_000, // ¼ × 1 s Material
            actual = advanced,
            toleranceUs = 4 * frameUs,
            what = "Materialfortschritt bei ¼×",
        )
    }

    @Test
    fun `Ratenwechsel kommt ohne Flush aus`() {
        prefill(10)
        startLive(5_000_000)
        advanceRecording(300)
        controller.freeze()
        controller.play(1000)
        advanceRecording(200)

        val flushesBefore = decoder.flushCount
        controller.setSpeed(250)
        advanceRecording(300)
        controller.setSpeed(500)
        advanceRecording(300)

        assertEquals(
            "Ein Geschwindigkeitswechsel darf nur den Anker verschieben",
            flushesBefore,
            decoder.flushCount,
        )
    }

    @Test
    fun `Loop bleibt zwischen A und B`() {
        prefill(10)
        startLive(3_000_000)
        advanceRecording(300)

        val a = 4_000_000L
        val b = 5_000_000L
        controller.setLoop(a, b)
        controller.play(1000)
        advanceRecording(200)

        assertEquals(PlayMode.LOOP.ordinal, controller.modeOrdinal)
        val flushesAtStart = decoder.flushCount

        // Vier Sekunden Wanduhr bei 1 s Loop-Länge ⇒ mehrere Durchläufe.
        repeat(40) {
            advanceRecording(100)
            val p = controller.positionPtsUs
            assertTrue(
                "Position $p muss im Loop-Bereich [$a, $b] bleiben",
                p >= a - 5 * frameUs && p <= b + 5 * frameUs,
            )
        }
        assertTrue(
            "Der Loop muss mehrfach zurückgesprungen sein",
            decoder.flushCount >= flushesAtStart + 2,
        )
    }

    @Test
    fun `Loop erzwingt eine Mindestlaenge`() {
        prefill(10)
        startLive(3_000_000)
        advanceRecording(200)

        controller.setLoop(4_000_000, 4_050_000) // 50 ms gewünscht
        time.advance(20)

        assertTrue(
            "Loop muss auf mindestens 300 ms aufgezogen werden",
            controller.loopBPtsUs - controller.loopAPtsUs >= 300_000,
        )
    }

    @Test
    fun `Scrubben pausiert und zeigt die Zielstelle`() {
        prefill(10)
        startLive(3_000_000)
        advanceRecording(300)

        val target = 2_500_000L
        controller.scrubTo(target)
        time.advance(100)

        assertEquals(PlayMode.PAUSED.ordinal, controller.modeOrdinal)
        assertNear(target, controller.positionPtsUs, 2 * frameUs, "Position nach dem Scrubben")
        assertTrue(
            "Vorlauf-Frames zwischen Keyframe und Ziel müssen verworfen werden",
            decoder.droppedPts.isNotEmpty(),
        )
        assertTrue(
            "Es darf kein Frame vor dem Ziel gerendert werden",
            decoder.renderedPts.last() >= target - frameUs,
        )
    }

    @Test
    fun `schnelles Wischen fasst die Seeks zusammen`() {
        prefill(20)
        startLive(3_000_000)
        advanceRecording(300)

        val flushesBefore = decoder.flushCount
        val startPosition = controller.positionPtsUs
        // Eine Wischgeste über eine Sekunde: 60 Positionen, je 50 ms Material.
        repeat(60) {
            controller.scrubBy(50_000)
            time.advance(16)
        }
        time.advance(100)

        val seeks = decoder.flushCount - flushesBefore
        assertTrue(
            "60 Wisch-Ereignisse dürfen nicht 60 Seeks auslösen (waren $seeks)",
            seeks <= 25,
        )
        assertTrue("Es muss trotzdem gesucht worden sein (waren $seeks)", seeks >= 5)
        // Zusammenfassen darf keine Bewegung verschlucken: 60 × 50 ms müssen ankommen.
        assertNear(
            expected = startPosition + 3_000_000,
            actual = controller.positionPtsUs,
            toleranceUs = 3 * frameUs,
            what = "Position nach der Wischgeste",
        )
    }

    @Test
    fun `Einzelbild vor und zurueck bewegt genau ein Bild`() {
        prefill(10)
        startLive(3_000_000)
        advanceRecording(300)

        controller.scrubTo(5_000_000)
        time.advance(100)
        val start = controller.positionPtsUs

        controller.stepFrame(+1)
        time.advance(50)
        assertNear(start + frameUs, controller.positionPtsUs, frameUs / 2, "ein Bild vorwärts")

        controller.stepFrame(-1)
        time.advance(100)
        assertNear(start, controller.positionPtsUs, frameUs / 2, "ein Bild zurück")
    }

    @Test
    fun `Drift-Regel springt nach vorn und meldet es der UI`() {
        prefill(10)
        startLive(2_000_000)
        advanceRecording(300)

        // Die App war kurz blockiert: der Encoder hat 3 s Material auf einen Schlag nachgelegt.
        repeat(3 * fps) { appendFrame() }
        time.advance(200)

        assertTrue(
            "Der Rückstand muss wieder unter dem Soll-Delay liegen",
            ring.newestPtsUs() - controller.positionPtsUs <= 2_000_000 + 500_000,
        )
        assertTrue(
            "Die UI muss den Sprung als „Puffer aufgeholt“ anzeigen können",
            controller.hintCaughtUpUntilMs > time.uptimeMillis(),
        )
    }

    @Test
    fun `LIVE ist aus jedem Zustand erreichbar`() {
        prefill(10)
        startLive(3_000_000)
        advanceRecording(300)

        controller.setLoop(4_000_000, 5_000_000)
        controller.play(250)
        advanceRecording(300)
        assertEquals(PlayMode.LOOP.ordinal, controller.modeOrdinal)

        controller.goLive()
        advanceRecording(400)

        assertEquals(PlayMode.LIVE_DELAY.ordinal, controller.modeOrdinal)
        assertEquals("LIVE läuft immer in Echtzeit", 1000, controller.speedPermille)
        assertNear(
            expected = 3_000_000,
            actual = ring.newestPtsUs() - controller.positionPtsUs,
            toleranceUs = 5 * frameUs,
            what = "Versatz nach der Rückkehr zu LIVE",
        )
    }

    @Test
    fun `Replay laeuft an der Live-Kante nahtlos in LIVE ueber`() {
        prefill(10)
        startLive(2_000_000)
        advanceRecording(200)

        // 4 s zurück und mit doppelter Geschwindigkeit aufholen: bei genau 1× würde die
        // Wiedergabe die Live-Kante nie einholen, sie wandert ja mit derselben Rate weiter.
        controller.scrubTo(ring.newestPtsUs() - 4_000_000)
        time.advance(100)
        controller.play(2000)

        val flushesBefore = decoder.flushCount
        advanceRecording(3000)

        assertEquals(PlayMode.LIVE_DELAY.ordinal, controller.modeOrdinal)
        assertEquals(
            "Der Übergang REPLAY→LIVE darf keinen Seek auslösen",
            flushesBefore,
            decoder.flushCount,
        )
        assertEquals("LIVE läuft immer in Echtzeit", 1000, controller.speedPermille)
    }

    @Test
    fun `Zeitlupe aus dem Live-Bild heraus wird zur Wiederholung`() {
        prefill(10)
        startLive(3_000_000)
        advanceRecording(300)
        assertEquals(PlayMode.LIVE_DELAY.ordinal, controller.modeOrdinal)

        val flushesBefore = decoder.flushCount
        controller.setSpeed(250)
        advanceRecording(500)

        assertEquals(PlayMode.REPLAY.ordinal, controller.modeOrdinal)
        assertEquals(
            "Der Wechsel in die Zeitlupe darf keinen Seek auslösen",
            flushesBefore,
            decoder.flushCount,
        )
    }

    @Test
    fun `Puffer-Spiegel fuer die Timeline wird laufend nachgefuehrt`() {
        prefill(5)
        startLive(2_000_000)
        advanceRecording(300)

        assertEquals(ring.oldestPtsUs(), controller.bufOldestPtsUs)
        assertEquals(ring.newestPtsUs(), controller.bufNewestPtsUs)
    }

    @Test
    fun `stop ist idempotent und beendet den Decoder`() {
        prefill(5)
        startLive(2_000_000)
        advanceRecording(100)

        controller.stop()
        controller.stop()

        val position = controller.positionPtsUs
        time.advance(500)
        assertEquals("Nach stop() darf nichts mehr laufen", position, controller.positionPtsUs)
    }
}
