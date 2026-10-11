package com.aquascope

import android.app.Application
import android.os.Build
import android.util.Log
import com.aquascope.baseline.PlaceReliability
import com.aquascope.baseline.UltrasonicModelWeights
import com.aquascope.data.ImpulseArchive
import com.aquascope.data.ScanRepository
import com.aquascope.data.SurfacePhotoArchive
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Unlocks non-SDK ServiceManager access so Monster Halo (`vivo_light_service`)
 * can be reached on OriginOS / Android 9+.
 */
class AquaScopeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        unlockHiddenApis()
        UltrasonicModelWeights.ensureLoaded(this)
        warmPlaceChecks()
    }

    /** Runs each place's dry/damp self-check off the main thread so screens find it cached. */
    private fun warmPlaceChecks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && getProcessName() != packageName) return
        Thread({
            runCatching { dropAutoTaughtDamp() }
                .onFailure { Log.w(TAG, "Removing auto-taught damp examples failed: ${it.message}") }
            runCatching {
                ScanRepository(this).loadLocations().forEach { PlaceReliability.of(it) }
            }.onFailure { Log.w(TAG, "Place self-check warm-up failed: ${it.message}") }
        }, "place-self-check").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** Once: forget damp examples older builds taught from scans the user never labelled. */
    private fun dropAutoTaughtDamp() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AUTO_DAMP_DROPPED, false)) return
        val impulses = ImpulseArchive(this)
        val autoTaught = impulses.withLabel(ImpulseArchive.LABEL_DAMP_IMPLICIT)
        if (autoTaught.isNotEmpty()) {
            val repo = ScanRepository(this)
            val locations = repo.loadLocations()
            var removed = 0
            for (scan in autoTaught) {
                if (locations.find { it.id == scan.locationId }?.moistFeatures?.remove(scan.features) == true) removed++
            }
            repo.saveLocations(locations)
            val photos = SurfacePhotoArchive(this)
            for (scan in autoTaught) {
                impulses.label(scan.scanId, ImpulseArchive.LABEL_UNLABELLED)
                photos.label(scan.scanId, ImpulseArchive.LABEL_UNLABELLED)
            }
            Log.i(TAG, "Removed $removed of ${autoTaught.size} auto-taught damp examples")
        }
        prefs.edit().putBoolean(KEY_AUTO_DAMP_DROPPED, true).apply()
    }

    companion object {
        private const val TAG = "AquaScopeApp"
        private const val PREFS = "aquascope_app"
        private const val KEY_AUTO_DAMP_DROPPED = "auto_damp_dropped"

        fun unlockHiddenApis() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
                Log.i(TAG, "Hidden API exemptions applied")
            } catch (t: Throwable) {
                Log.w(TAG, "Hidden API bypass failed: ${t.message}")
            }
        }
    }
}
