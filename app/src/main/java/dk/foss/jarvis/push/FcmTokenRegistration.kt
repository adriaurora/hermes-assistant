package dk.foss.jarvis.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

object FcmTokenRegistration {
    private const val WORK_NAME = "hermes-fcm-token-registration"
    private const val KEY_TOKEN = "fcm_token"

    fun enqueue(context: Context, token: String) {
        val request = OneTimeWorkRequestBuilder<FcmTokenWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString(KEY_TOKEN, token).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun enqueueCurrent(context: Context) {
        val request = OneTimeWorkRequestBuilder<FcmTokenWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    internal fun token(data: Data): String? = data.getString(KEY_TOKEN)?.takeIf { it.isNotBlank() }
}
