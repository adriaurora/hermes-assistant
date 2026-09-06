package dk.foss.jarvis.push

import dk.foss.jarvis.hermes.EventFetchException
import dk.foss.jarvis.hermes.FetchFailureKind
import org.junit.Assert.assertEquals
import org.junit.Test

class V1PolicyTest {

    // ── EnrollmentPolicy ──────────────────────────────────────────────────

    @Test
    fun `LEGACY transport always returns None regardless of registration and secret`() {
        assertEquals(EnrollmentAction.None, EnrollmentPolicy.decide(PushTransport.LEGACY, true, true))
        assertEquals(EnrollmentAction.None, EnrollmentPolicy.decide(PushTransport.LEGACY, false, false))
    }

    @Test
    fun `V1 no registration returns RegisterFresh with legacyRevokeFirst=false`() {
        val action = EnrollmentPolicy.decide(PushTransport.V1, false, false)
        assertEquals(EnrollmentAction.RegisterFresh::class, action::class)
        if (action is EnrollmentAction.RegisterFresh) assertEquals(false, action.legacyRevokeFirst)
    }

    @Test
    fun `V1 has registration no secret returns RegisterFresh with legacyRevokeFirst=true`() {
        val action = EnrollmentPolicy.decide(PushTransport.V1, true, false)
        assertEquals(EnrollmentAction.RegisterFresh::class, action::class)
        if (action is EnrollmentAction.RegisterFresh) assertEquals(true, action.legacyRevokeFirst)
    }

    @Test
    fun `V1 has registration and secret returns UpdateToken`() {
        assertEquals(EnrollmentAction.UpdateToken, EnrollmentPolicy.decide(PushTransport.V1, true, true))
    }

    // ── RevokeV1Policy ────────────────────────────────────────────────────

    @Test
    fun `null error maps to ConfirmAndClear`() {
        assertEquals(FcmRevokePolicy.RevokeOutcome.RevokeSuccess, RevokeV1Policy.classify(null))
    }

    @Test
    fun `device_not_found maps to ConfirmAndClear`() {
        val err = EventFetchException(FetchFailureKind.HTTP, 404, rpcCode = "device_not_found")
        assertEquals(FcmRevokePolicy.RevokeOutcome.RevokeSuccess, RevokeV1Policy.classify(err))
    }

    @Test
    fun `device_revoked maps to ConfirmAndClear`() {
        val err = EventFetchException(FetchFailureKind.HTTP, 409, rpcCode = "device_revoked")
        assertEquals(FcmRevokePolicy.RevokeOutcome.RevokeSuccess, RevokeV1Policy.classify(err))
    }

    @Test
    fun `device_auth_failed maps to KeepAndError`() {
        val err = EventFetchException(FetchFailureKind.HTTP, 403, rpcCode = "device_auth_failed")
        assertEquals(FcmRevokePolicy.RevokeOutcome.CredentialRejected, RevokeV1Policy.classify(err))
    }

    @Test
    fun `unknown rpcCode maps to RetryAgain`() {
        val err = EventFetchException(FetchFailureKind.HTTP, 400, rpcCode = "weird_code")
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, RevokeV1Policy.classify(err))
    }

    @Test
    fun `NETWORK failure maps to Retry`() {
        val err = EventFetchException(FetchFailureKind.NETWORK, cause = java.net.ConnectException())
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, RevokeV1Policy.classify(err))
    }

    @Test
    fun `HTTP 503 maps to Retry`() {
        val err = EventFetchException(FetchFailureKind.HTTP, 503, cause = Exception("server"))
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, RevokeV1Policy.classify(err))
    }

    @Test
    fun `HTTP 401 maps to Retry`() {
        val err = EventFetchException(FetchFailureKind.HTTP, 401, cause = Exception("unauthorized"))
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, RevokeV1Policy.classify(err))
    }
}