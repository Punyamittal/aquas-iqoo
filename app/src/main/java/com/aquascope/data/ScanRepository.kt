package com.aquascope.data

import android.content.Context
import com.aquascope.dsp.AcousticFeatures
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * JSON file-based persistence for scan locations, baselines, and history.
 * Simple and sufficient for a hackathon — no Room overhead.
 */
class ScanRepository(context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()
    private val dataDir = File(appContext.filesDir, "aquascope_data").also { it.mkdirs() }
    private val locationsFile = File(dataDir, "locations.json")

    fun loadLocations(): MutableList<ScanLocation> = loadLocationsRaw()

    private fun loadLocationsRaw(): MutableList<ScanLocation> {
        if (!locationsFile.exists()) return mutableListOf()
        return try {
            val json = locationsFile.readText()
            if (json.isBlank()) return mutableListOf()
            val type = object : TypeToken<MutableList<ScanLocation>>() {}.type
            val loaded = gson.fromJson<MutableList<ScanLocation>>(json, type) ?: mutableListOf()
            // Gson can null Kotlin default lists when the field was missing in older files.
            loaded.map { loc ->
                @Suppress("UNNECESSARY_SAFE_CALL", "USELESS_ELVIS")
                ScanLocation(
                    id = loc.id,
                    label = loc.label,
                    baselineFeatures = loc.baselineFeatures ?: mutableListOf(),
                    scanHistory = loc.scanHistory ?: mutableListOf(),
                    moistFeatures = loc.moistFeatures ?: mutableListOf(),
                    featureVersion = loc.featureVersion
                )
            }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveLocations(locations: List<ScanLocation>) {
        // Atomic write: temp file then rename, avoids corrupting JSON on crash mid-write
        val tmp = File(dataDir, "locations.json.tmp")
        tmp.writeText(gson.toJson(locations))
        if (!tmp.renameTo(locationsFile)) {
            tmp.copyTo(locationsFile, overwrite = true)
            tmp.delete()
        }
    }

    fun addLocation(location: ScanLocation) {
        val locations = loadLocations()
        locations.add(location)
        saveLocations(locations)
    }

    fun updateLocation(location: ScanLocation) {
        val locations = loadLocations()
        val idx = locations.indexOfFirst { it.id == location.id }
        if (idx >= 0) {
            locations[idx] = location
            saveLocations(locations)
        }
    }

    fun getLocation(id: String): ScanLocation? {
        return loadLocations().find { it.id == id }
    }

    fun deleteLocation(id: String) {
        val locations = loadLocations()
        locations.removeAll { it.id == id }
        saveLocations(locations)
    }

    fun clearBaseline(id: String) {
        val location = getLocation(id) ?: return
        location.baselineFeatures.clear()
        location.moistFeatures.clear()
        updateLocation(location)
    }
}

data class ScanLocation(
    val id: String = java.util.UUID.randomUUID().toString(),
    val label: String,
    val baselineFeatures: MutableList<SerializableFeatures> = mutableListOf(),
    val scanHistory: MutableList<ScanRecord> = mutableListOf(),
    /** User-confirmed moist / anomalous examples (not added to dry baseline). */
    val moistFeatures: MutableList<SerializableFeatures> = mutableListOf(),
    /** Pipeline that produced the taught features; files written before this field read as 0. */
    var featureVersion: Int = CURRENT_FEATURE_VERSION
) {
    companion object {
        /** 2 = band-limited deconvolution + 40 ms direct-echo window. */
        const val CURRENT_FEATURE_VERSION = 2
    }
}

data class ScanRecord(
    val timestamp: Long = System.currentTimeMillis(),
    val features: SerializableFeatures,
    val anomalyScore: Double,
    /** Archive id of the recording (and of its surface photo); null on records saved before it was stored. */
    val scanId: String? = null
)

/** Serializable wrapper for AcousticFeatures (Gson-friendly). */
data class SerializableFeatures(
    val resonanceFreqHz: Double,
    val decayTimeMs: Double,
    val spectralCentroidHz: Double,
    val spectralSpreadHz: Double,
    val spectralFlatness: Double
) {
    fun toAcousticFeatures() = AcousticFeatures(
        resonanceFreqHz, decayTimeMs, spectralCentroidHz, spectralSpreadHz, spectralFlatness
    )

    companion object {
        fun from(f: AcousticFeatures) = SerializableFeatures(
            f.resonanceFreqHz, f.decayTimeMs, f.spectralCentroidHz,
            f.spectralSpreadHz, f.spectralFlatness
        )
    }
}
