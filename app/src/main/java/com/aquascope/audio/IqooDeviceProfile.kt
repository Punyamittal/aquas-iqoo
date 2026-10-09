package com.aquascope.audio

import android.content.Context
import android.os.Build

/**
 * [ROLE] Official iQOO Open Innovation Hackathon device profile.
 * [WHY] Locks sample rate, chirp band, and routing assumptions to iQOO 15 hardware.
 * [IQOO] Snapdragon 8 Elite Gen 5 · dual stereo speakers · 48 kHz preferred · 12/16 GB RAM OK
 *        (AquaScope DSP ≪ 1 GB). Ask LLM is separate from this sensing profile.
 */
object IqooDeviceProfile {

    const val TARGET_DEVICE_NAME = "iQOO 15"

    /** Prefer 48 kHz on Snapdragon / Hi-Res phones; fallback handled in AudioEngine. */
    const val PREFERRED_SAMPLE_RATE = 48000

    /** Fallback rates if preferred is unavailable. */
    val SAMPLE_RATE_CANDIDATES = intArrayOf(48000, 44100, 16000)

    /**
     * Phone speakers are weak below ~80–100 Hz; start a bit above 20 Hz for better SNR
     * on contact sensing with the bottom speaker.
     */
    const val CHIRP_START_HZ = 80.0

    /** Upper bound well within phone speaker + mic bandwidth; below Nyquist at 48 kHz. */
    const val CHIRP_END_HZ = 16000.0

    /** Slightly longer chirp for more energy into the surface on the bottom speaker. */
    const val CHIRP_DURATION_SEC = 1.6

    /** Extra mic window after chirp ends for decay capture. */
    const val RECORD_TAIL_SEC = 0.6

    /** Larger stream chunks reduce under-runs on high-end Snapdragon audio HAL. */
    const val PLAY_WRITE_CHUNK = 2048

    fun summarize(context: Context): String {
        val model = Build.MODEL ?: "unknown"
        val manufacturer = Build.MANUFACTURER ?: "unknown"
        val isLikelyIqoo = manufacturer.contains("vivo", ignoreCase = true) ||
            manufacturer.contains("iqoo", ignoreCase = true) ||
            model.contains("iqoo", ignoreCase = true) ||
            model.contains("I2220", ignoreCase = true) // common vivo/iQOO model patterns vary
        return buildString {
            append("Target: $TARGET_DEVICE_NAME | Running on: $manufacturer $model")
            if (isLikelyIqoo) append(" (vivo/iQOO family detected)")
            append(" | Preferred SR: ${PREFERRED_SAMPLE_RATE} Hz")
        }
    }
}
