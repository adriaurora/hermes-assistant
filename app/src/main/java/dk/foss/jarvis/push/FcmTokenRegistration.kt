package dk.foss.jarvis.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

object FcmTokenRegistration {
    private const val WORK_NAME = "hermes-fcm-token-registration"

    /** Enqueue a token-registration worker without inputData. The worker
     *  fetches the current Firebase token internally via [FcmPushRegistrar]. */
    fun enqueueCurrent(context: Context) {
        val request = OneTimeWorkRequestBuilder<FcmTokenWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
