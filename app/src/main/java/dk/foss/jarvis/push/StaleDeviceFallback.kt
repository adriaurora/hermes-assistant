package dk.foss.jarvis.push

import dk.foss.jarvis.hermes.HermesHttpException

/**
 * Determines whether an error observed during a legacy device token update
 * means the pinned device is truly gone (HTTP 404) vs a transient/server
 * error.  Only confirmed 404s trigger a fresh registration instead of a
 * permanent failure.
 *
 * This restores the fallback semantics that existed in main before the v1
 * reconciliation branch lost the check.
 */
object StaleDeviceFallback {

    /** Returns true when [error] conclusively proves the device is unknown. */
    fun isConfirmedNotFound(error: Throwable?): Boolean = when {
        error == null -> false
        error is HermesHttpException && error.statusCode == 404 -> true
        error is IllegalStateException && error.message?.startsWith("HTTP 404") == true -> true
        else -> false
    }

    /** 401/403 = the stored credential is definitively rejected; retrying cannot fix it. */
    fun isAuthRejection(error: Throwable?): Boolean = when {
        error == null -> false
        error is HermesHttpException && (error.statusCode == 401 || error.statusCode == 403) -> true
        error is IllegalStateException && (error.message?.startsWith("HTTP 401") == true || error.message?.startsWith("HTTP 403") == true) -> true
        else -> false
    }
}