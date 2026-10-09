package dev.axzy.stock.backend.application.audit

import java.time.LocalDate

/**
 * De dónde viene la petición. Coincide con el enum `audit_channel` de PostgreSQL: un canal
 * inventado falla al insertar en lugar de guardarse como texto libre.
 */
enum class AuditChannel { WEB, MOBILE, MOBILE_OFFLINE, SERVICE_ERP, SERVICE_PRINTER, SERVICE_JOB, MIGRATION, SYSTEM }

/**
 * Cuánto importa lo que se registra. `CRITICAL` exige la traza de la decisión de acceso
 * (CHECK `audit_critical_has_trace`), así que lo importante tiene que poder justificarse.
 */
enum class AuditLevel { CRITICAL, RELEVANT, ACCESS, TECHNICAL }

enum class AuditResult { SUCCESS, FAILURE, DENIED, PARTIAL }

enum class AuditDecision { ALLOWED, DENIED, NOT_APPLICABLE }

/**
 * Un registro de bitácora, con las seis dimensiones en el orden en que se leen:
 * quién → desde dónde → sobre qué → qué → con qué permiso → y cómo salió.
 *
 * El caso de uso lo construye; la persistencia lo escribe **dentro de la transacción de la
 * operación que audita**. No hay forma de registrarlo fuera de ella.
 */
data class AuditEntry(
    // ── Quién ──────────────────────────────────────────────────────────────
    /** `null` solo si [channel] no es humano: la base lo impone con `audit_human_has_actor`. */
    val actorUserId: Long?,
    val actorUsername: String? = null,
    val actorRoles: List<String>? = null,
    // ── Desde dónde ────────────────────────────────────────────────────────
    val channel: AuditChannel,
    val sourceIp: String? = null,
    val sourceUserAgent: String? = null,
    val sourceDevice: String? = null,
    // ── Sobre qué ──────────────────────────────────────────────────────────
    val entityType: String,
    val entityId: String? = null,
    val entityLabel: String? = null,
    // ── Qué ────────────────────────────────────────────────────────────────
    val action: String,
    val level: AuditLevel = AuditLevel.RELEVANT,
    /** JSON con el estado anterior. Lleva los valores, no solo el nombre del estado. */
    val previousState: String? = null,
    val newState: String? = null,
    val reason: String? = null,
    val note: String? = null,
    // ── Con qué permiso ────────────────────────────────────────────────────
    val permissionKey: String? = null,
    val decision: AuditDecision = AuditDecision.ALLOWED,
    val decisionCode: String? = null,
    // ── Cómo salió ─────────────────────────────────────────────────────────
    val result: AuditResult = AuditResult.SUCCESS,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val durationMs: Int? = null,
    // ── Trazabilidad ───────────────────────────────────────────────────────
    val correlationId: String? = null,
    val causationId: String? = null,
    val idempotencyKey: String? = null,
    val requestId: String? = null,
    val businessDate: LocalDate? = null,
) {
    companion object {
        /**
         * El canal de una acción iniciada por una persona, o `SYSTEM` si no hay persona.
         *
         * Sin usuario no hay acción humana que atribuir, y la base exige actor para los canales
         * humanos. Las rutas siempre pasan un usuario autenticado; el caso sin usuario son las
         * tareas del sistema y las pruebas.
         */
        fun channelFor(actorUserId: Long?): AuditChannel =
            if (actorUserId != null) AuditChannel.WEB else AuditChannel.SYSTEM
    }
}

/** Un acceso denegado: quién lo intentó, desde dónde y contra qué. */
data class AccessDenial(
    val actorUserId: Long?,
    val actorUsername: String?,
    val channel: AuditChannel,
    val sourceIp: String?,
    val permissionKey: String,
    val entityType: String,
    val entityId: String?,
    val reason: String,
)

/**
 * Registro de accesos denegados.
 *
 * Es la única escritura de bitácora que **no** participa de una transacción de negocio: negar
 * el acceso ya es la decisión de seguridad, y un fallo al anotarla no debe convertir un `403`
 * en un `500`. Devuelve `null` si no se pudo registrar.
 */
fun interface AccessDenialLog {
    fun recordDenied(denial: AccessDenial): Long?
}
