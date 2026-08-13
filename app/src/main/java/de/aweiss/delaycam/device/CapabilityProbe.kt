package de.aweiss.delaycam.device

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.util.Size
import java.util.Locale

/**
 * Phase 0: Liest die für Delay-Cam relevanten Geräte-Fähigkeiten aus
 * (Kamera, AVC-Encoder/-Decoder, RAM) und baut daraus einen Klartext-Report.
 * Auf dessen Basis legen wir das Default-Aufnahmeprofil fest
 * (Auflösung / fps / Bitrate / Pufferlänge).
 *
 * Bewusst nur lesende API-Aufrufe: Es wird keine Kamera geöffnet und kein
 * Codec instanziiert — der Check ist damit jederzeit gefahrlos ausführbar.
 */
object CapabilityProbe {

    private const val TAG = "DelayCamProbe"
    private const val MIME_AVC = "video/avc"

    /** Profil-Kandidaten in der Fallback-Reihenfolge aus dem Plan (Abschnitt 11). */
    private val CANDIDATES = listOf(
        Candidate("1080p60", 1920, 1080, 60, bitrateMbps = 16),
        Candidate("1080p30", 1920, 1080, 30, bitrateMbps = 12),
        Candidate("720p60", 1280, 720, 60, bitrateMbps = 10),
        Candidate("720p30", 1280, 720, 30, bitrateMbps = 6),
    )

    private data class Candidate(
        val label: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateMbps: Int,
    )

    /** Prüfergebnis pro Kandidat; wird für jeden Report-Lauf neu aufgebaut. */
    private class Verdict(val candidate: Candidate) {
        var cameraOk = false
        var encoderOk = false
        var decoderOk = false
        val ok get() = cameraOk && encoderOk && decoderOk
    }

    fun buildReport(context: Context, cameraPermissionGranted: Boolean): String {
        val sb = StringBuilder(16_000)
        val verdicts = CANDIDATES.map { Verdict(it) }

        sb.appendLine("══════ DELAY-CAM GERÄTE-CHECK ══════")
        sb.appendLine()
        deviceSection(context, sb)
        sb.appendLine()
        memorySection(context, sb)
        sb.appendLine()
        sb.append("Kamera-Berechtigung: ")
        sb.appendLine(if (cameraPermissionGranted) "erteilt" else "NICHT erteilt (für diesen Check unnötig, ab Phase 1 nötig)")
        sb.appendLine()
        cameraSection(context, sb, verdicts)
        sb.appendLine()
        codecSection(sb, encoder = true, verdicts)
        sb.appendLine()
        codecSection(sb, encoder = false, verdicts)
        sb.appendLine()
        verdictSection(context, sb, verdicts)
        return sb.toString()
    }

    /** Report zeilenweise nach Logcat, abholbar per `adb logcat -s DelayCamProbe`. */
    fun logToLogcat(report: String) {
        for (line in report.lines()) Log.i(TAG, line.ifEmpty { " " })
    }

    // ───────────────────────── Abschnitte ─────────────────────────

    private fun deviceSection(context: Context, sb: StringBuilder) {
        sb.appendLine("────── Gerät ──────")
        sb.appendLine("Modell: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SDK_INT >= 31) {
            sb.appendLine("SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            val mpc = Build.VERSION.MEDIA_PERFORMANCE_CLASS
            sb.appendLine("Media-Performance-Klasse: ${if (mpc == 0) "keine deklariert" else "API $mpc"}")
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        sb.appendLine("Thermal-Status jetzt: ${thermalName(pm.currentThermalStatus)}")
    }

    private fun memorySection(context: Context, sb: StringBuilder) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        sb.appendLine("────── Speicher ──────")
        sb.appendLine("RAM gesamt: ${gb(mi.totalMem)}")
        sb.appendLine("RAM frei: ${gb(mi.availMem)}")
        sb.appendLine("Low-RAM-Gerät: ${jaNein(am.isLowRamDevice)}")
        sb.appendLine("Java-Heap-Limit der App: ${am.memoryClass} MB (large: ${am.largeMemoryClass} MB)")
        sb.appendLine("(Der Ring-Puffer liegt off-heap in einem Direct ByteBuffer — das Heap-Limit begrenzt ihn nicht.)")
    }

    private fun cameraSection(context: Context, sb: StringBuilder, verdicts: List<Verdict>) {
        sb.appendLine("────── Kameras (Camera2) ──────")
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try {
            cm.cameraIdList
        } catch (e: Exception) {
            sb.appendLine("FEHLER beim Lesen der Kameraliste: ${e.message}")
            return
        }
        if (ids.isEmpty()) {
            sb.appendLine("Keine Kameras gefunden!")
            return
        }

        for (id in ids) {
            val ch = try {
                cm.getCameraCharacteristics(id)
            } catch (e: Exception) {
                sb.appendLine("Kamera $id: FEHLER ${e.message}")
                continue
            }
            val facingConst = ch.get(CameraCharacteristics.LENS_FACING)
            val facing = when (facingConst) {
                CameraMetadata.LENS_FACING_BACK -> "Rückseite"
                CameraMetadata.LENS_FACING_FRONT -> "Front"
                else -> "extern"
            }
            sb.appendLine()
            sb.appendLine("Kamera $id ($facing)")
            sb.appendLine("  Hardware-Level: ${hwLevelName(ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))}")
            sb.appendLine("  Sensor-Rotation: ${ch.get(CameraCharacteristics.SENSOR_ORIENTATION)}°")

            val physical = ch.physicalCameraIds
            if (physical.isNotEmpty()) {
                sb.appendLine("  Logische Kamera aus physischen: ${physical.joinToString()}")
            }

            val aeRanges: List<Range<Int>> =
                ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
            sb.appendLine("  AE-FPS-Ranges: ${aeRanges.joinToString(" ") { "[${it.lower}–${it.upper}]" }}")
            val fixed30 = aeRanges.any { it.lower == 30 && it.upper == 30 }
            val fixed60 = aeRanges.any { it.lower == 60 && it.upper == 60 }
            sb.appendLine("  Feste 30er-Range: ${check(fixed30)}   Feste 60er-Range: ${check(fixed60)}")

            val anti = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)?.toList().orEmpty()
            sb.appendLine("  Antibanding 50 Hz: ${check(anti.contains(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ))}")

            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
            val manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            sb.appendLine(
                "  Manuelle Belichtung (Sport-Modus): " +
                    if (manualSensor && expRange != null) {
                        "ja (kürzeste Belichtung 1/${(1_000_000_000.0 / expRange.lower).toLong()} s)"
                    } else {
                        "nein"
                    }
            )

            val stab = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList().orEmpty()
            sb.appendLine("  Video-Stabilisierung: ${stab.joinToString { stabName(it) }} → wird auf AUS gesetzt")

            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map == null) {
                sb.appendLine("  Keine Stream-Konfiguration lesbar!")
                continue
            }
            val codecSizes = map.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty()

            sb.appendLine("  Max. Sensor-fps je Auflösung (Encoder-Surface):")
            for (c in CANDIDATES.distinctBy { it.width to it.height }) {
                val size = Size(c.width, c.height)
                if (!codecSizes.contains(size)) {
                    sb.appendLine("    ${c.width}×${c.height}: NICHT verfügbar")
                    continue
                }
                val minDur = runCatching {
                    map.getOutputMinFrameDuration(MediaCodec::class.java, size)
                }.getOrDefault(0L)
                val maxFps = if (minDur > 0) String.format(Locale.ROOT, "%.0f", 1e9 / minDur) else "keine Angabe"
                sb.appendLine("    ${c.width}×${c.height}: bis $maxFps fps")
            }

            if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
                val hs = map.highSpeedVideoSizes?.toList().orEmpty()
                sb.appendLine("  High-Speed-Modi (nur zur Info): " + hs.joinToString("; ") { s ->
                    val rates = map.getHighSpeedVideoFpsRangesFor(s).joinToString("/") { "${it.upper}" }
                    "${s.width}×${s.height}@$rates"
                })
            }

            // Fürs Default-Profil zählen nur Rückkameras — das Tablet filmt das Spielfeld.
            if (facingConst == CameraMetadata.LENS_FACING_BACK) {
                for (v in verdicts) {
                    val c = v.candidate
                    if (!codecSizes.contains(Size(c.width, c.height))) continue
                    val minDur = runCatching {
                        map.getOutputMinFrameDuration(MediaCodec::class.java, Size(c.width, c.height))
                    }.getOrDefault(Long.MAX_VALUE)
                    // minDur == 0 heißt: keine Einschränkung deklariert.
                    val frameDurOk = minDur == 0L || minDur <= 1_000_000_000L / c.fps + 100_000L
                    val aeOk = aeRanges.any { it.lower == c.fps && it.upper == c.fps }
                    if (frameDurOk && aeOk) v.cameraOk = true
                }
            }
        }
    }

    private fun codecSection(sb: StringBuilder, encoder: Boolean, verdicts: List<Verdict>) {
        sb.appendLine(if (encoder) "────── AVC-Encoder (H.264) ──────" else "────── AVC-Decoder (H.264) ──────")
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder == encoder && it.supportedTypes.any { t -> t.equals(MIME_AVC, ignoreCase = true) } }
        if (infos.isEmpty()) {
            sb.appendLine("KEINER GEFUNDEN — damit wäre das Konzept nicht umsetzbar!")
            return
        }

        for (info in infos) {
            val caps = runCatching { info.getCapabilitiesForType(MIME_AVC) }.getOrNull() ?: continue
            val video = caps.videoCapabilities ?: continue
            val hw = info.isHardwareAccelerated
            sb.appendLine()
            sb.appendLine("${info.name} ${if (hw) "[Hardware]" else "[Software]"}")
            sb.appendLine("  Max. parallele Instanzen: ${caps.maxSupportedInstances}")
            sb.appendLine(
                "  Auflösungsbereich: ${video.supportedWidths.lower}–${video.supportedWidths.upper}" +
                    " × ${video.supportedHeights.lower}–${video.supportedHeights.upper}"
            )
            sb.appendLine("  Bitrate: ${video.bitrateRange.lower / 1_000_000}–${video.bitrateRange.upper / 1_000_000} Mbit/s")
            if (encoder) {
                val cbr = caps.encoderCapabilities
                    ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
                sb.appendLine("  CBR-Modus: ${check(cbr)}")
            } else {
                val lowLatency = Build.VERSION.SDK_INT >= 30 && runCatching {
                    caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                }.getOrDefault(false)
                sb.appendLine("  Low-Latency-Feature: ${check(lowLatency)}")
            }
            for (c in CANDIDATES) {
                val supported = runCatching {
                    video.areSizeAndRateSupported(c.width, c.height, c.fps.toDouble())
                }.getOrDefault(false)
                sb.append("  ${c.label}: ${check(supported)}")
                val achievable = if (supported) runCatching {
                    video.getAchievableFrameRatesFor(c.width, c.height)
                }.getOrNull() else null
                if (achievable != null) {
                    sb.append("  (real erreichbar lt. System: ${achievable.lower.toInt()}–${achievable.upper.toInt()} fps)")
                }
                sb.appendLine()
                // Fürs Default-Profil zählen nur Hardware-Codecs.
                if (hw && supported) {
                    val v = verdicts.first { it.candidate === c }
                    if (encoder) v.encoderOk = true else v.decoderOk = true
                }
            }
        }
    }

    private fun verdictSection(context: Context, sb: StringBuilder, verdicts: List<Verdict>) {
        sb.appendLine("────── Bewertung (Kamera + HW-Encoder + HW-Decoder) ──────")
        for (v in verdicts) {
            sb.appendLine(
                "${v.candidate.label}: Kamera ${check(v.cameraOk)}  Encoder ${check(v.encoderOk)}" +
                    "  Decoder ${check(v.decoderOk)}  →  ${if (v.ok) "MÖGLICH" else "nein"}"
            )
        }
        sb.appendLine()

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalGb = mi.totalMem / 1e9

        var pick = verdicts.firstOrNull { it.ok }?.candidate
        var ramNote: String? = null
        if (pick != null && pick.fps == 60 && pick.width == 1920 && totalGb < 6.5) {
            // Plan Abschnitt 3: unter ~8 GB RAM ist 720p60 der sicherere Default.
            val fallback = verdicts.firstOrNull { it.ok && it.candidate.width == 1280 && it.candidate.fps == 60 }
            if (fallback != null) {
                ramNote = "1080p60 wäre technisch möglich, aber bei ${gb(mi.totalMem)} RAM ist 720p60 der sicherere Default (Plan, Abschnitt 3)."
                pick = fallback.candidate
            }
        }

        if (pick == null) {
            sb.appendLine("VORSCHLAG: Kein Kandidat vollständig bestätigt — Report bitte schicken, dann entscheiden wir manuell.")
        } else {
            val bufferSeconds = 60
            val bufferMb = pick.bitrateMbps.toLong() * 1_000_000 / 8 * bufferSeconds * 12 / 10 / 1_000_000
            sb.appendLine("VORSCHLAG Default-Profil: ${pick.label}, ${pick.bitrateMbps} Mbit/s CBR, $bufferSeconds s Puffer (≈ $bufferMb MB RAM)")
            ramNote?.let { sb.appendLine(it) }
            sb.appendLine("(Nur ein Vorschlag — endgültig wird das Profil gemeinsam festgelegt.)")
        }
    }

    // ───────────────────────── Helfer ─────────────────────────

    private fun check(b: Boolean) = if (b) "✓" else "✗"

    private fun jaNein(b: Boolean) = if (b) "ja" else "nein"

    private fun gb(bytes: Long) = String.format(Locale.GERMANY, "%.1f GB", bytes / 1e9)

    private fun hwLevelName(level: Int?) = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY (problematisch für unser Konzept)"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "unbekannt ($level)"
    }

    private fun stabName(mode: Int) = when (mode) {
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "AUS"
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "EIN"
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION -> "PREVIEW"
        else -> "?$mode"
    }

    private fun thermalName(status: Int) = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE (kühl)"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "unbekannt ($status)"
    }
}
