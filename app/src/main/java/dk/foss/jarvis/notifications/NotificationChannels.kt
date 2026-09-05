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

const val HERMES_REMINDERS_CHANNEL = "hermes_reminders"

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
        action = Intent.ACTION_VIEW
        setPackage(context.packageName)
        putExtra("event_id", envelope.eventId)
        putExtra("session_id", envelope.sessionId)
        putExtra("from_notification", true)
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
