package de.aweiss.delaycam.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import de.aweiss.delaycam.buffer.EncodedRingBuffer
import de.aweiss.delaycam.capture.CaptureProfile
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exportiert einen Bereich [A,B] aus dem RingBuffer als MP4 (Plan Abschnitt 7):
 * reines Remuxen der bereits encodierten AVC-Samples per MediaMuxer, KEIN
 * Re-Encode — der Export dauert dadurch nur Millisekunden. Slowmotion entsteht
 * allein durch das Strecken der Zeitstempel.
 *
 * Läuft auf einem eigenen kurzlebigen Thread; `onResult` wird immer auf dem
 * Main-Thread geliefert.
 */
object ClipExporter {

    /**
     * EIN Direct-Buffer für alle Samples (Regel Abschnitt 10: keine Allokation
     * pro Sample). 2 MB reichen locker: das größte I-Frame liegt selbst bei
     * 16 Mbit/s CBR weit darunter.
     */
    private const val SAMPLE_BUFFER_BYTES = 2 * 1024 * 1024

    private const val MSG_OVERWRITTEN =
        "Der Puffer hat den Bereich inzwischen überschrieben — bitte direkt nach dem Einfrieren exportieren."
    private const val MSG_EMPTY = "Der Puffer ist leer — es gibt nichts zu exportieren."

    /** Eigene Fehlerklasse: nur deren (deutsche) Meldungen gehen unverändert an die UI. */
    private class ExportException(message: String) : Exception(message)

    fun export(
        context: Context,
        ring: EncodedRingBuffer,
        profile: CaptureProfile,
        aPtsUs: Long,
        bPtsUs: Long,
        slowmoPermille: Int,               // 1000 = Echtzeit; 250 ⇒ Datei läuft 4× so lang
        onResult: (Result<Uri>) -> Unit,
    ) {
        val main = Handler(Looper.getMainLooper())
        Thread({
            val result = try {
                Result.success(doExport(context, ring, profile, aPtsUs, bPtsUs, slowmoPermille))
            } catch (t: Throwable) {
                // Erwartete Fehler tragen bereits eine deutsche Meldung; alles
                // andere (Muxer/IO) bekommt einen deutschen Rahmen.
                val msg = (t as? ExportException)?.message
                    ?: "Export fehlgeschlagen: ${t.message ?: t.javaClass.simpleName}"
                Result.failure(Exception(msg, t))
            }
            main.post { onResult(result) }
        }, "clip-export").start()
    }

    /** Läuft auf dem Export-Thread; wirft bei jedem Fehler (Aufräumen inklusive). */
    private fun doExport(
        context: Context,
        ring: EncodedRingBuffer,
        profile: CaptureProfile,
        aPtsUs: Long,
        bPtsUs: Long,
        slowmoPermille: Int,
    ): Uri {
        if (ring.isEmpty()) throw ExportException(MSG_EMPTY)
        val csd0 = ring.csd0()
            ?: throw ExportException("Encoder-Konfiguration (SPS/PPS) fehlt — Export nicht möglich.")
        val csd1 = ring.csd1()

        // A/B defensiv sortieren (PlaybackController sortiert bereits) und
        // Division durch 0 ausschließen.
        val a = minOf(aPtsUs, bPtsUs)
        val b = maxOf(aPtsUs, bPtsUs)
        val slowmo = slowmoPermille.coerceAtLeast(1)

        // Start beim Keyframe auf/vor A: Die Frames zwischen Keyframe und A
        // braucht der Decoder als Vorlauf, sonst wäre der Clipanfang nicht
        // dekodierbar. Fallback: ältestes Sample (per Ring-Invariante ebenfalls
        // ein Keyframe).
        var seq = ring.seqOfKeyframeAtOrBefore(a)
        if (seq < 0) seq = ring.oldestSeq()
        if (seq < 0) throw ExportException(MSG_EMPTY)

        val format = MediaFormat
            .createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, profile.width, profile.height)
            .apply {
                setByteBuffer("csd-0", csd0)
                if (csd1 != null) setByteBuffer("csd-1", csd1)
                setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
            }

        // ── MediaStore-Ziel mit IS_PENDING-Flow ──────────────────────────────
        // IS_PENDING=1: Die Datei ist für Galerie & andere Apps unsichtbar,
        // solange sie noch geschrieben wird. Erst nach erfolgreichem Muxen wird
        // sie mit IS_PENDING=0 „veröffentlicht“. Bricht der Export ab, löschen
        // wir den Eintrag wieder — es bleibt keine halbe Datei zurück.
        val displayName =
            "delaycam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + ".mp4"
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DelayCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            pending
        ) ?: throw ExportException("MediaStore-Eintrag konnte nicht angelegt werden.")

        var pfd: ParcelFileDescriptor? = null
        var muxer: MediaMuxer? = null
        try {
            pfd = resolver.openFileDescriptor(uri, "rw")
                ?: throw ExportException("Zieldatei konnte nicht geöffnet werden.")
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(format)
            muxer.start()

            val sampleBuf = ByteBuffer.allocateDirect(SAMPLE_BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            var basePtsUs = -1L
            var written = 0

            // newestSeq() wird pro Runde neu gelesen — der Encoder darf während
            // des Exports weiterschreiben; wir enden ohnehin am B-Marker.
            while (seq <= ring.newestSeq()) {
                val ptsUs = ring.ptsAt(seq)
                if (ptsUs < 0) {
                    // seq lag eben noch im gültigen Bereich → der Writer hat
                    // uns überholt und das Sample verdrängt.
                    throw ExportException(MSG_OVERWRITTEN)
                }
                if (ptsUs > b) break // Ende: erstes Sample hinter B (B selbst gehört noch dazu)

                // Keyframe-Flag VOR dem Kopieren lesen: Das Flag pro seq ist
                // unveränderlich, und copySampleInto ist danach der letzte
                // Gültigkeits-Check — so kann nie ein falsches Flag in die
                // Datei geschrieben werden, wenn der Writer uns genau hier
                // überholt (dann liefert die Kopie -1 und wir brechen ab).
                val keyframe = ring.isKeyframe(seq)
                val size = ring.copySampleInto(seq, sampleBuf) // macht clear/put/flip
                if (size < 0) throw ExportException(MSG_OVERWRITTEN)

                // ── PTS-Streckung (Slowmo ohne Re-Encode) ────────────────────
                // Die Samples bleiben byte-identisch, nur ihre Zeitstempel
                // werden auseinandergezogen:
                //   pts' = (pts − basePts) * 1000 / slowmoPermille
                // basePts = pts des ERSTEN geschriebenen Samples ⇒ der Clip
                // beginnt bei 0. Bei 250 ‰ wird aus 1 s Material eine 4 s lange
                // Datei — jeder Player spielt sie „echt“ langsam ab.
                if (basePtsUs < 0) basePtsUs = ptsUs
                info.offset = 0
                info.size = size
                info.presentationTimeUs = (ptsUs - basePtsUs) * 1000L / slowmo
                info.flags = if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(track, sampleBuf, info)
                written++
                seq++
            }

            if (written == 0) {
                throw ExportException("Im gewählten Bereich liegen keine Bilder.")
            }

            // Erst Muxer und Datei sauber schließen, DANN veröffentlichen.
            muxer.stop()
            muxer.release()
            muxer = null
            pfd.close()
            pfd = null

            val publish = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, publish, null, null)
            return uri
        } catch (t: Throwable) {
            // ── Abbruch-Aufräumen ────────────────────────────────────────────
            // Muxer/PFD immer freigeben (release() räumt auch einen bereits
            // gestarteten Muxer ab) und den MediaStore-Eintrag löschen, damit
            // keine unsichtbare Pending-Leiche bzw. kaputte Datei zurückbleibt.
            runCatching { muxer?.release() }
            runCatching { pfd?.close() }
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }
}
