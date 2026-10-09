package com.aquascope.data

import android.content.Context
import com.aquascope.dsp.AcousticFeatures
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
        val peakToNoiseDb: Double = 0.0
    )

    private val dir = File(context.applicationContext.filesDir, "aquascope_data/ir").also { it.mkdirs() }
    private val gson = Gson()

    fun save(
        locationId: String,
        sampleRate: Int,
        impulseResponse: DoubleArray,
        features: AcousticFeatures,
        peakToNoiseDb: Double
    ): String? =
        runCatching {
            val timestamp = System.currentTimeMillis()
            val scanId = "${timestamp}_${locationId.take(8)}"
            val buf = ByteBuffer.allocate(impulseResponse.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            impulseResponse.forEach { buf.putFloat(it.toFloat()) }
            File(dir, "$scanId.f32").writeBytes(buf.array())
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
                    peakToNoiseDb = peakToNoiseDb
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

    private fun writeMeta(meta: Meta) {
        File(dir, "${meta.scanId}.json").writeText(gson.toJson(meta))
    }

    private fun prune() {
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
        /** Scan the user left unlabelled; the app taught it as damp by default. */
        const val LABEL_DAMP_IMPLICIT = "damp_implicit"
        const val LABEL_UNLABELLED = "unlabelled"
        /** Echo failed the quality check; the scan was not scored or taught. */
        const val LABEL_REJECTED = "rejected"
        private const val MAX_BYTES = 64L * 1024 * 1024
    }
}
