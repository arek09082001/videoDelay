package de.aweiss.delaycam.capture

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodec
import android.media.MediaCodecList
import android.util.Size

/**
 * Aufnahmeprofil (Auflösung / fps / Bitrate / Pufferlänge) inklusive der
 * Fallback-Leiter aus dem Plan (Abschnitt 11):
 * 1080p60 → 1080p30 → 720p60 → 720p30.
 */
data class CaptureProfile(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val bufferSeconds: Int,
) {
    /** Puffergröße: Bitrate ÷ 8 × Sekunden × 1,2 Reserve (Plan Abschnitt 3). */
    val bufferBytes: Int
        get() = (bitrate.toLong() / 8L * bufferSeconds * 12L / 10L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Sample-Slots: ein Slot pro Frame plus Reserve für Timing-Schwankungen. */
    val maxSamples: Int get() = fps * bufferSeconds + 128

    val frameDurationUs: Long get() = 1_000_000L / fps

    companion object {
        val LADDER = listOf(
            CaptureProfile("1080p60", 1920, 1080, 60, 16_000_000, 60),
            CaptureProfile("1080p30", 1920, 1080, 30, 12_000_000, 60),
            CaptureProfile("720p60", 1280, 720, 60, 10_000_000, 60),
            CaptureProfile("720p30", 1280, 720, 30, 6_000_000, 60),
        )

        fun byLabel(label: String): CaptureProfile? = LADDER.firstOrNull { it.label == label }

        /**
         * Wählt das beste vom Gerät vollständig unterstützte Profil
         * (Kamera + HW-Encoder + HW-Decoder). Bei explizitem Wunschprofil wird
         * nicht geprüft — der Nutzer hat entschieden. Die Pufferlänge wird auf
         * maximal die Hälfte des aktuell freien RAMs geclampt (Plan Abschnitt 11).
         */
        fun selectBest(context: Context, requestedLabel: String?, bufferSeconds: Int): CaptureProfile {
            val explicit = requestedLabel?.takeIf { it != "auto" }?.let { byLabel(it) }
            val base = explicit
                ?: LADDER.firstOrNull { isSupported(context, it) }
                ?: LADDER.last()
            return base.copy(bufferSeconds = clampBufferSeconds(context, base, bufferSeconds))
        }

        private fun clampBufferSeconds(context: Context, p: CaptureProfile, wanted: Int): Int {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            var seconds = wanted.coerceIn(30, 120)
            while (seconds > 30 && p.copy(bufferSeconds = seconds).bufferBytes > mi.availMem / 2) {
                seconds /= 2
            }
            return seconds.coerceAtLeast(30)
        }

        fun isSupported(context: Context, p: CaptureProfile): Boolean =
            cameraSupports(context, p) &&
                codecSupports(p, encoder = true) &&
                codecSupports(p, encoder = false)

        /** Rückkamera muss die Größe fürs Encoder-Surface schaffen UND eine feste (fps,fps)-AE-Range bieten. */
        private fun cameraSupports(context: Context, p: CaptureProfile): Boolean {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            return try {
                cm.cameraIdList.any { id ->
                    val ch = cm.getCameraCharacteristics(id)
                    if (ch.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) {
                        return@any false
                    }
                    val aeOk = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        ?.any { it.lower == p.fps && it.upper == p.fps } == true
                    val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?: return@any false
                    val size = Size(p.width, p.height)
                    val hasSize = map.getOutputSizes(MediaCodec::class.java)?.contains(size) == true
                    val minDur = if (hasSize) {
                        runCatching { map.getOutputMinFrameDuration(MediaCodec::class.java, size) }
                            .getOrDefault(Long.MAX_VALUE)
                    } else {
                        Long.MAX_VALUE
                    }
                    // minDur == 0 heißt: keine Einschränkung deklariert.
                    val durOk = hasSize && (minDur == 0L || minDur <= 1_000_000_000L / p.fps + 100_000L)
                    aeOk && durOk
                }
            } catch (e: Exception) {
                false
            }
        }

        private fun codecSupports(p: CaptureProfile, encoder: Boolean): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder == encoder &&
                    info.isHardwareAccelerated &&
                    info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) } &&
                    runCatching {
                        info.getCapabilitiesForType("video/avc").videoCapabilities
                            ?.areSizeAndRateSupported(p.width, p.height, p.fps.toDouble()) == true
                    }.getOrDefault(false)
            }
    }
}
