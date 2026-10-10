package dev.axzy.stock.application.audit

import dev.axzy.stock.domain.audit.AuditEvent

/**
 * Writes to the audit log. **It joins the transaction in progress**: if the use case rolls back,
 * the record does not survive either, and if the record fails, the use case rolls back.
 *
 * A change without a record is exactly what the audit log exists to prevent.
 * (docs/flujos/auditoria.md, prueba U1).
 */
fun interface AuditRecorder {
    fun record(event: AuditEvent): Long
}

/**
 * Records an access denial in its OWN transaction.
 *
 * It is a different port from [AuditRecorder] because its contract is the opposite: a denial
 * has to be written even if the use case that caused it rolls back. With a single port
 * the denied attempt would disappear together with the rollback.
 */
fun interface AccessDenialLog {
    fun record(event: AuditEvent)
}
