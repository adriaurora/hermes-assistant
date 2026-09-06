package dk.foss.jarvis.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmRegistrationPolicyTest {
    @Test fun registrationRequiresEnabledStateAndToken() {
        assertTrue(FcmRegistrationPolicy.shouldRegister(true, "opaque-fcm-token"))
        assertFalse(FcmRegistrationPolicy.shouldRegister(false, "opaque-fcm-token"))
        assertFalse(FcmRegistrationPolicy.shouldRegister(true, null))
        assertFalse(FcmRegistrationPolicy.shouldRegister(true, ""))
    }

    @Test fun `pendingRevoke blocks registration`() {
        assertFalse(FcmRegistrationPolicy.shouldRegister(true, "token", pendingRevoke = true))
    }
    @Test fun `pending credential clear blocks registration`() {
        assertFalse(FcmRegistrationPolicy.shouldRegister(true, "token", pendingCredentialClear = true))
        assertFalse(FcmRegistrationPolicy.shouldRegister(true, "token", pendingRevoke = true, pendingCredentialClear = true))
    }
    @Test fun `registration allowed when no revoke and no clear pending`() {
        assertTrue(FcmRegistrationPolicy.shouldRegister(true, "token", pendingRevoke = false, pendingCredentialClear = false))
    }

    @Test fun `disabled blocks registration regardless of token`() {
        assertFalse(FcmRegistrationPolicy.shouldRegister(false, "token", pendingRevoke = false))
        assertFalse(FcmRegistrationPolicy.shouldRegister(false, "token", pendingRevoke = true))
    }

    @Test fun `blank token blocks registration regardless of state`() {
        assertFalse(FcmRegistrationPolicy.shouldRegister(true, null, pendingRevoke = true))
        assertFalse(FcmRegistrationPolicy.shouldRegister(true, "", pendingRevoke = true))
        assertFalse(FcmRegistrationPolicy.shouldRegister(true, "   ", pendingRevoke = true))
    }

    @Test fun `all conditions must pass`() {
        assertTrue(FcmRegistrationPolicy.shouldRegister(true, "token", pendingRevoke = false))
        assertFalse(FcmRegistrationPolicy.shouldRegister(false, "token", pendingRevoke = false))
        assertFalse(FcmRegistrationPolicy.shouldRegister(true, null, pendingRevoke = false))
        assertFalse(FcmRegistrationPolicy.shouldRegister(true, "token", pendingRevoke = true))
    }
}
