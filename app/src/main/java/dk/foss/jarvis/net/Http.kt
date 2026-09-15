package dk.foss.jarvis.net

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp clients. One process-wide connection/thread pool, reused by all
 * network callers (the Hermes chat client) instead of building a fresh
 * client per request.
 *
 * ## Insecure HTTP policy
 *
 * The app enforces a fail-closed network gate (see [NetworkGate]). All HTTP
 * requests MUST pass through the gate *before* constructing an OkHttp call.
 * HTTPS is always allowed; HTTP is only allowed for explicitly approved
 * endpoints.  The gate instance is obtained via [gate] (production) or
 * [testingGate] (JVM tests).
 *
 * The Android manifest's `usesCleartextTraffic` flag controls what the
 * *platform* allows.  In debug builds it is set to `true` so that LAN HTTP
 * is reachable once approved.  In release builds the platform blocks cleartext
 * by default — the app gate then acts as the policy boundary.
 */
object Http {
    /** General-purpose client with bounded timeouts. No redirects — the gate
     * enforces a fail-closed policy and callers must validate before building
     * requests.  SSE streams inherit this via [streaming]. */
    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** For long-lived SSE streams (no read timeout); inherits redirect settings from [base]. */
    val streaming: OkHttpClient = base.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // ─── Network gate ──────────────────────────────────────────────────────

    /**
     * Application context for the production network gate.
     * Set once by the app (typically in Application.onCreate or MainActivity).
     * All network callers access it lazily.
     */
    @Volatile
    var applicationContext: Context? = null

    /**
     * Production network gate backed by Android DataStore.
     * The gate is lazily initialised from [applicationContext].
     *
     * The production gate includes a cleanup store so that [validateForCleanup]
     * can check the pending-revoke allowance.
     */
    @Volatile
    private var _gate: NetworkGate? = null

    /**
     * Return the production [NetworkGate].  Must be called after
     * [applicationContext] is set (i.e. on Android after the app starts).
     */
    fun gate(): NetworkGate {
        _gate?.let { return it }
        val ctx = applicationContext
            ?: throw IllegalStateException("Network gate not initialised — set Http.applicationContext first")
        synchronized(this) {
            _gate ?: run {
                val activeStore = AndroidApprovedOriginsStore(ctx)
                NetworkGate(activeStore, activeStore).also { _gate = it }
            }
        }
        return _gate!!
    }

    /**
     * Testing gate backed by an in-memory store.  Available only in JVM tests.
     * Call this from your test setup to inject a fresh gate with known state.
     *
     * The testing gate includes an in-memory cleanup store so that
     * [validateForCleanup] can be tested alongside regular [validate].
     */
    internal val testingGate = {
        val activeStore = InMemoryApprovedOriginsStore()
        NetworkGate(activeStore, activeStore)
    }()

    /** Reset the production gate (useful in tests that reuse the Http singleton). */
    internal fun resetGate() { _gate = null; applicationContext = null }
}