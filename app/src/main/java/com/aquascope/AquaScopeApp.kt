package com.aquascope

import android.app.Application
import android.os.Build
import android.util.Log
import com.aquascope.baseline.UltrasonicModelWeights
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
    }

    companion object {
        private const val TAG = "AquaScopeApp"

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
