package de.aweiss.delaycam.playback

/**
 * Umrechnung PTS ↔ Timeline-Position und die Zahlenformate der Oberfläche
 * (Vertrag `docs/architektur-phase1-6.md` §7).
 *
 * Bewusst ohne `String.format`/`Locale`: Die Formate sind fest deutsch (Dezimalkomma), damit
 * die Anzeige unabhängig von der Systemsprache identisch aussieht — und damit die JVM-Tests
 * deterministisch bleiben.
 */
object Timeline {

    /** Position eines Zeitstempels im Puffer als 0..1 (geclamped). */
    fun ptsToFraction(ptsUs: Long, oldestUs: Long, newestUs: Long): Float {
        if (oldestUs < 0 || newestUs < 0) return 0f
        val span = newestUs - oldestUs
        if (span <= 0L) return 1f
        val f = (ptsUs - oldestUs).toFloat() / span.toFloat()
        return f.coerceIn(0f, 1f)
    }

    fun fractionToPts(fraction: Float, oldestUs: Long, newestUs: Long): Long {
        if (oldestUs < 0 || newestUs < 0) return -1
        val f = fraction.coerceIn(0f, 1f)
        val span = newestUs - oldestUs
        return oldestUs + (span * f).toLong()
    }

    /** „12,3 s“ — auf Zehntelsekunden gerundet. */
    fun formatSeconds(us: Long): String {
        val tenths = (kotlin.math.abs(us) + 50_000L) / 100_000L
        return "${tenths / 10},${tenths % 10} s"
    }

    /** „−5,0 s“ mit echtem Minuszeichen (U+2212) fürs Status-Badge. */
    fun formatDelayBadge(us: Long): String = "−" + formatSeconds(us)

    /** „1:23,4“ — Minuten:Sekunden,Zehntel für die Timeline-Beschriftung. */
    fun formatClock(us: Long): String {
        val tenths = (kotlin.math.abs(us) + 50_000L) / 100_000L
        val minutes = tenths / 600
        val seconds = (tenths % 600) / 10
        val rest = tenths % 10
        val secondsText = if (seconds < 10) "0$seconds" else "$seconds"
        return "$minutes:$secondsText,$rest"
    }
}
