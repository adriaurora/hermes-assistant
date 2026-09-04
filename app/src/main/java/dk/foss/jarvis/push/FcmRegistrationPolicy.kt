package dk.foss.jarvis.push

object FcmRegistrationPolicy {
    fun shouldRegister(enabled: Boolean, token: String?): Boolean = enabled && !token.isNullOrBlank()
}
