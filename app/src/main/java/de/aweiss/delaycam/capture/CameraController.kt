package de.aweiss.delaycam.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import java.util.concurrent.Executor

/**
 * Camera2-Ansteuerung (Vertrag `docs/architektur-phase1-6.md` §4).
 *
 * Die Kamera schreibt direkt auf die Encoder-Input-Surface (und optional auf die Mini-Preview) —
 * kein ImageReader, kein Pixel-Zugriff (Plan §10 Regel 2).
 *
 * Wichtig fürs Delay-Konzept: Die Bildrate muss **fest** sein. In Hallenlicht regelt die
 * Automatik sonst auf 30→24→20 fps herunter, und ein fester Zeitversatz in Frames wird zum
 * schwankenden Zeitversatz in Sekunden. Deshalb `CONTROL_AE_TARGET_FPS_RANGE = Range(fps, fps)`
 * plus 50-Hz-Antibanding (Plan §11).
 */
class CameraController(private val context: Context) {

    /** Nach [open] gültig; der Integrator rechnet daraus die Anzeige-Rotation aus. */
    @Volatile
    var sensorOrientation: Int = 0
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var executor: Executor? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    private var profile: CaptureProfile? = null
    private var encoderSurface: Surface? = null
    private var previewSurface: Surface? = null
    private var sportExposure = false
    private var onError: (String) -> Unit = {}

    /** Nach [close] true — verspätete Kamera-Callbacks dürfen dann nichts mehr aufbauen. */
    @Volatile
    private var closed = false

    fun open(
        profile: CaptureProfile,
        encoderSurface: Surface,
        previewSurface: Surface?,
        sportExposure: Boolean,
        onError: (String) -> Unit,
    ) {
        close()
        closed = false
        this.profile = profile
        this.encoderSurface = encoderSurface
        this.previewSurface = previewSurface
        this.sportExposure = sportExposure
        this.onError = onError

        if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onError("Keine Kamera-Berechtigung — bitte in den Android-Einstellungen erlauben.")
            return
        }

        val t = HandlerThread("camera").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        executor = Executor { command -> h.post(command) }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = findBackCameraId(manager)
        if (id == null) {
            onError("Keine Rückkamera gefunden.")
            return
        }
        sensorOrientation = runCatching {
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        }.getOrDefault(0)

        try {
            manager.openCamera(id, deviceCallback, h)
        } catch (e: CameraAccessException) {
            onError("Kamera lässt sich nicht öffnen: ${e.message}")
        } catch (e: SecurityException) {
            onError("Keine Kamera-Berechtigung — bitte in den Android-Einstellungen erlauben.")
        }
    }

    /** Idempotent. Gibt Session, Gerät und den Kamera-Thread frei. */
    fun close() {
        closed = true
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        thread?.quitSafely()
        thread = null
        handler = null
        executor = null
    }

    /** Sport-Belichtung im laufenden Betrieb umschalten: nur ein neuer Repeating-Request. */
    fun setSportExposure(on: Boolean) {
        sportExposure = on
        handler?.post { startRepeating() }
    }

    private fun findBackCameraId(manager: CameraManager): String? = runCatching {
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
    }.getOrNull()

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (closed) {
                camera.close()
                return
            }
            device = camera
            createSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            if (device === camera) device = null
            if (!closed) onError("Die Kamera wurde von einer anderen App übernommen.")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            if (device === camera) device = null
            if (closed) return
            val text = when (error) {
                ERROR_CAMERA_IN_USE -> "Die Kamera wird bereits benutzt."
                ERROR_MAX_CAMERAS_IN_USE -> "Zu viele Kameras gleichzeitig geöffnet."
                ERROR_CAMERA_DISABLED -> "Die Kamera ist durch eine Richtlinie gesperrt."
                ERROR_CAMERA_DEVICE -> "Die Kamera meldet einen Gerätefehler."
                else -> "Kamera-Dienst-Fehler ($error)."
            }
            this@CameraController.onError(text)
        }
    }

    private fun createSession(camera: CameraDevice) {
        val enc = encoderSurface ?: return
        val exec = executor ?: return
        val outputs = ArrayList<OutputConfiguration>(2)
        outputs.add(OutputConfiguration(enc))
        previewSurface?.let { outputs.add(OutputConfiguration(it)) }
        try {
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    exec,
                    sessionCallback,
                )
            )
        } catch (e: CameraAccessException) {
            onError("Aufnahme-Session lässt sich nicht anlegen: ${e.message}")
        } catch (e: IllegalArgumentException) {
            onError("Aufnahme-Session lässt sich nicht anlegen: ${e.message}")
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
            if (closed) {
                runCatching { s.close() }
                return
            }
            session = s
            startRepeating()
        }

        override fun onConfigureFailed(s: CameraCaptureSession) {
            if (!closed) {
                onError("Die Kamera unterstützt diese Auflösung/Bildrate nicht — bitte in den Einstellungen ein kleineres Profil wählen.")
            }
        }
    }

    /** Baut den Repeating-Request neu auf (Start und Sport-Modus-Wechsel). */
    private fun startRepeating() {
        val s = session ?: return
        val camera = device ?: return
        val p = profile ?: return
        val enc = encoderSurface ?: return
        val h = handler ?: return
        try {
            val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(enc)
            previewSurface?.let { b.addTarget(it) }

            // Feste Bildrate — der Kern der Delay-Stabilität (Plan §11).
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(p.fps, p.fps))
            b.set(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ,
            )
            // Stabilisierung kostet Latenz und beschneidet das Bild (Plan §10 Regel 5).
            b.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )
            b.set(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            )

            if (sportExposure) applySportExposure(b)

            s.setRepeatingRequest(b.build(), null, h)
        } catch (e: CameraAccessException) {
            onError("Aufnahme konnte nicht gestartet werden: ${e.message}")
        } catch (e: IllegalStateException) {
            // Session wurde parallel geschlossen — kein Fehlerfall für den Nutzer.
        }
    }

    /**
     * Sport-Belichtung (Plan §11): kurze Belichtungszeit friert die Bewegung ein, damit die
     * Einzelbilder in der Slowmotion scharf sind. Bezahlt wird mit Rauschen (höherer ISO).
     * Beide Werte werden auf die vom Gerät gemeldeten Bereiche geklemmt.
     */
    private fun applySportExposure(b: CaptureRequest.Builder) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = device?.id ?: return
        val ch = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return

        val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val manualOk = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        if (!manualOk || expRange == null || isoRange == null) {
            Log.w(TAG, "Sport-Belichtung nicht unterstützt — bleibt bei der Automatik")
            return
        }

        val exposure = 2_000_000L.coerceIn(expRange.lower, expRange.upper) // 1/500 s
        val iso = 800.coerceIn(isoRange.lower, isoRange.upper)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        // Ohne AE muss die Frame-Dauer selbst gesetzt werden, sonst bestimmt sie der Sensor.
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / (profile?.fps ?: 30))
    }

    private companion object {
        const val TAG = "DelayCamCamera"
    }
}
