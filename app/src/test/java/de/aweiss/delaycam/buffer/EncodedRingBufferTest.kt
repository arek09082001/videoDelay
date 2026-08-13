package de.aweiss.delaycam.buffer

import android.media.MediaCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * JVM-Tests des Ring-Buffers (Plan §13.2a): Wrap-around, GOP-Verdrängung, Keyframe-Suche und
 * das Überholen des Lesers. Läuft ohne Emulator in Sekunden.
 */
class EncodedRingBufferTest {

    private val frameDurationUs = 33_333L

    /** Sample mit erkennbarem Inhalt: jedes Byte trägt die laufende Nummer. */
    private fun payload(size: Int, marker: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(size)
        repeat(size) { b.put(marker.toByte()) }
        b.position(0)
        return b
    }

    private fun info(size: Int, ptsUs: Long, key: Boolean) = MediaCodec.BufferInfo().apply {
        offset = 0
        this.size = size
        presentationTimeUs = ptsUs
        flags = if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
    }

    /** Schreibt [count] Samples; jedes [gop]-te ist ein Keyframe. */
    private fun fill(
        ring: EncodedRingBuffer,
        count: Int,
        size: Int = 100,
        gop: Int = 4,
        startIndex: Int = 0,
    ) {
        for (i in startIndex until startIndex + count) {
            ring.append(payload(size, i), info(size, i * frameDurationUs, i % gop == 0))
        }
    }

    @Test
    fun `schreiben und lesen liefert die erwarteten Kennzahlen`() {
        val ring = EncodedRingBuffer(10_000, 64)
        assertTrue(ring.isEmpty())
        assertEquals(-1L, ring.oldestSeq())
        assertEquals(-1L, ring.newestPtsUs())

        fill(ring, 8)

        assertFalse(ring.isEmpty())
        assertEquals(8, ring.sampleCount())
        assertEquals(0L, ring.oldestSeq())
        assertEquals(7L, ring.newestSeq())
        assertEquals(0L, ring.oldestPtsUs())
        assertEquals(7 * frameDurationUs, ring.newestPtsUs())
        assertEquals(7 * frameDurationUs, ring.durationUs())
        assertTrue(ring.isKeyframe(4))
        assertFalse(ring.isKeyframe(5))
        assertEquals(-1L, ring.ptsAt(8))
    }

    @Test
    fun `Keyframe-Suche findet den GOP-Anfang vor dem Ziel`() {
        val ring = EncodedRingBuffer(10_000, 64)
        fill(ring, 12, gop = 4) // Keyframes bei seq 0, 4, 8

        assertEquals(4L, ring.seqOfKeyframeAtOrBefore(6 * frameDurationUs))
        assertEquals(4L, ring.seqOfKeyframeAtOrBefore(4 * frameDurationUs))
        assertEquals(0L, ring.seqOfKeyframeAtOrBefore(3 * frameDurationUs))
        assertEquals(8L, ring.seqOfKeyframeAtOrBefore(999 * frameDurationUs))
        // Ziel liegt vor dem ersten Sample
        assertEquals(-1L, ring.seqOfKeyframeAtOrBefore(-1L))
    }

    @Test
    fun `seqAtOrAfter findet das erste Sample ab einem Zeitpunkt`() {
        val ring = EncodedRingBuffer(10_000, 64)
        fill(ring, 10)

        assertEquals(0L, ring.seqAtOrAfter(0))
        assertEquals(5L, ring.seqAtOrAfter(5 * frameDurationUs))
        assertEquals(6L, ring.seqAtOrAfter(5 * frameDurationUs + 1))
        assertEquals(-1L, ring.seqAtOrAfter(100 * frameDurationUs))
    }

    @Test
    fun `Wrap-around liefert weiterhin unverfälschte Daten`() {
        // 1000 B fassen 10 Samples à 100 B — nach 40 Samples ist der Puffer mehrfach umgelaufen.
        val ring = EncodedRingBuffer(1_000, 16)
        fill(ring, 40, size = 100, gop = 4)

        val oldest = ring.oldestSeq()
        val newest = ring.newestSeq()
        assertTrue("Puffer sollte umgelaufen sein", oldest > 0)

        val dst = ByteBuffer.allocateDirect(256)
        var seq = oldest
        while (seq <= newest) {
            val size = ring.copySampleInto(seq, dst)
            assertEquals(100, size)
            val actual = ByteArray(size)
            dst.get(actual)
            // Jedes Byte muss die Sample-Nummer tragen — sonst wurde beim Wrap gemischt.
            val expected = ByteArray(size) { seq.toInt().toByte() }
            assertArrayEquals("Sample $seq ist beschädigt", expected, actual)
            seq++
        }
    }

    @Test
    fun `Verdraengung entfernt ganze GOPs und haelt die Keyframe-Invariante`() {
        val ring = EncodedRingBuffer(1_000, 16)
        for (i in 0 until 60) {
            ring.append(payload(100, i), info(100, i * frameDurationUs, i % 4 == 0))
            val oldest = ring.oldestSeq()
            if (oldest >= 0) {
                assertTrue(
                    "Ältestes Sample (seq $oldest) muss ein Keyframe sein",
                    ring.isKeyframe(oldest),
                )
                // Ganze GOPs ⇒ die Startnummer ist immer durch die GOP-Länge teilbar.
                assertEquals(0L, oldest % 4)
            }
        }
    }

    @Test
    fun `Samples vor dem ersten Keyframe werden verworfen`() {
        val ring = EncodedRingBuffer(10_000, 64)
        ring.append(payload(100, 1), info(100, 0, key = false))
        assertTrue("Ohne Keyframe darf nichts im Puffer landen", ring.isEmpty())

        ring.append(payload(100, 2), info(100, frameDurationUs, key = true))
        assertEquals(1, ring.sampleCount())
        assertTrue(ring.isKeyframe(ring.oldestSeq()))
    }

    @Test
    fun `zu grosses Sample wird still verworfen`() {
        val ring = EncodedRingBuffer(500, 16)
        ring.append(payload(100, 1), info(100, 0, key = true))
        ring.append(payload(600, 2), info(600, frameDurationUs, key = true))
        assertEquals("Das übergroße Sample darf den Puffer nicht anfassen", 1, ring.sampleCount())
    }

    @Test
    fun `ueberholter Leser bekommt minus eins statt falscher Daten`() {
        val ring = EncodedRingBuffer(1_000, 16)
        fill(ring, 8, size = 100, gop = 4)
        val victim = ring.oldestSeq()

        // Genug nachschieben, dass die GOP des Opfers verdrängt wird.
        fill(ring, 40, size = 100, gop = 4, startIndex = 8)

        assertEquals(-1L, ring.ptsAt(victim))
        assertFalse(ring.isKeyframe(victim))
        assertEquals(-1, ring.copySampleInto(victim, ByteBuffer.allocateDirect(256)))
        // Die verdrängte Nummer bleibt für immer ungültig — sie wird nie neu vergeben.
        assertTrue(ring.oldestSeq() > victim)
    }

    @Test
    fun `clear macht alte seqs ungueltig und laesst die Zaehlung weiterlaufen`() {
        val ring = EncodedRingBuffer(10_000, 64)
        fill(ring, 6)
        val lastSeq = ring.newestSeq()

        ring.clear()
        assertTrue(ring.isEmpty())
        assertEquals(-1L, ring.ptsAt(lastSeq))
        assertEquals(0, ring.usedBytes())

        // Nach dem Reset wird ab dem nächsten Keyframe neu aufgesetzt.
        fill(ring, 4, startIndex = 100)
        assertTrue(
            "seq-Nummern dürfen nach clear() nicht neu vergeben werden",
            ring.oldestSeq() > lastSeq,
        )
    }

    @Test
    fun `csd wird als unabhaengige Kopie geliefert`() {
        val ring = EncodedRingBuffer(10_000, 64)
        assertEquals(null, ring.csd0())

        val sps = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 103, 66))
        val pps = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 104, 46))
        ring.setCsd(sps, pps)

        val out0 = ring.csd0()!!
        assertEquals(6, out0.remaining())
        out0.put(0, 99) // Kopie verändern …
        assertEquals("csd muss bei jedem Aufruf frisch sein", 0, ring.csd0()!!.get(0).toInt())
        assertEquals(6, ring.csd1()!!.remaining())
        // Die Quelle darf nicht angefasst worden sein.
        assertEquals(0, sps.position())
    }

    @Test
    fun `usedBytes und sampleCount bleiben im Rahmen`() {
        val ring = EncodedRingBuffer(1_000, 16)
        fill(ring, 50, size = 100, gop = 4)
        assertTrue(ring.usedBytes() <= 1_000)
        assertTrue(ring.sampleCount() <= 16)
        assertTrue(ring.sampleCount() > 0)
    }
}
