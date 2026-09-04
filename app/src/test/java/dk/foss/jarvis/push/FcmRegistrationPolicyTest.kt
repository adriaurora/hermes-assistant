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
}
