package dev.axzy.stock.application.audit

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.common.asRight
import java.time.Instant
import java.time.LocalDate

/**
 * La consulta de la bitácora (M08, docs/flujos/auditoria.md §5, U5).
 *
 * La bitácora **ya se escribe** en cada escritura desde la fase 3; lo que faltaba era
 * poder leerla. Y leerla es un acto que también se registra: quien consulta la bitácora
 * está viendo quién hizo qué, y eso es información sensible.
 */

/** Una fila de la bitácora, con lo que hace falta para responder «quién, qué, cuándo y por qué». */
data class AuditEntryView(
    val id: Long,
    val occurredAt: Instant,
    val businessDate: LocalDate,
    val actorUserId: Long?,
    val actorUsername: String?,
    val actorName: String?,
    val actorRoles: List<String>,
    val channel: String,
    val entityType: String,
    val entityId: String?,
    val entityLabel: String?,
    val action: String,
    val level: String,
    val previousState: String?,
    val newState: String?,
    val reason: String?,
    val note: String?,
    val permissionKey: String?,
    val decision: String?,
    val result: String,
    val errorCode: String?,
    val errorMessage: String?,
    /** `SERVICE_PRINTER` con su credencial: un hecho sin persona detrás se distingue. */
    val serviceKeyId: Long?,
    val requestId: String?,
    val idempotencyKey: String?,
)

/**
 * Los filtros de la consulta.
 *
 * `from` y `to` son **fechas de negocio** (`business_date`), que la tabla ya guarda: un
 * cierre se explica por día de trabajo, no por el huso horario de quien consulta. Un
 * rango invertido se rechaza en lugar de devolver nada, que es lo que hace imposible
 * distinguir «no hay nada» de «pedí mal el rango».
 */
data class AuditFilters(
    val entityType: String?,
    val entityId: String?,
    val actorUserId: Long?,
    val action: String?,
    val level: String?,
    val result: String?,
    val channel: String?,
    val from: LocalDate?,
    val to: LocalDate?,
) {
    companion object {
        /** Un rango de fechas invertido no es un rango vacío: es un error de quien pregunta. */
        fun isInverted(from: LocalDate?, to: LocalDate?): Boolean = from != null && to != null && from > to
    }
}

interface AuditQueries {
    fun page(filters: AuditFilters, request: PageRequest): Page<AuditEntryView>
}

/**
 * Consulta la bitácora y **deja constancia de la consulta**.
 *
 * El registro va con nivel `ACCESS`: no mueve inventario ni dinero, pero sí expone quién
 * hizo qué, y el propio módulo dice que su consulta se registra como acceso. Se escribe
 * **después** de leer, en su propia transacción: si el registro fallara, la consulta ya
 * se hizo y lo que no puede quedar es el hecho sin rastro.
 */
class QueryAuditLog(
    private val queries: AuditQueries,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
) {

    fun execute(
        actor: Actor,
        filters: AuditFilters,
        request: PageRequest,
        origin: RequestOrigin,
    ): Outcome<Page<AuditEntryView>> = access.authorizeOr(actor, "audit.view", origin) { grant ->
        val page = queries.page(filters, request)
        audit.record(
            auditEvents.byActor(
                actor = actor,
                origin = origin,
                action = "AUDIT_QUERIED",
                level = AuditLevel.ACCESS,
                entity = AuditEntity("audit_log", null, "Consulta de la bitácora"),
                grant = grant,
                newState = mapOf(
                    "filters" to filters.toString(),
                    "page" to request.page,
                    "size" to request.size,
                    "totalItems" to page.totalItems,
                ),
            ),
        )
        page.asRight()
    }
}
