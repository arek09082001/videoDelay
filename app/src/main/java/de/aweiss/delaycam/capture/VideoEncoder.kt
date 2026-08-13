package de.aweiss.delaycam.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import de.aweiss.delaycam.buffer.EncodedRingBuffer
import java.nio.ByteBuffer

/**
 * Hardware-H.264-Encoder (Vertrag `docs/architektur-phase1-6.md` §3).
 *
 * Die Kamera rendert direkt auf die von [start] gelieferte Input-Surface — es gibt im Live-Pfad
 * keinen einzigen Pixel-Zugriff und keine Bitmap (Plan §10 Regel 2). Der Encoder läuft im
 * Async-Modus; sein Callback-Thread `encoder-out` schiebt die fertigen Samples ohne Umweg in den
 * [EncodedRingBuffer].
 *
 * Annahme (Plan §3 / Vertrag §3): Surface-Encoder liefern **keine B-Frames**. Damit ist die
 * Ausgabereihenfolge gleich der Anzeigereihenfolge, die PTS steigen streng monoton und der
 * RingBuffer darf über seine seq-Nummern binär suchen.
 */
class VideoEncoder(
    private val profile: CaptureProfile,
    private val ring: EncodedRingBuffer,
    private val onError: (String) -> Unit,
) {

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var thread: HandlerThread? = null

    /**
     * Ab `stop()` true: der Callback-Thread darf dann nichts mehr am Codec anfassen. Der Callback
     * kann noch laufen, während `stop()` bereits abräumt — deshalb volatile plus try/catch.
     */
    @Volatile
    private var stopped = false

    /** Vorab alloziert: die Thermal-Drosselung soll keinen Speicher anfassen müssen. */
    private val bitrateParams = Bundle()

    /**
     * Konfiguriert und startet den Encoder und liefert die Input-Surface für die Kamera.
     * Wirft nur, wenn schon das Anlegen des Codecs scheitert — alle späteren Fehler laufen
     * über [onError].
     */
    fun start(): Surface {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            profile.width,
            profile.height,
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrate)
            // CBR: planbarer Speicherbedarf — die Puffergröße ist aus der Bitrate berechnet.
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            )
            setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
            // Kurzes GOP (0,5 s) statt Bitrate-Effizienz: es bestimmt, wie fein wir seeken und
            // loopen können. Als Float, damit der Wert nicht auf 1 s aufgerundet wird (API 25+).
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.5f)
            setInteger(MediaFormat.KEY_PRIORITY, 0)                 // 0 = realtime
            setInteger(MediaFormat.KEY_OPERATING_RATE, profile.fps)
        }

        val t = HandlerThread("encoder-out").also { it.start() }
        thread = t
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = c
        // Callback VOR configure() setzen — sonst startet der Codec im synchronen Modus.
        c.setCallback(callback, Handler(t.looper))
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = c.createInputSurface()
        inputSurface = surface
        c.start()
        return surface
    }

    /** Idempotent. Reihenfolge beachten: erst Kamera schließen, dann hier stoppen. */
    fun stop() {
        stopped = true
        val c = codec
        codec = null
        if (c != null) {
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        runCatching { inputSurface?.release() }
        inputSurface = null
        thread?.quitSafely()
        thread = null
    }

    /** Thermal-Drosselung: Bitrate im laufenden Betrieb ändern, ohne den Codec neu aufzusetzen. */
    fun setVideoBitrate(bps: Int) {
        val c = codec ?: return
        bitrateParams.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
        runCatching { c.setParameters(bitrateParams) }
    }

    private val callback = object : MediaCodec.Callback() {

        /** Surface-Input: der Encoder holt sich die Bilder selbst, Input-Buffer gibt es nie. */
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            if (stopped) return
            try {
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (isConfig) {
                    // Normalerweise kam SPS/PPS schon über onOutputFormatChanged. Manche Encoder
                    // liefern es NUR als Codec-Config-Sample — dann hier nachziehen.
                    if (ring.csd0() == null) {
                        codec.getOutputBuffer(index)?.let { storeCsdFromAnnexB(it, info) }
                    }
                } else if (info.size > 0) {
                    codec.getOutputBuffer(index)?.let { ring.append(it, info) }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                // Codec wurde parallel gestoppt — der Buffer gehört uns nicht mehr.
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val csd0 = runCatching { format.getByteBuffer("csd-0") }.getOrNull()
            val csd1 = runCatching { format.getByteBuffer("csd-1") }.getOrNull()
            if (csd0 != null) ring.setCsd(csd0, csd1)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            fail("Encoder-Fehler: ${e.diagnosticInfo}")
        }
    }

    /**
     * Notnagel: SPS/PPS aus einem Annex-B-Codec-Config-Sample herausschneiden.
     * Aufbau ist `00 00 00 01 <SPS> 00 00 00 01 <PPS>` (Startcode auch 3-Byte möglich).
     * Läuft genau einmal beim Start — die Allokationen hier sind unkritisch.
     */
    private fun storeCsdFromAnnexB(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(info.size)
        buffer.limit(info.offset + info.size)
        buffer.position(info.offset)
        buffer.get(bytes)

        // Zweiten Startcode suchen: davor liegt SPS, dahinter PPS.
        var split = -1
        var i = 3
        while (i < bytes.size - 3) {
            val threeByte = bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 &&
                bytes[i + 2].toInt() == 1
            val fourByte = bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 &&
                bytes[i + 2].toInt() == 0 && i + 3 < bytes.size && bytes[i + 3].toInt() == 1
            if (threeByte || fourByte) {
                split = i
                break
            }
            i++
        }
        if (split <= 0) {
            ring.setCsd(ByteBuffer.wrap(bytes), null)
            Log.w(TAG, "Codec-Config ohne zweiten Startcode — als csd-0 abgelegt")
            return
        }
        ring.setCsd(
            ByteBuffer.wrap(bytes.copyOfRange(0, split)),
            ByteBuffer.wrap(bytes.copyOfRange(split, bytes.size)),
        )
    }

    private fun fail(msg: String) {
        Log.e(TAG, msg)
        onError(msg)
    }

    private companion object {
        const val TAG = "DelayCamEncoder"
    }
}
