package dk.foss.jarvis.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import dk.foss.jarvis.receivers.PushIngress
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Registers the opaque FCM token through the authenticated Hermes device API. */
object FcmPushRegistrar {
    suspend fun registerCurrentToken(context: Context): Boolean {
        if (!PushPrefs(context).isEnabled()) return false
        val token = runCatching { token() }.getOrNull() ?: return false
        return registerToken(context, token)
    }

    suspend fun registerToken(context: Context, token: String): Boolean =
        FcmRegistrationPolicy.shouldRegister(PushPrefs(context).isEnabled(), token) &&
            PushIngress.onFcmToken(context, token)

    private suspend fun token(): String = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }
}
