package dk.foss.jarvis.push

/** The registration operation required when a transport supplies an endpoint. */
enum class EndpointAction {
    REGISTER,
    UPDATE,
    /** Reserved for transports that can determine that the endpoint is unchanged. */
    UPDATE_WITH_SAME,
}

/** Pure decision shared by endpoint ingress and JVM tests. */
fun planEndpoint(existingDeviceId: String?, @Suppress("UNUSED_PARAMETER") endpoint: String): EndpointAction =
    if (existingDeviceId == null) EndpointAction.REGISTER else EndpointAction.UPDATE
