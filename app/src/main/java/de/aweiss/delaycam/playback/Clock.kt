package de.aweiss.delaycam.playback

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface

/**
 * Zeit- und Scheduling-Nähte (Plan §13.1 / §14: „Zeit immer über ein `Clock`-Interface, nie
 * direkt `System.nanoTime()` im Produktivcode").
 *
 * Der [PlaybackController] ist die einzige Stelle mit echter Timing-Logik — mit diesen drei
 * Interfaces lässt sich seine komplette Zustandsmaschine als reiner JVM-Unit-Test mit
 * virtueller Zeit fahren (siehe `src/test/.../PlaybackControllerTest.kt`), ohne Emulator,
 * ohne MediaCodec und ohne Warten in Echtzeit.
 */
interface Clock {
    /** Monotone Systemzeit in Nanosekunden — dieselbe Basis wie `releaseOutputBuffer(idx, ns)`. */
    fun nanoTime(): Long

    /** Millisekunden seit Boot (ohne Tiefschlaf) — für UI-Hinweise mit Ablaufzeit. */
    fun uptimeMillis(): Long
}

/** Produktiv-Uhr: `System.nanoTime()` ist exakt die Basis, die MediaCodec beim Pacing erwartet. */
object SystemNanoClock : Clock {
    override fun nanoTime(): Long = System.nanoTime()
    override fun uptimeMillis(): Long = SystemClock.uptimeMillis()
}

/**
 * Serialisiert alle Playback-Kommandos und den Pump-Takt auf genau einen Thread.
 * Produktiv ein [HandlerThread], im Test ein Fake mit virtueller Zeit.
 */
interface PlaybackScheduler {
    fun post(task: Runnable)
    fun postDelayed(task: Runnable, delayMs: Long)

    /** Entfernt alle noch nicht gelaufenen Tasks (beim Stoppen). */
    fun removeAll()

    /** Beendet den Thread. Nach [quit] werden keine Tasks mehr angenommen. */
    fun quit()
}

/** Produktiv-Scheduler: eigener HandlerThread (Plan §2, Thread `playback`). */
class HandlerScheduler(name: String) : PlaybackScheduler {
    private val thread = HandlerThread(name).also { it.start() }
    private val handler = Handler(thread.looper)

    override fun post(task: Runnable) {
        handler.post(task)
    }

    override fun postDelayed(task: Runnable, delayMs: Long) {
        handler.postDelayed(task, delayMs)
    }

    override fun removeAll() {
        handler.removeCallbacksAndMessages(null)
    }

    override fun quit() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}

/**
 * Mechanik-Schnittstelle des Decoders. Der [PlaybackController] entscheidet ausschließlich die
 * Policy (was wann gerendert wird), der Decoder liefert nur die Mechanik — dadurch lässt sich
 * im Test ein Fake einsetzen (Vertrag §5: „Policy dort, Mechanik hier").
 *
 * [VideoDecoder] ist die Produktiv-Implementierung; einzige Abweichung vom Vertrag ist das
 * nullbare `surface` (der Test hat keine echte Surface — produktiv ist es nie null).
 */
interface DecoderPort {
    fun configure(
        surface: Surface?,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        listener: VideoDecoder.Listener,
    )

    fun start()
    fun flush()
    fun stop()

    /** Freier Input-Buffer vorhanden? */
    fun canQueue(): Boolean

    /** false: kein Input frei ODER das Sample wurde inzwischen verdrängt. */
    fun queueSample(seq: Long): Boolean

    /** `releaseOutputBuffer(idx, renderAtNs)` — vsync-genaues Pacing. */
    fun render(bufferIndex: Int, renderAtNs: Long)

    /** `releaseOutputBuffer(idx, false)` — Frame verwerfen (Vordekodieren beim Seek). */
    fun drop(bufferIndex: Int)
}
