package com.aquascope.vision

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What the scanned spot looks like with the camera and torch pressed flat on it. The torch light
 * that scatters back into the camera is dimmer when the material is wet (water fills the pores
 * and absorbs more), and pressing flat removes distance, angle and room light.
 *
 * [brightness] is mean linear luminance (0-1) of the centre of the frame divided by the camera's
 * exposure (seconds × ISO/100), so auto-exposure does not hide a darker surface. It is null when
 * the camera did not report its exposure.
 */
data class SurfaceLook(
    val rawLuminance: Double,
    val brightness: Double?,
    val glarePercent: Double,
    val texture: Double,
    val saturation: Double,
    /** Brightest over dimmest quarter of the whole frame; large when light leaks in at one side. */
    val unevenness: Double = 1.0
)

object SurfaceLookAnalyzer {

    /** Share of the frame, centred, that is analysed; edges are dim from the torch falloff. */
    private const val CENTRE_FRACTION = 0.6
    private const val GLARE_LUMA = 250

    /** Below this the change is within what pressure and small shifts alone cause. */
    private const val MIN_DARKER_PERCENT = 15.0

    /** Photos further apart than this were taken differently (not pressed flat, torch off, ...). */
    private const val MAX_COMPARABLE_RATIO = 4.0

    /** Under this (0.2% of full scale) almost no torch light came back: torch covered or blocked. */
    private const val MIN_RAW_LUMINANCE = 0.002

    /** A pressed-flat frame is evenly lit; one quarter this much brighter means light leaks in at a side. */
    private const val MAX_UNEVENNESS = 5.0

    fun analyze(pixels: IntArray, width: Int, height: Int, exposureTimeNs: Long?, iso: Int?): SurfaceLook {
        val cropW = (width * CENTRE_FRACTION).toInt().coerceAtLeast(1)
        val cropH = (height * CENTRE_FRACTION).toInt().coerceAtLeast(1)
        val x0 = (width - cropW) / 2
        val y0 = (height - cropH) / 2
        var linSum = 0.0
        var lumaSum = 0.0
        var lumaSq = 0.0
        var satSum = 0.0
        var glare = 0
        val n = cropW * cropH
        for (y in y0 until y0 + cropH) {
            for (x in x0 until x0 + cropW) {
                val p = pixels[y * width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
                lumaSum += luma
                lumaSq += luma * luma
                linSum += 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                satSum += if (max == 0) 0.0 else (max - min).toDouble() / max
                if (luma >= GLARE_LUMA) glare++
            }
        }
        val meanLuma = lumaSum / n
        val std = sqrt((lumaSq / n - meanLuma * meanLuma).coerceAtLeast(0.0))
        val rawLuminance = linSum / n
        val exposure = if (exposureTimeNs != null && exposureTimeNs > 0 && iso != null && iso > 0) {
            exposureTimeNs / 1e9 * iso / 100.0
        } else null
        return SurfaceLook(
            rawLuminance = rawLuminance,
            brightness = exposure?.let { rawLuminance / it },
            glarePercent = 100.0 * glare / n,
            texture = if (meanLuma > 1e-6) std / meanLuma else 0.0,
            saturation = satSum / n,
            unevenness = quarterRatio(pixels, width, height)
        )
    }

    enum class Quality { OK, TOO_DARK, UNEVEN }

    /** Whether a pressed-flat photo is usable; bad ones are retaken instead of saved. */
    fun quality(look: SurfaceLook): Quality = when {
        look.rawLuminance < MIN_RAW_LUMINANCE -> Quality.TOO_DARK
        look.unevenness > MAX_UNEVENNESS -> Quality.UNEVEN
        else -> Quality.OK
    }

    private fun quarterRatio(pixels: IntArray, width: Int, height: Int): Double {
        val sums = DoubleArray(4)
        val counts = IntArray(4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val q = (if (x < width / 2) 0 else 1) + (if (y < height / 2) 0 else 2)
                sums[q] += 0.2126 * linear((p shr 16) and 0xFF) + 0.7152 * linear((p shr 8) and 0xFF) +
                    0.0722 * linear(p and 0xFF)
                counts[q]++
            }
        }
        val means = sums.indices.filter { counts[it] > 0 }.map { sums[it] / counts[it] }
        val dimmest = means.minOrNull() ?: return 1.0
        return (means.maxOrNull() ?: dimmest) / dimmest.coerceAtLeast(1e-4)
    }

    enum class Verdict { LIKE_DRY, LIKE_DAMP, NOT_COMPARABLE, DRY_ONLY }

    /**
     * [darkerPercent] is against the median dry photo. [verdict] says which taught photos this one
     * is nearer to (on a log scale, since light falls off multiplicatively).
     */
    data class Comparison(val darkerPercent: Double, val verdict: Verdict, val dryPhotos: Int, val dampPhotos: Int)

    /** Null until the place has a dry photo with a known exposure, or when this photo lacks one. */
    fun compare(look: SurfaceLook, dry: List<SurfaceLook>, damp: List<SurfaceLook>): Comparison? {
        val x = look.brightness?.takeIf { it > 0 } ?: return null
        val dryB = dry.mapNotNull { it.brightness?.takeIf { b -> b > 0 } }
        if (dryB.isEmpty()) return null
        val dampB = damp.mapNotNull { it.brightness?.takeIf { b -> b > 0 } }
        val lx = ln(x)
        val ld = ln(median(dryB))
        val lm = if (dampB.isEmpty()) null else ln(median(dampB))
        val darker = 100.0 * (1.0 - exp(lx - ld))
        val limit = ln(MAX_COMPARABLE_RATIO)
        val nearest = minOf(abs(lx - ld), lm?.let { abs(lx - it) } ?: Double.MAX_VALUE)
        val verdict = when {
            nearest > limit -> Verdict.NOT_COMPARABLE
            lm == null || abs(lm - ld) < ln(1.1) -> Verdict.DRY_ONLY
            abs(lx - lm) < abs(lx - ld) -> Verdict.LIKE_DAMP
            else -> Verdict.LIKE_DRY
        }
        return Comparison(darker, verdict, dryB.size, dampB.size)
    }

    /** One plain sentence for the scan screen. */
    fun describe(c: Comparison?): String {
        if (c == null) {
            return "Photo saved. Once a dry spot here also has a photo, AquaScope compares how much torch light comes back."
        }
        if (c.verdict == Verdict.NOT_COMPARABLE) {
            return "This photo is far brighter or darker than the others taught here, so it can't be compared. " +
                "Press the camera and torch flat on the spot, the same way every time."
        }
        val change = when {
            c.darkerPercent >= MIN_DARKER_PERCENT -> "${c.darkerPercent.toInt()}% darker than"
            c.darkerPercent <= -MIN_DARKER_PERCENT -> "${(-c.darkerPercent).toInt()}% brighter than"
            else -> "about as bright as"
        }
        val dry = if (c.dryPhotos == 1) "the dry photo" else "the ${c.dryPhotos} dry photos"
        val verdict = when (c.verdict) {
            Verdict.LIKE_DAMP -> " It is closer to the damp photos taught here."
            Verdict.LIKE_DRY -> " It is closer to the dry photos taught here."
            else -> if (c.darkerPercent >= MIN_DARKER_PERCENT) " Wet surfaces often look darker." else ""
        }
        return "Light check: $change $dry here.$verdict Shown next to the sound score, not mixed into it."
    }

    private fun linear(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else 0.5 * (s[m - 1] + s[m])
    }
}
