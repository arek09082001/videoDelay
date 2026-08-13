package de.aweiss.delaycam.buffer

import android.media.MediaCodec
import android.util.Log
import java.nio.ByteBuffer

/**
 * Ring-Puffer für encodierte H.264-Samples — das Herzstück der App (Plan §3, Vertrag
 * `docs/architektur-phase1-6.md` §1).
 *
 * Ablage: EIN Direct-ByteBuffer (off-heap, [capacityBytes] groß) für die Nutzdaten plus vorab
 * allozierte Index-Arrays für Offset/Größe/PTS/Keyframe-Flag. Nach dem Konstruktor wird im
 * Steady-State nie wieder alloziert (Plan §10 Regel 1); nur die Konfigurationspfade
 * ([setCsd], [csd0], [csd1]) dürfen Kopien anlegen.
 *
 * Adressierung über **seq**: eine ab 0 fortlaufende Sample-Nummer über die Lebenszeit des
 * Puffers. Gültig ist stets der lückenlose Bereich `[oldestSeq .. newestSeq]`; verdrängte seqs
 * bleiben für immer ungültig — dadurch erkennt ein Leser das Überholt-Werden zuverlässig an
 * `-1`. Der Index-Slot ist `(seq % maxSamples)`: die Slot-Abbildung wrappt, die seq-Nummern
 * selbst nie. Alle Suchen laufen deshalb über seq-Nummern, nicht über Slots (siehe
 * [seqOfKeyframeAtOrBefore]).
 *
 * Nebenläufigkeit:
 *  - Genau EIN Schreiber (`encoder-out`-Thread) ruft [setCsd] und [append].
 *  - Leser (`playback`, kurzzeitig ein Export-Thread) nutzen die Lese-API beliebig parallel.
 *  - Ein einziger Monitor [lock] schützt NUR die Index-Buchhaltung; die Byte-Kopien laufen
 *    bewusst außerhalb des Locks (Plan §3 Regel 1). Sicher wird das durch das
 *    Prüfen-Kopieren-Prüfen-Protokoll in [copySampleInto] bzw. durch
 *    Reservieren-Kopieren-Veröffentlichen in [append] — Details an den Methoden.
 *  - Sichtbarkeit der Nutzdaten: der Schreiber kopiert die Bytes VOR seinem
 *    veröffentlichenden `synchronized`-Block, der Leser liest sie NACH seinem eigenen
 *    `synchronized`-Block auf demselben Monitor. Diese Monitor-Kante stellt die Sichtbarkeit
 *    nach dem Java-Memory-Model sicher, obwohl die Kopien selbst ungeschützt laufen.
 *
 * Invariante der GOP-Verdrängung: Das älteste Sample im Puffer ist immer ein Keyframe —
 * verdrängt wird nur GOP-weise, und Samples vor dem ersten Keyframe eines Streams werden gar
 * nicht erst aufgenommen (sie wären undekodierbar). Der Pufferanfang ist damit jederzeit ein
 * gültiger Decoder-Einstiegspunkt.
 */
class EncodedRingBuffer(val capacityBytes: Int, val maxSamples: Int) {

    init {
        require(capacityBytes > 0) { "capacityBytes muss > 0 sein" }
        require(maxSamples > 0) { "maxSamples muss > 0 sein" }
    }

    /** Die EINE Nutzdaten-Allokation — off-heap, damit das Java-Heap-Limit nicht begrenzt. */
    private val store: ByteBuffer = ByteBuffer.allocateDirect(capacityBytes)

    /**
     * Pro Thread eine eigene Sicht (`duplicate`) auf [store]: `position`/`limit` eines
     * ByteBuffers sind nicht threadsicher, der Speicher dahinter ist aber für alle Sichten
     * derselbe. So können Schreiber und mehrere Leser gleichzeitig kopieren, ohne sich die
     * Buffer-Zeiger zu zerschießen. Alloziert wird einmal pro Thread beim ersten Zugriff
     * (playback: beim Start; Export: einmal pro Export) — im Steady-State nie.
     */
    private val views: ThreadLocal<ByteBuffer> = ThreadLocal.withInitial { store.duplicate() }

    // ─── Index-Arrays, adressiert über slot = (seq % maxSamples); guarded by lock ───
    private val offsets = IntArray(maxSamples)      // Byte-Offset des Samples in store
    private val sizes = IntArray(maxSamples)        // Länge in Bytes
    private val samplePtsUs = LongArray(maxSamples) // Presentation-Timestamp in µs
    private val keyFlag = BooleanArray(maxSamples)  // BUFFER_FLAG_KEY_FRAME gesetzt?

    /** Monitor NUR um die Index-Buchhaltung — nie um eine Byte-Kopie herum halten. */
    private val lock = Any()

    // ─── Zustand, guarded by lock ───
    private var tailSeq = 0L    // ältester gültiger seq; leer ⇔ tailSeq == headSeq
    private var headSeq = 0L    // nächster zu vergebender seq (newest = headSeq − 1)
    private var writePos = 0    // nächste Schreibposition in store; Invariante: leer ⇒ 0
    private var generation = 0L // von clear() erhöht → in-flight-append erkennt den Reset
    private var csd0Bytes: ByteArray? = null
    private var csd1Bytes: ByteArray? = null
    private var warnedNonMonotonic = false
    private var warnedNoKeyframe = false

    // ───────────────────────── Writer-Seite (nur encoder-out-Thread) ─────────────────────────

    /**
     * SPS/PPS separat ablegen (Plan §3 Regel 3) — nötig für die Decoder-Konfiguration und den
     * MP4-Export. Es wird kopiert; Position/Limit der übergebenen Buffer bleiben unberührt.
     */
    fun setCsd(csd0: ByteBuffer, csd1: ByteBuffer?) {
        val c0 = toByteArray(csd0)
        val c1 = csd1?.let { toByteArray(it) }
        synchronized(lock) {
            csd0Bytes = c0
            csd1Bytes = c1
        }
    }

    /**
     * Hängt ein encodiertes Sample an: [info]`.size` Bytes ab [info]`.offset` aus [data].
     *
     * Drei Schritte, damit der Lock nie eine Byte-Kopie umschließt:
     *  1) **Unter Lock reservieren:** alte GOPs verdrängen, bis Bytes UND ein Slot reichen,
     *     dann die Zielposition bestimmen. Der reservierte Bereich gehört ab jetzt zu keinem
     *     gültigen Sample mehr — kein Leser kann ihn über die Index-Buchhaltung erreichen.
     *  2) **Ohne Lock kopieren.** Ein Leser, der noch mitten in der Kopie eines soeben
     *     verdrängten Samples steckt, liest dabei schlimmstenfalls halb überschriebenen
     *     Datensalat — den erkennt und verwirft er selbst über Schritt 3 seines
     *     Prüfen-Kopieren-Prüfen-Protokolls (siehe [copySampleInto]).
     *  3) **Unter Lock veröffentlichen:** erst jetzt wird der Index-Eintrag geschrieben und
     *     `headSeq` erhöht — Leser sehen das Sample nie halbfertig.
     */
    fun append(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        // Codec-Config läuft über setCsd — hier defensiv ignorieren (Vertrag §1).
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        val size = info.size
        if (size <= 0) return // z. B. leeres End-of-Stream-Sample
        if (size > capacityBytes) {
            // Vertrag: still verwerfen und nur warnen — niemals werfen.
            Log.w(TAG, "Sample ($size B) größer als der gesamte Puffer ($capacityBytes B) — verworfen")
            return
        }
        if (info.offset < 0 || info.offset.toLong() + size > data.capacity()) {
            Log.w(TAG, "BufferInfo passt nicht zur Quelle (offset=${info.offset}, size=$size, cap=${data.capacity()}) — verworfen")
            return
        }
        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0

        // ---- Schritt 1: unter Lock Platz schaffen und den Zielbereich reservieren ----
        val off: Int
        val gen: Long
        var pts = info.presentationTimeUs
        synchronized(lock) {
            gen = generation

            // GOP-weise verdrängen, bis sowohl ein freier Slot (count < maxSamples) als auch
            // genügend zusammenhängende Bytes da sind. Ein einzelnes großes Sample kann dabei
            // MEHRERE GOPs verdrängen: die Schleife läuft einfach weiter, bis placeForLocked()
            // eine Position findet — spätestens beim leeren Puffer passt alles, denn
            // size ≤ capacityBytes ist oben bereits sichergestellt. Jede Runde entfernt
            // mindestens ein Sample, die Schleife terminiert also garantiert.
            while (headSeq != tailSeq &&
                (headSeq - tailSeq >= maxSamples || placeForLocked(size) < 0)
            ) {
                evictOldestGopLocked()
            }

            // Randfall: Puffer ist (oder wurde soeben) leer und das Sample ist KEIN Keyframe.
            // Es zu speichern würde die Invariante „ältestes Sample = Keyframe“ verletzen und
            // wäre für den Decoder wertlos. Kommt nur vor, wenn ein Stream nicht mit einem
            // Keyframe beginnt oder eine einzelne GOP größer als der ganze Puffer ist
            // (Fehlkonfiguration). Verwerfen und auf den nächsten Keyframe warten.
            if (headSeq == tailSeq && !isKey) {
                if (!warnedNoKeyframe) {
                    warnedNoKeyframe = true
                    Log.w(TAG, "Sample ohne vorausgehenden Keyframe verworfen — warte auf Keyframe")
                }
                return
            }

            off = placeForLocked(size)
            if (off < 0) {
                // Nach der Verdrängungsschleife unmöglich; reiner Schutz gegen Regressionen.
                Log.e(TAG, "Interner Fehler: kein Platz trotz Verdrängung (size=$size)")
                return
            }

            // pts defensiv streng monoton halten — die binären Suchen verlassen sich darauf.
            // Annahme (Vertrag §3): Surface-Encoder liefern keine B-Frames, pts-Reihenfolge ==
            // seq-Reihenfolge; real tritt dieser Zweig also nie ein.
            if (headSeq != tailSeq) {
                val newest = samplePtsUs[slot(headSeq - 1)]
                if (pts <= newest) {
                    if (!warnedNonMonotonic) {
                        warnedNonMonotonic = true
                        Log.w(TAG, "Nicht-monotone pts vom Encoder ($pts ≤ $newest) — auf ${newest + 1} geklemmt")
                    }
                    pts = newest + 1
                }
            }
        }

        // ---- Schritt 2: ohne Lock kopieren ----
        // Quelle ist der MediaCodec-Output-Buffer; Position/Limit werden hier gemäß BufferInfo
        // gesetzt (und damit verändert) — unkritisch, denn der Encoder-Callback released den
        // Buffer unmittelbar nach diesem Aufruf.
        data.limit(info.offset + size)
        data.position(info.offset)
        val view = views.get()
        view.limit(off + size)
        view.position(off)
        view.put(data) // Direct→Direct-Bulk-Kopie (memcpy), keine Allokation

        // ---- Schritt 3: unter Lock veröffentlichen ----
        synchronized(lock) {
            if (generation != gen) {
                // clear() kam uns zwischen Schritt 1 und 3 dazwischen: Reservierung und
                // Buchhaltung passen nicht mehr zusammen. Sample verwerfen — nach clear()
                // wird ohnehin ab dem nächsten Keyframe neu aufgesetzt.
                return
            }
            val s = slot(headSeq)
            offsets[s] = off
            sizes[s] = size
            samplePtsUs[s] = pts
            keyFlag[s] = isKey
            headSeq++
            // Schreibposition normalisieren: exakt am Pufferende gelandet ⇒ logisch bei 0
            // weiter. So bleibt die Fallunterscheidung in placeForLocked() eindeutig.
            val end = off + size
            writePos = if (end == capacityBytes) 0 else end
        }
    }

    // ───────────────────────── Beide Seiten ─────────────────────────

    /**
     * Verwirft alle Samples. Die seq-Zählung läuft WEITER (Vertrag: verdrängte seqs bleiben
     * für immer ungültig) — so bekommen in-flight-Leser danach sauber `-1` statt versehentlich
     * neue Daten unter alter Nummer. CSD bleibt erhalten: SPS/PPS gehören zur
     * Encoder-Konfiguration, nicht zum Pufferinhalt.
     */
    fun clear() {
        synchronized(lock) {
            tailSeq = headSeq
            writePos = 0
            generation++
        }
    }

    fun isEmpty(): Boolean = synchronized(lock) { headSeq == tailSeq }

    /** Kopie von SPS (csd-0); null, solange der Encoder das Format noch nicht geliefert hat. */
    fun csd0(): ByteBuffer? = wrapCopy(synchronized(lock) { csd0Bytes })

    /** Kopie von PPS (csd-1); je nach Encoder auch dauerhaft null. */
    fun csd1(): ByteBuffer? = wrapCopy(synchronized(lock) { csd1Bytes })

    // ───────────────────────── Leser-Seite ─────────────────────────

    fun oldestSeq(): Long = synchronized(lock) { if (headSeq == tailSeq) -1L else tailSeq }

    fun newestSeq(): Long = synchronized(lock) { if (headSeq == tailSeq) -1L else headSeq - 1 }

    fun oldestPtsUs(): Long =
        synchronized(lock) { if (headSeq == tailSeq) -1L else samplePtsUs[slot(tailSeq)] }

    fun newestPtsUs(): Long =
        synchronized(lock) { if (headSeq == tailSeq) -1L else samplePtsUs[slot(headSeq - 1)] }

    /** Zeitspanne ältestes→neuestes Sample; 0 bei leerem Puffer (und bei genau einem Sample). */
    fun durationUs(): Long = synchronized(lock) {
        if (headSeq == tailSeq) 0L
        else samplePtsUs[slot(headSeq - 1)] - samplePtsUs[slot(tailSeq)]
    }

    fun ptsAt(seq: Long): Long = synchronized(lock) {
        if (seq < tailSeq || seq >= headSeq) -1L else samplePtsUs[slot(seq)]
    }

    fun isKeyframe(seq: Long): Boolean = synchronized(lock) {
        seq >= tailSeq && seq < headSeq && keyFlag[slot(seq)]
    }

    /**
     * seq des spätesten Keyframes mit `pts ≤ ptsUs`; -1, wenn es keinen gibt (Ziel liegt vor
     * dem Pufferanfang oder der Puffer ist leer).
     *
     * Zweistufig: erst binäre Suche nach dem spätesten Sample mit `pts ≤ ptsUs`, von dort
     * rückwärts zum Keyframe laufen (höchstens eine GOP ≈ 0,5 s ⇒ wenige Dutzend Schritte).
     *
     * Die binäre Suche läuft über SEQ-NUMMERN `[tailSeq .. headSeq−1]`, NICHT über Slots:
     * Der gültige Bereich ist in seq lückenlos und die pts darüber streng monoton — im
     * Slot-Array dagegen wrappt die Abbildung (slot = seq % maxSamples), dort wäre
     * „sortiert“ schlicht falsch. Erst beim Array-Zugriff wird die mittlere seq in ihren
     * Slot übersetzt. Weiter als maxSamples können oldest und newest nie auseinanderliegen
     * (die Verdrängung hält count ≤ maxSamples), jede seq im Suchbereich hat also garantiert
     * ihren eigenen, noch gültigen Slot.
     */
    fun seqOfKeyframeAtOrBefore(ptsUs: Long): Long = synchronized(lock) {
        if (headSeq == tailSeq) return -1L
        var lo = tailSeq
        var hi = headSeq - 1
        if (samplePtsUs[slot(lo)] > ptsUs) return -1L // schon das älteste Sample ist zu neu
        // Invariante: pts(lo) ≤ ptsUs. Aufgerundetes mid ⇒ Suche nach dem LETZTEN Treffer.
        while (lo < hi) {
            val mid = lo + (hi - lo + 1) / 2
            if (samplePtsUs[slot(mid)] <= ptsUs) lo = mid else hi = mid - 1
        }
        // Rückwärts zum GOP-Anfang. Da das älteste Sample per Invariante ein Keyframe ist,
        // endet die Schleife spätestens bei tailSeq mit einem Treffer.
        var s = lo
        while (s >= tailSeq) {
            if (keyFlag[slot(s)]) return s
            s--
        }
        -1L // nur erreichbar, falls die Keyframe-Invariante je verletzt würde
    }

    /** Erster seq mit `pts ≥ ptsUs`; -1, wenn selbst das neueste Sample älter ist (oder leer). */
    fun seqAtOrAfter(ptsUs: Long): Long = synchronized(lock) {
        if (headSeq == tailSeq) return -1L
        var lo = tailSeq
        var hi = headSeq - 1
        if (samplePtsUs[slot(hi)] < ptsUs) return -1L
        // Invariante: pts(hi) ≥ ptsUs. Abgerundetes mid ⇒ Suche nach dem ERSTEN Treffer.
        while (lo < hi) {
            val mid = lo + (hi - lo) / 2
            if (samplePtsUs[slot(mid)] >= ptsUs) hi = mid else lo = mid + 1
        }
        lo
    }

    /**
     * Kopiert Sample [seq] nach [dst] (Konvention: macht `dst.clear()`, schreibt, `dst.flip()`).
     * Rückgabe: Byte-Anzahl oder -1, wenn das Sample (inzwischen) verdrängt ist.
     *
     * Überhol-sicher OHNE Lock um die Kopie — das Prüfen-Kopieren-Prüfen-Protokoll:
     *  1) **Prüfen:** unter Lock die Gültigkeit von seq prüfen und offset/size als Snapshot
     *     lesen. Ab hier kann der Schreiber uns jederzeit verdrängen.
     *  2) **Kopieren:** ohne Lock. Überschreibt der Schreiber währenddessen genau diesen
     *     Bereich, lesen wir Datensalat — aber nie außerhalb des Puffers, denn offset/size
     *     stammen aus einem konsistenten Snapshot.
     *  3) **Prüfen:** unter Lock erneut `seq ≥ tailSeq` testen. Gilt das noch, hat der
     *     Schreiber den Bereich garantiert NICHT angefasst, denn er beschreibt nur Bereiche,
     *     deren Samples er vorher (unter demselben Lock) verdrängt hat — die Kopie ist gültig.
     *     Sonst -1: die Kopie ist womöglich Müll, der Aufrufer muss neu aufsetzen (Playback:
     *     auf den nächsten Keyframe klemmen, „Puffer aufgeholt“; Export: abbrechen).
     *
     * Der Gewinn: die teure memcpy großer Keyframes blockiert nie den Encoder-Thread — der
     * Lock wird nur für wenige Feldzugriffe gehalten.
     */
    fun copySampleInto(seq: Long, dst: ByteBuffer): Int {
        val off: Int
        val size: Int
        // Schritt 1: Snapshot unter Lock.
        synchronized(lock) {
            if (seq < tailSeq || seq >= headSeq) return -1
            val s = slot(seq)
            off = offsets[s]
            size = sizes[s]
        }
        if (dst.capacity() < size) {
            // Programmierfehler des Aufrufers (Ziel-Buffer zu klein). Kein Throw im Hot-Path —
            // wie „verdrängt“ behandeln, damit der Aufrufer geordnet neu aufsetzt.
            Log.e(TAG, "copySampleInto: dst zu klein (${dst.capacity()} B < $size B)")
            return -1
        }
        // Schritt 2: Kopie ohne Lock über die Thread-eigene Sicht auf den Speicher. Samples
        // sind nie gesplittet, [off, off+size) ist also immer ein zusammenhängendes Stück.
        val view = views.get()
        view.limit(off + size)
        view.position(off)
        dst.clear()
        dst.put(view)
        dst.flip()
        // Schritt 3: Gegenprüfung unter Lock.
        synchronized(lock) {
            if (seq < tailSeq) return -1
        }
        return size
    }

    // ───────────────────────── Statistik (Debug/Settings-Anzeige) ─────────────────────────

    /**
     * Belegter Bereich des Rings in Bytes, INKLUSIVE eines eventuellen Verschnitts am
     * Pufferende — also genau die Bytes, die für neue Samples momentan nicht zur Verfügung
     * stehen. Abgeleitet aus der Lage von writePos relativ zum ältesten Sample.
     */
    fun usedBytes(): Int = synchronized(lock) {
        if (headSeq == tailSeq) return 0
        val tailOff = offsets[slot(tailSeq)]
        when {
            writePos > tailOff -> writePos - tailOff                 // lineares Layout
            writePos < tailOff -> capacityBytes - tailOff + writePos // gewrapptes Layout
            else -> capacityBytes                                    // randvoll
        }
    }

    fun sampleCount(): Int = synchronized(lock) { (headSeq - tailSeq).toInt() }

    // ───────────────────────── Interne Helfer ─────────────────────────

    /**
     * Schreibposition für ein Sample von [size] Bytes, oder -1, wenn es ohne weitere
     * Verdrängung nicht passt. Nur unter [lock] aufrufen.
     *
     * Wrap-Around mit Verschnitt: Samples werden NIE gesplittet (Vertrag §1). Reicht der
     * Platz zwischen writePos und dem Pufferende nicht, wird ab Offset 0 geschrieben — die
     * Lücke `[writePos, capacityBytes)` wird dann zum „Verschnitt“. Der Verschnitt braucht
     * keine eigene Buchhaltung: Die Frei-Platz-Rechnung arbeitet nur mit writePos und dem
     * Offset des ältesten Samples (tailOff).
     *
     *  - Lineares Layout (writePos > tailOff): belegt ist `[tailOff, writePos)`; frei sind
     *    das Endstück `[writePos, cap)` und der Anfang `[0, tailOff)`. Wegen des
     *    Split-Verbots zählt nur, ob das Sample in EINES der beiden Stücke passt.
     *  - Gewrapptes Layout (writePos < tailOff): belegt ist `[tailOff, cap) + [0, writePos)`;
     *    frei ist nur die Mitte `[writePos, tailOff)`. Der Verschnitt liegt hinter dem
     *    letzten Vor-Wrap-Sample und steckt damit automatisch im Bereich `[tailOff, cap)`.
     *    Frei wird er genau dann, wenn die Verdrängung das letzte Vor-Wrap-Sample entfernt:
     *    tailOff springt dann an den Pufferanfang, das Layout ist wieder linear und
     *    `[writePos, cap)` zählt automatisch wieder als frei — nichts muss zurückgebucht werden.
     *  - writePos == tailOff bei nicht-leerem Puffer: kein einziges Byte frei.
     */
    private fun placeForLocked(size: Int): Int {
        if (headSeq == tailSeq) return writePos // leer ⇒ writePos == 0, alles frei
        val tailOff = offsets[slot(tailSeq)]
        return when {
            writePos > tailOff -> when {
                capacityBytes - writePos >= size -> writePos // passt am Stück bis zum Ende
                tailOff >= size -> 0                         // Wrap: ab 0, Endstück wird Verschnitt
                else -> -1
            }
            writePos < tailOff -> if (tailOff - writePos >= size) writePos else -1
            else -> -1 // randvoll
        }
    }

    /**
     * Verdrängt genau eine GOP vom alten Ende: das älteste Sample (per Invariante ein
     * Keyframe) plus alle unmittelbar folgenden Nicht-Keyframes. Danach beginnt der Puffer
     * wieder mit einem Keyframe — der Pufferanfang bleibt jederzeit dekodierbar (Plan §3
     * Regel 2). Es werden nur Indizes bewegt, keine Bytes: Speicher gilt als frei, sobald
     * tailSeq darüber hinweg ist. Leert die Verdrängung den Puffer komplett, wandert die
     * Schreibposition auf 0 zurück (Invariante „leer ⇒ writePos == 0“ — maximiert den
     * zusammenhängenden Platz für das nächste, offenbar große Sample).
     */
    private fun evictOldestGopLocked() {
        tailSeq++
        while (tailSeq < headSeq && !keyFlag[slot(tailSeq)]) tailSeq++
        if (tailSeq == headSeq) writePos = 0
    }

    /** seq → Index-Slot. Nur für gültige (nicht-negative) seqs aufrufen. */
    private fun slot(seq: Long): Int = (seq % maxSamples).toInt()

    /** Inhalt ab `position` als frisches ByteArray; Position des Aufrufers bleibt unberührt. */
    private fun toByteArray(src: ByteBuffer): ByteArray {
        val d = src.duplicate() // Konfigurationspfad — Allokation hier erlaubt
        val out = ByteArray(d.remaining())
        d.get(out)
        return out
    }

    private fun wrapCopy(bytes: ByteArray?): ByteBuffer? =
        bytes?.let { ByteBuffer.wrap(it.copyOf()) }

    private companion object {
        const val TAG = "DelayCamRing"
    }
}
