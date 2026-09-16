package dk.foss.jarvis

import android.app.Application
import dk.foss.jarvis.net.Http

/**
 * Application entry point.
 *
 * Sets [Http.applicationContext] so that the production network gate
 * ([AndroidApprovedOriginsStore]) can initialize on first call.  All network
 * callers (HermesClient, EventRpcClient, FcmRevokeWorker) use this gate before
 * any socket is opened.
 */
class HermesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Http.applicationContext = applicationContext
    }
}