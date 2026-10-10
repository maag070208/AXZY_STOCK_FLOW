package dev.axzy.stock.application.shared

import dev.axzy.stock.domain.audit.AuditChannel

/**
 * Where a request comes from. It travels as an explicit argument to the use case: the audit log
 * records the channel and the network, and cannot read them from a global context.
 */
data class RequestOrigin(
    val channel: AuditChannel,
    val ip: String? = null,
    val userAgent: String? = null,
    val deviceId: String? = null,
    /** Request identifier (`X-Request-Id`): it correlates the response with the audit log. */
    val requestId: String? = null,
) {
    companion object {
        /** An internal task, with no request behind it. */
        fun system() = RequestOrigin(channel = AuditChannel.SYSTEM)
    }
}
