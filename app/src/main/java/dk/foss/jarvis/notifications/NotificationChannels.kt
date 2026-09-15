package dk.foss.jarvis.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import dk.foss.jarvis.MainActivity
import dk.foss.jarvis.R
import dk.foss.jarvis.events.HermesEventEnvelope
import java.util.UUID

const val HERMES_REMINDERS_CHANNEL = "hermes_reminders"
const val HERMES_NOTIFICATION_TAP = "dk.foss.jarvis.INTERNAL_NOTIFICATION_TAP"

/** App-private capability: random one-shot tokens prevent forged launcher intents. */
object NotificationTapStore {
    private const val PREFS = "notification_taps"
    fun issue(context: Context, eventId: String, sessionId: String?): String {
        val token = UUID.randomUUID().toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(token, "$eventId\u0000${sessionId.orEmpty()}").apply()
        return token
    }
    fun consume(context: Context, token: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getString(token, null) ?: return null
        prefs.edit().remove(token).apply()
        return value.substringAfter('\u0000').takeIf { it.isNotBlank() }
    }
}

fun ensureReminderChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(
        HERMES_REMINDERS_CHANNEL, "Hermes Reminders", NotificationManager.IMPORTANCE_DEFAULT,
    ))
}

fun displayTitleFor(envelope: HermesEventEnvelope): String = envelope.title.ifBlank { "Hermes reminder" }

fun postReminderNotification(context: Context, envelope: HermesEventEnvelope, notificationId: Int) {
    ensureReminderChannel(context)
    val intent = Intent(context, MainActivity::class.java).apply {
        action = HERMES_NOTIFICATION_TAP
        setPackage(context.packageName)
        putExtra("tap_token", NotificationTapStore.issue(context, envelope.eventId, envelope.sessionId))
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
    fun ensure(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, "android.permission.POST_NOTIFICATIONS",
        ) == PackageManager.PERMISSION_GRANTED
}
