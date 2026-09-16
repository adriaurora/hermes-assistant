package dk.foss.jarvis.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import dk.foss.jarvis.MainActivity
import dk.foss.jarvis.R
import dk.foss.jarvis.events.HermesEventEnvelope
import java.util.UUID

const val HERMES_REMINDERS_CHANNEL = "hermes_reminders"
const val HERMES_NOTIFICATION_TAP = "dk.foss.jarvis.INTERNAL_NOTIFICATION_TAP"

/** Result of consuming a notification tap token. */
sealed interface TapResult {
    /** Tap is valid — contains the session ID to open. */
    data class Valid(val sessionId: String, val notificationOrigin: String) : TapResult

    /** Tap originated from a different endpoint or has no origin prefix. */
    data object StaleOrigin : TapResult

    /** Tap token not found (already consumed or invalid). */
    data object NotFound : TapResult
}

/**
 * App-private capability: random one-shot tokens prevent forged launcher intents.
 *
 * Tokens now carry the full endpoint identity (URL + API key fingerprint) so
 * that consumers can verify a tap still refers to the active endpoint.
 * Legacy tokens (2-part format, no origin prefix) are treated as stale.
 */
object NotificationTapStore {
    private const val PREFS = "notification_taps"
    private const val SEPARATOR = '\u0000'

    /**
     * Issue a one-shot notification tap token.
     *
     * @param context   Application context for SharedPreferences access.
     * @param eventId   Hermes event identifier.
     * @param sessionId Optional session identifier (may be null).
     * @param origin    Full endpoint identity: [originIdentity](baseUrl, apiKey).
     * @return A random UUID string used as the token key.
     */
    fun issue(context: Context, eventId: String, sessionId: String?, origin: String): String {
        val token = UUID.randomUUID().toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(token, "$origin$SEPARATOR$eventId$SEPARATOR${sessionId.orEmpty()}")
            .apply()
        return token
    }

    /**
     * Consume a notification tap token.  Verifies that the stored origin
     * matches [currentOrigin] (the active endpoint identity).  Removes the
     * token so it cannot be reused.
     *
     * @param context       Application context.
     * @param token         The one-shot tap token.
     * @param currentOrigin The currently configured endpoint identity.
     * @return [TapResult.Valid] if the tap is accepted,
     *         [TapResult.StaleOrigin] if the origin mismatches or the token
     *         is a legacy format, or [TapResult.NotFound] if the token
     *         doesn't exist.
     */
    fun consume(context: Context, token: String, currentOrigin: String): TapResult {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getString(token, null) ?: return TapResult.NotFound
        prefs.edit().remove(token).apply()
        return validateTap(value, currentOrigin)
    }

    /**
     * Non-destructive validation: read the token without removing it.
     * Used by [PushIngress.fromNotificationIntent] to verify that a
     * notification intent refers to a valid tap without consuming it,
     * so that [MainActivity.consumeTap] can still do so.
     *
     * @param context       Application context.
     * @param token         The tap token.
     * @param currentOrigin The currently configured endpoint identity.
     * @return The same [TapResult] as [consume], but the token is not removed.
     */
    fun peek(context: Context, token: String, currentOrigin: String): TapResult {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getString(token, null) ?: return TapResult.NotFound
        return validateTap(value, currentOrigin)
    }

    private fun validateTap(value: String, currentOrigin: String): TapResult {
        val parts = value.split(SEPARATOR)
        if (parts.size < 3) return TapResult.StaleOrigin
        val tapOrigin = parts[0]
        if (tapOrigin != currentOrigin) return TapResult.StaleOrigin
        val sessionId = parts[2]
        return sessionId.takeIf { it.isNotBlank() }
            ?.let { TapResult.Valid(it, tapOrigin) }
            ?: TapResult.NotFound
    }
}

fun ensureReminderChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(
        HERMES_REMINDERS_CHANNEL, "Hermes Reminders", NotificationManager.IMPORTANCE_DEFAULT,
    ))
}

fun displayTitleFor(envelope: HermesEventEnvelope): String = envelope.title.ifBlank { "Hermes reminder" }

/**
 * Post a reminder notification with endpoint identity tracking.
 *
 * @param context           Application context.
 * @param envelope          The Hermes event envelope.
 * @param notificationId    Unique notification ID.
 * @param endpointIdentity  Full endpoint identity from the source:
 *                          [originIdentity](settings.baseUrl, settings.apiKey).
 */
fun postReminderNotification(
    context: Context,
    envelope: HermesEventEnvelope,
    notificationId: Int,
    endpointIdentity: String,
) {
    ensureReminderChannel(context)
    val intent = Intent(context, MainActivity::class.java).apply {
        action = HERMES_NOTIFICATION_TAP
        setPackage(context.packageName)
        putExtra("tap_token", NotificationTapStore.issue(context, envelope.eventId, envelope.sessionId, endpointIdentity))
    }
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val contentIntent = PendingIntent.getActivity(context, notificationId, intent, flags)
    val notification = NotificationCompat.Builder(context, HERMES_REMINDERS_CHANNEL)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(displayTitleFor(envelope))
        .setContentText(envelope.body)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(contentIntent)
        .build()
    // Android 13+ requires POST_NOTIFICATIONS; permission is requested by app UI, not here.
    context.getSystemService(NotificationManager::class.java).notify(notificationId, notification)
}

object NotificationPermission {
    fun ensure(context: Context): Boolean {
        val runtimeAllowed = Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, "android.permission.POST_NOTIFICATIONS",
            ) == PackageManager.PERMISSION_GRANTED
        if (!runtimeAllowed || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(HERMES_REMINDERS_CHANNEL)
        // notify() may return normally for a blocked channel. Do not interpret
        // that as delivery and ACK an event the user could not see.
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }
}