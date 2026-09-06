package dk.foss.jarvis.push

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native FCM registration lifecycle. */
object FcmLifecycle {
    // Non-reentrant serialization lock.  Serializes enable/disable against
    // in-flight WorkManager workers and Firebase callbacks so that a disable()
    // cannot race with a registration HTTP call.  The lock is in-memory only
    // and does NOT survive process death — PushPrefs state self-heals because
    // every operation (register, revoke, disable, enable) reads state inside
    // the lock before acting.
    private val lifecycleMutex = Mutex()

    /**
     * Execute [block] under the lifecycle serialization lock.
     *
     * The [Mutex] is non-reentrant.  Callers must not call another
     * [withLock]-protected block from within [block] (or any nested call)
     * — that would deadlock.
     *
     * The lock serializes registration HTTP calls ([PushIngress.onFcmToken])
     * and revoke HTTP calls ([EventClient.revokeDevice]) against enable() and
     * disable().  A disable() call waits for any in-flight registration HTTP
     * call to complete before flipping state; it then schedules the revoke
     * worker.  The in-flight registration finishes (committing its result)
     * before disable takes effect — this satisfies the requirement that the
     * current in-flight registration must finish before disable/revoke follows.
     *
     * NOTE: This lock is in-memory only and does NOT survive process death.
     * The PushPrefs state machine self-heals on every restart because every
     * operation re-reads state inside the lock.  WorkManager work is
     * persisted by WorkManager itself across reboot (see [FcmRevokeWorker]).
     */
    suspend inline fun withLock(crossinline block: suspend () -> Unit) {
        _withLock { block() }
    }

    /** Internal lock implementation: non-inline so we can call it from
     *  [withLockReturning] without the "inline function cannot access private" error. */
    @PublishedApi
    internal suspend fun _withLock(block: suspend () -> Unit) {
        lifecycleMutex.withLock { block() }
    }

    /**
     * Execute [block] under the lifecycle serialization lock, returning the
     * result.  Same non-reentrant guarantees as [withLock].
     */
    suspend inline fun <T> withLockReturning(crossinline block: suspend () -> T): T {
        return _withLockReturning { block() }
    }

    @PublishedApi
    internal suspend fun <T> _withLockReturning(block: suspend () -> T): T =
        lifecycleMutex.withLock { block() }

    suspend fun enable(context: Context) {
        val app = context.applicationContext
        withLock {
            val prefs = PushPrefs(app)
            prefs.enable()
            if (!LifecycleGuards.shouldEnqueueRegistration(prefs.isEnabled(), prefs.isPendingRevoke(), prefs.isPendingCredentialClear())) return@withLock
            prefs.setRegistrationState(FcmRegistrationState.REGISTERING)
            FcmTokenRegistration.enqueueCurrent(app)
        }
    }

    suspend fun disable(context: Context) {
        val app = context.applicationContext
        withLock {
            val prefs = PushPrefs(app)
            // 1. Immediately disable local delivery.
            prefs.disable()
            // 2. Mark revoke as pending (blocks any concurrent/future registration).
            prefs.setPendingRevoke(true)
            prefs.setRegistrationState(FcmRegistrationState.UNREGISTERING)
            // 3. Retain encrypted device registration — only cleared on success.
            // 4. Schedule the network-constrained revoke worker (persistent across
            //    reboot via WorkManager).
            FcmRevokeWorker.schedule(app)
            // 5. Cancel any pending registration work so it does not run while
            //    unregistering.
            WorkManager.getInstance(app).cancelUniqueWork(FcmTokenRegistration.WORK_NAME)
        }
    }
}
