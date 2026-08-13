package de.aweiss.delaycam

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import android.view.WindowManager
import de.aweiss.delaycam.buffer.EncodedRingBuffer
import de.aweiss.delaycam.capture.CameraController
import de.aweiss.delaycam.capture.CaptureProfile
import de.aweiss.delaycam.capture.VideoEncoder
import de.aweiss.delaycam.device.ThermalWatcher
import de.aweiss.delaycam.export.ClipExporter
import de.aweiss.delaycam.playback.PlaybackController
import de.aweiss.delaycam.playback.VideoDecoder

/**
 * Koordinator der gesamten Pipeline: besitzt RingBuffer, Kamera, Encoder,
 * Playback und Thermal-Überwachung. Alle Methoden laufen auf dem Main-Thread
 * (Activity-Lifecycle + UI-Callbacks); die eigentliche Arbeit passiert in den
 * Modulen auf deren eigenen Threads.
 */
class DelayCamEngine(private val context: Context) {

    @Volatile
    var profile: CaptureProfile = CaptureProfile.LADDER.last()
        private set

    @Volatile
    var errorMessage: String? = null

    @Volatile
    var thermalNote: String? = null

    @Volatile
    var videoAspect: Float = 16f / 9f
        private set

    // Platzhalter-Instanzen, damit UI-Polling vor start() nie ins Leere greift.
    // start() ersetzt beide durch die echten, aufs Profil dimensionierten Objekte.
    var ring: EncodedRingBuffer = EncodedRingBuffer(1_000_000, 256)
        private set
    var playback: PlaybackController = PlaybackController(ring, VideoDecoder(ring))
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var settings: AppSettings = AppSettings()
    private var encoder: VideoEncoder? = null
    private var encoderSurface: Surface? = null
    private var camera: CameraController? = null
    private var thermal: ThermalWatcher? = null

    private var videoSurface: Surface? = null
    private var previewSurface: Surface? = null
    private var running = false
    private var playbackRunning = false

    /** Thermal-Notbremse: erzwingt beim (Neu-)Start ein kleineres Profil. */
    private var forcedProfileLabel: String? = null
    private var lastThermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var bitrateReduced = false

    fun start() {
        if (running) return
        running = true
        errorMessage = null
        settings = AppSettings.load(context)
        profile = CaptureProfile.selectBest(
            context,
            forcedProfileLabel ?: settings.profileMode,
            settings.bufferSeconds,
        )
        ring = EncodedRingBuffer(profile.bufferBytes, profile.maxSamples)
        // Decoder-Fehler landen als Klartext im Fehlerbanner der Oberfläche.
        playback = PlaybackController(ring, VideoDecoder(ring), ::fail)
        playback.setDelayUs(settings.delayUs)

        val enc = VideoEncoder(profile, ring, ::fail)
        encoder = enc
        encoderSurface = try {
            enc.start()
        } catch (e: Exception) {
            fail("Encoder lässt sich nicht starten: ${e.message}")
            return
        }
        openCamera()
        thermal = ThermalWatcher(context, ::onThermalStatus).also { it.start() }
        mainHandler.post(startPlaybackWhenReady)
    }

    fun stop() {
        if (!running) return
        running = false
        mainHandler.removeCallbacks(startPlaybackWhenReady)
        thermal?.stop()
        thermal = null
        camera?.close()
        camera = null
        encoder?.stop()
        encoder = null
        encoderSurface = null
        playback.stop()
        playbackRunning = false
    }

    private fun openCamera() {
        val surface = encoderSurface ?: return
        val cam = CameraController(context)
        camera = cam
        cam.open(
            profile,
            surface,
            if (settings.miniPreview) previewSurface else null,
            settings.sportExposure,
            ::fail,
        )
    }

    /**
     * Decoder erst konfigurieren, wenn (a) die Video-Surface da ist und
     * (b) der Encoder SPS/PPS geliefert hat — vorher gibt es kein gültiges
     * Decoder-Format. Bis dahin alle 50 ms erneut prüfen.
     */
    private val startPlaybackWhenReady = object : Runnable {
        override fun run() {
            if (!running) return
            val surface = videoSurface
            if (surface == null || ring.csd0() == null) {
                mainHandler.postDelayed(this, 50)
                return
            }
            if (!playbackRunning) {
                val rotation = computeRotationDegrees()
                videoAspect = if (rotation % 180 == 90) {
                    profile.height.toFloat() / profile.width
                } else {
                    profile.width.toFloat() / profile.height
                }
                playback.start(surface, profile.width, profile.height, rotation)
                playbackRunning = true
            }
        }
    }

    fun setVideoSurface(surface: Surface?, width: Int, height: Int) {
        if (surface === videoSurface) return
        if (playbackRunning) {
            playback.stop()
            playbackRunning = false
        }
        videoSurface = surface
        if (surface != null && running) mainHandler.post(startPlaybackWhenReady)
    }

    fun setPreviewSurface(surface: Surface?) {
        if (surface === previewSurface) return
        previewSurface = surface
        // Ein neuer Session-Output erfordert einen Neuaufbau der Kamera-Session;
        // Encoder und Puffer laufen dabei ungestört weiter.
        if (running && settings.miniPreview) {
            camera?.close()
            openCamera()
        }
    }

    fun applySettings(s: AppSettings) {
        val old = settings
        settings = s
        if (!running) return
        if (old.profileMode != s.profileMode || old.bufferSeconds != s.bufferSeconds) {
            forcedProfileLabel = null
            stop()
            start()
            return
        }
        if (old.sportExposure != s.sportExposure) camera?.setSportExposure(s.sportExposure)
        if (old.miniPreview != s.miniPreview) {
            camera?.close()
            openCamera()
        }
        if (old.delayUs != s.delayUs) playback.setDelayUs(s.delayUs)
    }

    fun saveClip(aPtsUs: Long, bPtsUs: Long, slowmoPermille: Int, onResult: (Result<Uri>) -> Unit) {
        ClipExporter.export(context, ring, profile, aPtsUs, bPtsUs, slowmoPermille, onResult)
    }

    private fun onThermalStatus(status: Int) {
        if (status == lastThermalStatus) return
        lastThermalStatus = status
        when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> {
                if (profile.label != "720p30") {
                    thermalNote = "Überhitzung: auf 720p30 heruntergeschaltet"
                    forcedProfileLabel = "720p30"
                    if (running) {
                        stop()
                        start()
                    }
                } else {
                    thermalNote = "Überhitzung: bitte Gerät abkühlen lassen"
                }
            }
            status == PowerManager.THERMAL_STATUS_MODERATE -> {
                if (!bitrateReduced) {
                    encoder?.setVideoBitrate(profile.bitrate * 7 / 10)
                    bitrateReduced = true
                }
                thermalNote = "Gerät warm: Bitrate reduziert"
            }
            else -> {
                if (bitrateReduced) {
                    encoder?.setVideoBitrate(profile.bitrate)
                    bitrateReduced = false
                }
                if (forcedProfileLabel != null && status <= PowerManager.THERMAL_STATUS_LIGHT) {
                    // Volles Profil erst beim nächsten regulären Neustart wieder aktiv.
                    forcedProfileLabel = null
                }
                thermalNote = null
            }
        }
    }

    private fun computeRotationDegrees(): Int {
        val sensor = camera?.sensorOrientation ?: 0
        @Suppress("DEPRECATION")
        val displayRotation =
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val displayDeg = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensor - displayDeg + 360) % 360
    }

    private fun fail(msg: String) {
        mainHandler.post { errorMessage = msg }
    }
}
