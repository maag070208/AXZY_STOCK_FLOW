package dev.axzy.stock.application.shared

import dev.axzy.stock.domain.audit.AuditChannel

/**
 * De dónde viene una petición. Viaja como argumento explícito hasta el caso de uso: la bitácora
 * registra el canal y la red, y no los puede leer de un contexto global.
 */
data class RequestOrigin(
    val channel: AuditChannel,
    val ip: String? = null,
    val userAgent: String? = null,
    val deviceId: String? = null,
    /** Identificador de la petición (`X-Request-Id`): correlaciona la respuesta con la bitácora. */
    val requestId: String? = null,
) {
    companion object {
        /** Una tarea interna, sin petición que la origine. */
        fun system() = RequestOrigin(channel = AuditChannel.SYSTEM)
    }
}
