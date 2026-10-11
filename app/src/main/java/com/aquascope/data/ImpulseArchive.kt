package com.aquascope.data

import android.content.Context
import com.aquascope.dsp.AcousticFeatures
import com.aquascope.sensors.Steadiness
import com.google.gson.Gson
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Keeps the raw impulse response of each scan so new acoustic features can be tested
 * offline (tools/evaluate_extra_features.py) against what users taught as dry or damp.
 *
 * Layout: files/aquascope_data/ir/<scanId>.f32 (float32 little-endian) + <scanId>.json.
 * Oldest scans are deleted once the folder passes [MAX_BYTES].
 */
class ImpulseArchive(context: Context) {

    data class Meta(
        val scanId: String,
        val locationId: String,
        val timestamp: Long,
        val sampleRate: Int,
        val samples: Int,
        var label: String,
        val features: SerializableFeatures,
        val featureVersion: Int = 0,
        val peakToNoiseDb: Double = 0.0,
        /** Features of each chirp in the scan; [features] is their per-feature median. */
        val chirpFeatures: List<SerializableFeatures>? = null,
        val maxRotationRadS: Double? = null,
        val tiltChangeDeg: Double? = null,
        val gravity: List<Double>? = null
    )

    private val dir = File(context.applicationContext.filesDir, "aquascope_data/ir").also { it.mkdirs() }
    private val gson = Gson()

    fun save(
        locationId: String,
        sampleRate: Int,
        impulseResponse: DoubleArray,
        features: AcousticFeatures,
        peakToNoiseDb: Double,
        chirpFeatures: List<AcousticFeatures> = emptyList(),
        motion: Steadiness.Summary? = null,
        /** Each chirp's windowed direct echo (ImpulseWindow), a few KB each, saved as <scanId>.e<i>.f32. */
        chirpEchoes: List<DoubleArray> = emptyList()
    ): String? =
        runCatching {
            val timestamp = System.currentTimeMillis()
            val scanId = "${timestamp}_${locationId.take(8)}"
            writeWave(File(dir, "$scanId.f32"), impulseResponse)
            chirpEchoes.forEachIndexed { i, echo -> writeWave(File(dir, "$scanId.e$i.f32"), echo) }
            writeMeta(
                Meta(
                    scanId = scanId,
                    locationId = locationId,
                    timestamp = timestamp,
                    sampleRate = sampleRate,
                    samples = impulseResponse.size,
                    label = LABEL_UNLABELLED,
                    features = SerializableFeatures.from(features),
                    featureVersion = ScanLocation.CURRENT_FEATURE_VERSION,
                    peakToNoiseDb = peakToNoiseDb,
                    chirpFeatures = chirpFeatures.map { SerializableFeatures.from(it) },
                    maxRotationRadS = motion?.maxRotationRadS,
                    tiltChangeDeg = motion?.tiltChangeDeg,
                    gravity = motion?.gravity
                )
            )
            prune()
            scanId
        }.getOrNull()

    fun label(scanId: String?, label: String) {
        if (scanId == null) return
        runCatching {
            val file = File(dir, "$scanId.json")
            if (!file.exists()) return
            val meta = gson.fromJson(file.readText(), Meta::class.java) ?: return
            meta.label = label
            writeMeta(meta)
        }
    }

    fun labelOf(scanId: String?): String? {
        if (scanId == null) return null
        return runCatching {
            gson.fromJson(File(dir, "$scanId.json").readText(), Meta::class.java)?.label
        }.getOrNull()
    }

    fun withLabel(label: String): List<Meta> = allMetas().filter { it.label == label }

    /** How the phone was held for the scans taught dry or damp at [locationId]. */
    fun taughtGravities(locationId: String): List<List<Double>> =
        allMetas()
            .filter { it.locationId == locationId && (it.label == LABEL_DRY || it.label == LABEL_DAMP) }
            .mapNotNull { it.gravity }

    private fun allMetas(): List<Meta> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f -> runCatching { gson.fromJson(f.readText(), Meta::class.java) }.getOrNull() }

    private fun writeWave(file: File, samples: DoubleArray) {
        val buf = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buf.putFloat(it.toFloat()) }
        file.writeBytes(buf.array())
    }

    private fun writeMeta(meta: Meta) {
        File(dir, "${meta.scanId}.json").writeText(gson.toJson(meta))
    }

    private fun prune() {
        // Full per-chirp recordings from one earlier build (~400 KB each); only the echoes are kept now.
        dir.listFiles { f -> LEGACY_CHIRP_WAVE.matches(f.name) }?.forEach { it.delete() }
        val waves = dir.listFiles { f -> f.extension == "f32" }?.sortedBy { it.name } ?: return
        var total = waves.sumOf { it.length() }
        for (wave in waves) {
            if (total <= MAX_BYTES) break
            total -= wave.length()
            wave.delete()
            File(dir, "${wave.nameWithoutExtension}.json").delete()
        }
    }

    companion object {
        const val LABEL_DRY = "dry"
        const val LABEL_DAMP = "damp"
        /** Older builds taught unlabelled scans as damp under this label; only read when undoing that. */
        const val LABEL_DAMP_IMPLICIT = "damp_implicit"
        const val LABEL_UNLABELLED = "unlabelled"
        /** Echo failed the quality check; the scan was not scored or taught. */
        const val LABEL_REJECTED = "rejected"
        private const val MAX_BYTES = 256L * 1024 * 1024
        private val LEGACY_CHIRP_WAVE = Regex(""".+\.c\d\.f32""")
    }
}
