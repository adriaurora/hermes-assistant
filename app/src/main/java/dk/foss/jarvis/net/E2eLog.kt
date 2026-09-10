package dk.foss.jarvis.net

import android.util.Log
import dk.foss.jarvis.BuildConfig

/** Debug-only, deliberately header-free wire-flow diagnostics for local E2E runs. */
object E2eLog {
    @Suppress("NOTHING_TO_INLINE")
    inline fun log(msg: String) {
        if (BuildConfig.DEBUG) {
            try {
                Log.d("HermesE2E", "E2E $msg")
            } catch (_: Throwable) {
                // Android logging is unavailable in JVM unit tests.
            }
        }
    }
}
