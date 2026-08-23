package dk.foss.jarvis.receivers

import android.content.Context
import android.content.Intent
import dk.foss.jarvis.MainActivity

/** Small transport boundary: ntfy/UP (or another transport) can call this without a SDK dependency. */
object PushIngress {
    suspend fun fromNotificationIntent(context: Context, intent: Intent): Boolean {
        val eventId = intent.getStringExtra("event_id") ?: return false
        if (!intent.getBooleanExtra("from_notification", false)) return false
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtras(intent)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        return eventId.isNotBlank()
    }
}

// WorkManager is not a dependency in this build; a worker will be added with the transport.
// The transport boundary should invoke EventDispatcher.onPendingSync() from its own coroutine.
