package de.aweiss.delaycam.playback

import android.view.Surface
import de.aweiss.delaycam.buffer.EncodedRingBuffer

/**
 * Virtuelle Zeit: [Clock] und [PlaybackScheduler] in einem. Die Playback-Zustandsmaschine läuft
 * damit im Unit-Test deterministisch und ohne echtes Warten — 10 Sekunden „Wiedergabe" dauern
 * Millisekunden.
 */
class VirtualTime : Clock, PlaybackScheduler {

    var nowNs: Long = 0L
        private set

    private class Task(val dueNs: Long, val order: Long, val run: Runnable)

    private val tasks = ArrayList<Task>()
    private var order = 0L

    override fun nanoTime(): Long = nowNs

    override fun uptimeMillis(): Long = nowNs / 1_000_000L

    override fun post(task: Runnable) {
        tasks.add(Task(nowNs, order++, task))
    }

    override fun postDelayed(task: Runnable, delayMs: Long) {
        tasks.add(Task(nowNs + delayMs * 1_000_000L, order++, task))
    }

    override fun removeAll() {
        tasks.clear()
    }

    override fun quit() {
        tasks.clear()
    }

    /** Führt alle fälligen Tasks aus, ohne die Uhr zu bewegen. */
    fun runDue() {
        var guard = 0
        while (true) {
            var next: Task? = null
            for (t in tasks) {
                if (t.dueNs > nowNs) continue
                val best = next
                if (best == null || t.dueNs < best.dueNs ||
                    (t.dueNs == best.dueNs && t.order < best.order)
                ) {
                    next = t
                }
            }
            val task = next ?: return
            tasks.remove(task)
            task.run.run()
            if (++guard > 100_000) throw IllegalStateException("Pump-Schleife terminiert nicht")
        }
    }

    /** Uhr in 1-ms-Schritten vorstellen und dabei fällige Tasks ausführen. */
    fun advance(millis: Long) {
        runDue()
        var left = millis
        while (left > 0) {
            nowNs += 1_000_000L
            left--
            runDue()
        }
    }
}

/**
 * Decoder-Attrappe: nimmt Samples entgegen und meldet den zugehörigen Frame einen Tick später
 * zurück (wie ein echter Decoder mit kurzer Latenz). Merkt sich, was gerendert, verworfen und
 * geflusht wurde — daran prüfen die Tests die Policy des Controllers.
 */
class FakeDecoder(
    private val ring: EncodedRingBuffer,
    private val time: VirtualTime,
) : DecoderPort {

    val renderedPts = ArrayList<Long>()
    val droppedPts = ArrayList<Long>()
    var flushCount = 0
        private set
    var lastRenderAtNs = 0L
        private set

    private var listener: VideoDecoder.Listener? = null
    private var running = false
    private var nextIndex = 1
    private var generation = 0
    private val ptsByIndex = HashMap<Int, Long>()

    override fun configure(
        surface: Surface?,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        listener: VideoDecoder.Listener,
    ) {
        this.listener = listener
    }

    override fun start() {
        running = true
    }

    override fun flush() {
        flushCount++
        // Wie beim echten Flush: alle unterwegs befindlichen Frames verfallen.
        generation++
    }

    override fun stop() {
        running = false
    }

    override fun canQueue(): Boolean = running

    override fun queueSample(seq: Long): Boolean {
        if (!running) return false
        val ptsUs = ring.ptsAt(seq)
        if (ptsUs < 0) return false
        val index = nextIndex++
        val gen = generation
        ptsByIndex[index] = ptsUs
        time.postDelayed({
            if (gen == generation) listener?.onFrame(ptsUs, index)
        }, DECODE_LATENCY_MS)
        return true
    }

    override fun render(bufferIndex: Int, renderAtNs: Long) {
        renderedPts.add(ptsByIndex[bufferIndex] ?: -1L)
        lastRenderAtNs = renderAtNs
    }

    override fun drop(bufferIndex: Int) {
        droppedPts.add(ptsByIndex[bufferIndex] ?: -1L)
    }

    private companion object {
        const val DECODE_LATENCY_MS = 2L
    }
}
