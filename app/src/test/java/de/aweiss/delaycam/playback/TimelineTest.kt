package de.aweiss.delaycam.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** PTS ↔ Pixel und die deutschen Zahlenformate der Oberfläche (Vertrag §7). */
class TimelineTest {

    @Test
    fun `ptsToFraction bildet den Puffer auf 0 bis 1 ab`() {
        assertEquals(0f, Timeline.ptsToFraction(1000, 1000, 5000), 0.001f)
        assertEquals(1f, Timeline.ptsToFraction(5000, 1000, 5000), 0.001f)
        assertEquals(0.5f, Timeline.ptsToFraction(3000, 1000, 5000), 0.001f)
        // Außerhalb des Puffers wird geklemmt statt extrapoliert.
        assertEquals(0f, Timeline.ptsToFraction(-999, 1000, 5000), 0.001f)
        assertEquals(1f, Timeline.ptsToFraction(999_999, 1000, 5000), 0.001f)
        // Leerer Puffer darf nicht durch null teilen.
        assertEquals(0f, Timeline.ptsToFraction(0, -1, -1), 0.001f)
        assertEquals(1f, Timeline.ptsToFraction(7, 7, 7), 0.001f)
    }

    @Test
    fun `fractionToPts ist die Umkehrung`() {
        assertEquals(1000L, Timeline.fractionToPts(0f, 1000, 5000))
        assertEquals(5000L, Timeline.fractionToPts(1f, 1000, 5000))
        assertEquals(3000L, Timeline.fractionToPts(0.5f, 1000, 5000))
        assertEquals(1000L, Timeline.fractionToPts(-3f, 1000, 5000))
        assertEquals(-1L, Timeline.fractionToPts(0.5f, -1, -1))
    }

    @Test
    fun `Sekunden werden auf Zehntel mit Komma formatiert`() {
        assertEquals("0,0 s", Timeline.formatSeconds(0))
        assertEquals("5,0 s", Timeline.formatSeconds(5_000_000))
        assertEquals("12,3 s", Timeline.formatSeconds(12_340_000))
        assertEquals("12,4 s", Timeline.formatSeconds(12_360_000)) // kaufmännisch gerundet
        assertEquals("0,5 s", Timeline.formatSeconds(500_000))
    }

    @Test
    fun `Delay-Badge nutzt ein echtes Minuszeichen`() {
        assertEquals("−5,0 s", Timeline.formatDelayBadge(5_000_000))
        assertEquals("−0,0 s", Timeline.formatDelayBadge(0))
    }

    @Test
    fun `Uhrzeitformat zeigt Minuten Sekunden und Zehntel`() {
        assertEquals("0:00,0", Timeline.formatClock(0))
        assertEquals("1:23,4", Timeline.formatClock(83_400_000))
        assertEquals("0:05,0", Timeline.formatClock(5_000_000))
        assertEquals("2:00,0", Timeline.formatClock(120_000_000))
    }
}
