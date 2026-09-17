package dk.foss.jarvis.data

import dk.foss.jarvis.hermes.HermesHttpError
import dk.foss.jarvis.net.BlockedRequest

/**
 * Maps low-level connection/test exceptions to user-facing messages.
 *
 * No implementation details (socket errors, HTTP status codes, gate rejection
 * reasons) are exposed to the user — only safe, actionable messages.
 */
object ConnectionErrorMapper {

    /**
     * Map a failure from [HermesClient.testConnection] to a semantic user
     * message.  The returned string is always safe for direct display in the
     * UI — it never contains technical stack traces, IP addresses, or
     * implementation-specific codes.
     *
     * @param throwable the failure from HermesClient.testConnection()
     * @return a user-friendly error message starting with ✕
     */
    fun mapTestConnectionError(to: Throwable): String {
        return when (to) {
            is HermesHttpError -> {
                // rpcCode can be null; message is always present (non-nullable).
                val detail = to.rpcCode ?: to.message
                when {
                    detail.contains("auth", ignoreCase = true) ||
                            detail.contains("unauthorized", ignoreCase = true) ||
                            detail.contains("forbidden", ignoreCase = true) ||
                            detail == "401" ||
                            detail == "403" ||
                            detail.contains("gateway_auth", ignoreCase = true) -> {
                        "✕ Autenticación incorrecta. Verifica tu API key."
                    }
                    detail.contains("session_not_found", ignoreCase = true) ||
                            detail == "404" -> {
                        "✕ No se pudo conectar. Verifica la URL del servidor."
                    }
                    else -> {
                        "✕ No se pudo conectar con el servidor. Verifica tu conexión."
                    }
                }
            }
            is BlockedRequest -> {
                "✕ Conexión no permitida."
            }
            // HttpDowngradeNotAllowed extends InvalidConnectionSettings, so check it first.
            is HttpDowngradeNotAllowed -> {
                "✕ No se puede cambiar de HTTPS a HTTP. Manteniendo configuración anterior."
            }
            is InvalidConnectionSettings -> {
                "✕ La dirección no es válida. Verifica el formato de la URL."
            }
            else -> {
                // Generic network error — no implementation details exposed
                "✕ No se pudo conectar con el servidor. Verifica tu conexión."
            }
        }
    }

    /**
     * Map a settings-save / updateConnection failure to a semantic message.
     * Used when the UI wants to display why the settings were not saved.
     *
     * @return a user-friendly error message starting with ✕
     */
    fun mapSaveError(to: Throwable): String {
        return when (to) {
            is HttpDowngradeNotAllowed -> {
                "✕ No se puede cambiar de HTTPS a HTTP. Manteniendo configuración anterior."
            }
            is InvalidConnectionSettings -> {
                "✕ La dirección no es válida. Verifica el formato de la URL."
            }
            is BlockedRequest -> {
                "✕ Conexión no permitida."
            }
            else -> {
                "✕ No se pudo guardar la configuración. Reintenta más tarde."
            }
        }
    }
}