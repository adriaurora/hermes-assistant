package dk.foss.jarvis.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import dk.foss.jarvis.receivers.PushIngress
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Registers the opaque FCM token through the authenticated Hermes device API. */
object FcmPushRegistrar {

    /** Fetch the current Firebase token (async, non-blocking). */
    private suspend fun currentToken(): String? = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

    /**
     * Register the device with the given FCM token.
     *
     * This method is lock-protected: it rechecks [PushPrefs.enabled] and
     * [PushPrefs.pendingRevoke] inside the lifecycle lock before making the
     * HTTP call to [PushIngress.onFcmToken].  State updates are also inside
     * the lock so that a concurrent disable() / enable() sees the final
     * state without racing.
     *
     * @return true if the HTTP call succeeded, false if state mismatch
     *         (enabled=false or pendingRevoke=true) or HTTP failure.
     */
    suspend fun registerCurrentToken(context: Context): Boolean {
        return registerCurrentTokenOutcome(context).let { it == TokenSyncOutcome.REGISTERED || it == TokenSyncOutcome.UPDATED }
    }
    suspend fun registerCurrentTokenOutcome(context: Context): TokenSyncOutcome {
        val prefs = PushPrefs(context)
        if (!prefs.isEnabled()) return TokenSyncOutcome.DISABLED
        if (prefs.isPendingRevoke()) return TokenSyncOutcome.DISABLED

        val token = currentToken() ?: return TokenSyncOutcome.RETRYABLE
        return registerToken(context, token)
    }

    /**
     * Register/Update the FCM token via HTTP under the lifecycle lock.
     * Rechecks enabled + pendingRevoke inside the lock before calling
     * [PushIngress.onFcmToken].  State is updated inside the lock.
     */
    private suspend fun registerToken(context: Context, token: String): TokenSyncOutcome {
        val app = context.applicationContext
        return FcmLifecycle.withLockReturning {
            val prefs = PushPrefs(app)
            // Revalidate inside lock (state may have changed).
            if (!prefs.isEnabled() || prefs.isPendingRevoke()) return@withLockReturning TokenSyncOutcome.DISABLED

            val outcome = PushIngress.onFcmToken(app, token)
            prefs.setRegistrationState(if (outcome == TokenSyncOutcome.REGISTERED || outcome == TokenSyncOutcome.UPDATED) FcmRegistrationState.ENABLED else if (outcome == TokenSyncOutcome.PERMANENT) FcmRegistrationState.ERROR else FcmRegistrationState.REGISTERING)
            outcome
        }
    }
}
