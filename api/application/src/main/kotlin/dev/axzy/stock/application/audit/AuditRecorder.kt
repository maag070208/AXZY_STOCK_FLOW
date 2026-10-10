package dev.axzy.stock.application.audit

import dev.axzy.stock.domain.audit.AuditEvent

/**
 * Escribe en la bitácora. **Participa en la transacción en curso**: si el caso de uso revierte,
 * el registro tampoco queda, y si el registro falla, el caso de uso revierte.
 *
 * Un cambio sin registro es justo lo que la bitácora existe para impedir
 * (docs/flujos/auditoria.md, prueba U1).
 */
fun interface AuditRecorder {
    fun record(event: AuditEvent): Long
}

/**
 * Registra una denegación de acceso en una transacción PROPIA.
 *
 * Es un puerto distinto de [AuditRecorder] porque su contrato es el contrario: una denegación
 * tiene que quedar escrita aunque el caso de uso que la provocó revierta. Con un solo puerto,
 * el intento denegado desaparecería junto con el rollback.
 */
fun interface AccessDenialLog {
    fun record(event: AuditEvent)
}
