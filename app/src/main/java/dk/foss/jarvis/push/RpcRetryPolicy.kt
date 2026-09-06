package dk.foss.jarvis.push

import dk.foss.jarvis.hermes.FetchFailureKind

enum class RpcErrorClass { RETRY, PERMANENT, REENROLL }

object RpcRetryPolicy {
    fun classify(kind: FetchFailureKind?, statusCode: Int?, rpcCode: String?): RpcErrorClass = when {
        rpcCode in setOf("device_not_found", "device_revoked", "device_auth_failed") -> RpcErrorClass.REENROLL
        rpcCode != null -> RpcErrorClass.PERMANENT
        statusCode == 401 -> RpcErrorClass.PERMANENT
        statusCode == 404 || statusCode == 405 -> RpcErrorClass.PERMANENT
        statusCode in 500..599 -> RpcErrorClass.RETRY
        kind == FetchFailureKind.NETWORK || kind == FetchFailureKind.SERIALIZATION -> RpcErrorClass.RETRY
        kind == FetchFailureKind.OTHER -> RpcErrorClass.PERMANENT
        else -> RpcErrorClass.RETRY
    }
}
