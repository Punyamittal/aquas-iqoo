package com.aquascope.data

import android.content.Context
import android.util.Log
import com.aquascope.vision.SurfaceLook
import com.google.gson.Gson
import java.io.File

/**
 * Torch-lit close-ups taken right after a sound scan, stored next to the scan's impulse response
 * (same id) and labelled dry or damp with it, so whether the photo helps can be tested offline.
 *
 * Layout: files/aquascope_data/photos/<id>.jpg + <id>.json. Oldest photos go past [MAX_BYTES].
 */
class SurfacePhotoArchive(context: Context) {

    data class Meta(
        val id: String,
        val scanId: String?,
        val locationId: String,
        val timestamp: Long,
        var label: String,
        val look: SurfaceLook,
        val exposureTimeNs: Long?,
        val iso: Int?
    )

    private val dir = File(context.applicationContext.filesDir, "aquascope_data/photos").also { it.mkdirs() }
    private val gson = Gson()

    fun save(
        scanId: String?,
        locationId: String,
        label: String,
        jpeg: ByteArray,
        look: SurfaceLook,
        exposureTimeNs: Long?,
        iso: Int?
    ): Meta? = runCatching {
        val timestamp = System.currentTimeMillis()
        val id = scanId ?: "${timestamp}_${locationId.take(8)}"
        File(dir, "$id.jpg").writeBytes(jpeg)
        val meta = Meta(id, scanId, locationId, timestamp, label, look, exposureTimeNs, iso)
        File(dir, "$id.json").writeText(gson.toJson(meta))
        prune()
        meta
    }.onFailure { Log.w("SurfacePhotoArchive", "Saving photo failed", it) }.getOrNull()

    fun load(id: String): Meta? = runCatching {
        gson.fromJson(File(dir, "$id.json").readText(), Meta::class.java)
    }.getOrNull()

    fun imageFile(id: String): File = File(dir, "$id.jpg")

    /** Gives a photo taken before its scan the scan's id, so labelling the scan labels the photo too. */
    fun attach(photoId: String, scanId: String): Meta? = runCatching {
        val meta = load(photoId) ?: return null
        if (photoId == scanId) return meta
        val linked = meta.copy(id = scanId, scanId = scanId)
        File(dir, "$photoId.jpg").renameTo(File(dir, "$scanId.jpg"))
        File(dir, "$scanId.json").writeText(gson.toJson(linked))
        File(dir, "$photoId.json").delete()
        linked
    }.onFailure { Log.w("SurfacePhotoArchive", "Linking photo $photoId to scan $scanId failed", it) }.getOrNull()

    fun label(scanId: String?, label: String) {
        val meta = scanId?.let { load(it) } ?: return
        meta.label = label
        runCatching { File(dir, "${meta.id}.json").writeText(gson.toJson(meta)) }
    }

    /** Looks of the photos at [locationId] the user labelled dry and damp, except [excludeId]. */
    fun taughtLooks(locationId: String, excludeId: String? = null): Pair<List<SurfaceLook>, List<SurfaceLook>> {
        val metas = (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f -> runCatching { gson.fromJson(f.readText(), Meta::class.java) }.getOrNull() }
            .filter { it.locationId == locationId && it.id != excludeId }
        return metas.filter { it.label == ImpulseArchive.LABEL_DRY }.map { it.look } to
            metas.filter { it.label == ImpulseArchive.LABEL_DAMP }.map { it.look }
    }

    /** Photo for each record at [locationId] (by record index), or null where there is none. */
    fun photosFor(locationId: String, records: List<ScanRecord>): List<File?> {
        val linked = (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f -> runCatching { gson.fromJson(f.readText(), Meta::class.java) }.getOrNull() }
            .filter { it.locationId == locationId && it.scanId != null && imageFile(it.id).exists() }
        return matchPhotos(records.map { it.timestamp to it.scanId }, linked.map { it.scanId!! })
            .map { id -> id?.let { imageFile(it) } }
    }

    private fun prune() {
        val photos = dir.listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name } ?: return
        var total = photos.sumOf { it.length() }
        for (photo in photos) {
            if (total <= MAX_BYTES) break
            total -= photo.length()
            photo.delete()
            File(dir, "${photo.nameWithoutExtension}.json").delete()
        }
    }

    companion object {
        private const val MAX_BYTES = 48L * 1024 * 1024
        /** A record is saved a few seconds after its recording; older records are matched within this gap. */
        const val MATCH_WINDOW_MS = 2 * 60_000L

        /**
         * Pairs records ((timestamp, scanId?)) with photo scan ids ("<recordingMillis>_<place>").
         * A known scanId must match exactly; otherwise the nearest unused recording at most
         * [MATCH_WINDOW_MS] before the record is taken.
         */
        fun matchPhotos(records: List<Pair<Long, String?>>, photoScanIds: List<String>): List<String?> {
            val available = photoScanIds.toMutableSet()
            val result = MutableList<String?>(records.size) { null }
            records.forEachIndexed { i, (_, scanId) ->
                if (scanId != null && available.remove(scanId)) result[i] = scanId
            }
            records.forEachIndexed { i, (timestamp, scanId) ->
                if (scanId != null) return@forEachIndexed
                val best = available
                    .mapNotNull { id -> id.substringBefore('_').toLongOrNull()?.let { id to timestamp - it } }
                    .filter { (_, gap) -> gap in 0..MATCH_WINDOW_MS }
                    .minByOrNull { it.second }?.first
                if (best != null) {
                    available.remove(best)
                    result[i] = best
                }
            }
            return result
        }
    }
}
