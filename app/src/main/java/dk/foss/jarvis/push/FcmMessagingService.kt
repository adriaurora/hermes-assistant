package dk.foss.jarvis.push

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FcmMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        // A notification payload is never consumed. Only data-only messages with a valid ID wake work.
        if (message.notification != null) return
        val eventId = FcmPayloadParser.eventId(message.data) ?: return
        val request = OneTimeWorkRequestBuilder<FcmEventWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString(FcmEventWorker.KEY_EVENT_ID, eventId).build())
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "hermes-fcm-event-$eventId", ExistingWorkPolicy.KEEP, request,
        )
    }

    /** Token rotated by Firebase — enqueue a worker that fetches the fresh token. */
    override fun onNewToken(token: String) {
        FcmTokenRegistration.enqueueCurrent(applicationContext)
    }
}
