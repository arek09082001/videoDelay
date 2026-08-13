package de.aweiss.delaycam.playback

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import de.aweiss.delaycam.buffer.EncodedRingBuffer

/**
 * Hardware-H.264-Decoder, der direkt auf die `SurfaceView`-Surface rendert
 * (Vertrag `docs/architektur-phase1-6.md` §5).
 *
 * Reine Mechanik: Samples aus dem RingBuffer in den Decoder schieben, fertige Frames melden,
 * auf Kommando rendern oder verwerfen. **Wann** ein Frame erscheint, entscheidet allein der
 * [PlaybackController] — hier gibt es keine Timing-Logik.
 *
 * Der Decoder läuft im Async-Modus auf dem eigenen Thread `decoder-out`. Freie Input-Indizes
 * werden in einer vorab allozierten Int-Ringliste verwaltet (kein `ArrayDeque<Integer>`, das
 * würde im Hot-Path Autoboxing-Müll erzeugen — Plan §10 Regel 1).
 */
class VideoDecoder(private val ring: EncodedRingBuffer) : DecoderPort {

    interface Listener {
        /** Läuft auf `decoder-out`! Der Buffer bleibt reserviert, bis render/drop ihn freigibt. */
        fun onFrame(ptsUs: Long, bufferIndex: Int)
        fun onDecoderError(msg: String)
    }

    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private var listener: Listener? = null

    @Volatile
    private var running = false

    // ─── Freie Input-Buffer-Indizes als Ringliste; guarded by inputLock ───
    private val freeInputs = IntArray(MAX_INPUT_SLOTS)
    private var freeHead = 0
    private var freeTail = 0
    private var freeCount = 0
    private val inputLock = Any()

    override fun configure(
        surface: Surface?,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        listener: Listener,
    ) {
        stop()
        this.listener = listener
        val csd0 = ring.csd0()
        if (csd0 == null) {
            listener.onDecoderError("Der Encoder hat noch keine Bildparameter geliefert.")
            return
        }
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height,
        ).apply {
            setByteBuffer("csd-0", csd0)
            ring.csd1()?.let { setByteBuffer("csd-1", it) }
            // Das Bild kommt aus dem Sensor gedreht; die Rotation übernimmt der Compositor,
            // nicht wir — kein Pixel wird angefasst.
            setInteger(MediaFormat.KEY_ROTATION, rotationDegrees)
        }

        try {
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            // Low-Latency nur setzen, wenn der Codec es wirklich kann — sonst lehnen manche
            // Decoder das Format komplett ab.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && supportsLowLatency(c)) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val t = HandlerThread("decoder-out").also { it.start() }
            thread = t
            c.setCallback(callback, Handler(t.looper))
            c.configure(format, surface, null, 0)
            codec = c
        } catch (e: Exception) {
            listener.onDecoderError("Decoder lässt sich nicht konfigurieren: ${e.message}")
        }
    }

    override fun start() {
        val c = codec ?: return
        clearFreeList()
        try {
            c.start()
            running = true
        } catch (e: IllegalStateException) {
            listener?.onDecoderError("Decoder lässt sich nicht starten: ${e.message}")
        }
    }

    /**
     * Verwirft alle in Arbeit befindlichen Buffer (Seek). Im Async-Modus liefert der Codec nach
     * `flush()` erst wieder Callbacks, wenn `start()` erneut aufgerufen wurde — deshalb hier
     * beides zusammen. **Alle vorher gemeldeten Buffer-Indizes sind danach ungültig**; der
     * Aufrufer darf sie nicht mehr rendern oder verwerfen.
     */
    override fun flush() {
        val c = codec ?: return
        running = false
        try {
            c.flush()
            clearFreeList()
            c.start()
            running = true
        } catch (e: IllegalStateException) {
            listener?.onDecoderError("Decoder-Flush fehlgeschlagen: ${e.message}")
        }
    }

    /** Idempotent. */
    override fun stop() {
        running = false
        val c = codec
        codec = null
        if (c != null) {
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        thread?.quitSafely()
        thread = null
        clearFreeList()
    }

    override fun canQueue(): Boolean = running && synchronized(inputLock) { freeCount > 0 }

    /**
     * Schiebt Sample [seq] in den Decoder. false, wenn gerade kein Input-Buffer frei ist oder das
     * Sample inzwischen aus dem RingBuffer verdrängt wurde (dann muss der Aufrufer neu aufsetzen).
     */
    override fun queueSample(seq: Long): Boolean {
        val c = codec ?: return false
        if (!running) return false
        val ptsUs = ring.ptsAt(seq)
        if (ptsUs < 0) return false // schon verdrängt — gar nicht erst einen Buffer holen
        val index = pollFreeInput()
        if (index < 0) return false
        val buffer = try {
            c.getInputBuffer(index)
        } catch (e: IllegalStateException) {
            return false
        }
        if (buffer == null) {
            pushFreeInput(index)
            return false
        }
        // copySampleInto macht clear()/put()/flip() und meldet -1, wenn der Schreiber uns
        // während des Kopierens überholt hat.
        val size = ring.copySampleInto(seq, buffer)
        if (size <= 0) {
            pushFreeInput(index) // Index war nie gequeued — zurück in die Freiliste
            return false
        }
        return try {
            c.queueInputBuffer(index, 0, size, ptsUs, 0)
            true
        } catch (e: IllegalStateException) {
            false
        }
    }

    override fun render(bufferIndex: Int, renderAtNs: Long) {
        val c = codec ?: return
        // Der Kern des Frame-Pacings: nicht selbst schlafen, sondern dem System sagen, WANN das
        // Bild erscheinen soll. Das SurfaceFlinger-Timing ist dadurch vsync-genau.
        runCatching { c.releaseOutputBuffer(bufferIndex, renderAtNs) }
    }

    override fun drop(bufferIndex: Int) {
        val c = codec ?: return
        runCatching { c.releaseOutputBuffer(bufferIndex, false) }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            pushFreeInput(index)
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            if (!running) {
                runCatching { codec.releaseOutputBuffer(index, false) }
                return
            }
            if (info.size <= 0) {
                runCatching { codec.releaseOutputBuffer(index, false) }
                return
            }
            listener?.onFrame(info.presentationTimeUs, index)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "Decoder-Format: $format")
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            listener?.onDecoderError("Decoder-Fehler: ${e.diagnosticInfo}")
        }
    }

    // ─── Freiliste: fester Int-Ring, keine Allokation im Hot-Path ───

    private fun pushFreeInput(index: Int) {
        synchronized(inputLock) {
            if (freeCount >= MAX_INPUT_SLOTS) return // dürfte nie passieren
            freeInputs[freeTail] = index
            freeTail = (freeTail + 1) % MAX_INPUT_SLOTS
            freeCount++
        }
    }

    private fun pollFreeInput(): Int = synchronized(inputLock) {
        if (freeCount == 0) return -1
        val index = freeInputs[freeHead]
        freeHead = (freeHead + 1) % MAX_INPUT_SLOTS
        freeCount--
        index
    }

    private fun clearFreeList() {
        synchronized(inputLock) {
            freeHead = 0
            freeTail = 0
            freeCount = 0
        }
    }

    private fun supportsLowLatency(codec: MediaCodec): Boolean = runCatching {
        codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            .isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
    }.getOrDefault(false)

    private companion object {
        const val TAG = "DelayCamDecoder"

        /** Mehr Input-Buffer vergibt kein AVC-Decoder; die Liste kann nie überlaufen. */
        const val MAX_INPUT_SLOTS = 64
    }
}
