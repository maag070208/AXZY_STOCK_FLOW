package dev.axzy.stock.adapters.web.audit

import dev.axzy.stock.adapters.web.http.PageDto
import dev.axzy.stock.adapters.web.http.RequiresPermission
import dev.axzy.stock.adapters.web.http.toDto
import dev.axzy.stock.adapters.web.http.toResponse
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.audit.AuditEntryView
import dev.axzy.stock.application.audit.AuditFilters
import dev.axzy.stock.application.audit.QueryAuditLog
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.SortSpec
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.common.Either
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * La bitácora, en modo consulta (docs/api/auditoria.md).
 *
 * **Solo lectura**: no hay `PUT` ni `DELETE` porque la tabla es inmutable por
 * disparadores. Es la única ruta de auditoría, y su propia consulta queda registrada con
 * nivel `ACCESS`: quien mira la bitácora está viendo quién hizo qué.
 */
@RestController
@RequestMapping("/v1/audit")
class AuditController(private val queryAuditLog: QueryAuditLog) {

    @GetMapping("/log")
    @RequiresPermission("audit.view")
    fun log(
        @RequestParam(required = false) entityType: String?,
        @RequestParam(required = false) entityId: String?,
        @RequestParam(required = false) actorUserId: Long?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) level: String?,
        @RequestParam(required = false) result: String?,
        @RequestParam(required = false) channel: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> {
        val analyzed = PageRequest.analyze(page, size, null, setOf("id"), SortSpec("id"))
        val request = when (analyzed) {
            is Either.Left -> return analyzed.error.toResponse()
            is Either.Right -> analyzed.value
        }
        val fromDate = when (val parsed = date(from, "from")) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value
        }
        val toDate = when (val parsed = date(to, "to")) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value
        }
        if (AuditFilters.isInverted(fromDate, toDate)) {
            return failure(
                FailureKind.INVALID_REQUEST, "INVALID_FILTER_RANGE",
                "The date range is inverted: 'from' is after 'to'.",
                mapOf("from" to (from ?: ""), "to" to (to ?: "")),
            ).toResponse()
        }
        val filters = AuditFilters(
            entityType = entityType?.trim()?.takeUnless { it.isBlank() },
            entityId = entityId?.trim()?.takeUnless { it.isBlank() },
            actorUserId = actorUserId,
            action = action?.trim()?.takeUnless { it.isBlank() },
            level = level?.trim()?.uppercase()?.takeUnless { it.isBlank() },
            result = result?.trim()?.uppercase()?.takeUnless { it.isBlank() },
            channel = channel?.trim()?.uppercase()?.takeUnless { it.isBlank() },
            from = fromDate,
            to = toDate,
        )

        return queryAuditLog.execute(actor, filters, request, origin).toResponse { it.toDto { entry -> entry.toDto() } }
    }

    private fun date(raw: String?, field: String): Either<dev.axzy.stock.application.shared.Failure, LocalDate?> {
        if (raw.isNullOrBlank()) return dev.axzy.stock.domain.common.Either.Right(null)
        return runCatching { LocalDate.parse(raw.trim()) }.fold(
            onSuccess = { dev.axzy.stock.domain.common.Either.Right(it) },
            onFailure = {
                dev.axzy.stock.domain.common.Either.Left(
                    dev.axzy.stock.application.shared.Failure(
                        FailureKind.INVALID_REQUEST, "INVALID_FILTER_DATE",
                        "The date is not in the format YYYY-MM-DD.",
                        mapOf("field" to field, "value" to raw),
                    ),
                )
            },
        )
    }
}

data class AuditEntryDto(
    val id: Long,
    val occurredAt: String,
    val businessDate: String,
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
    val serviceKeyId: Long?,
    val requestId: String?,
    val idempotencyKey: String?,
)

fun AuditEntryView.toDto(): AuditEntryDto = AuditEntryDto(
    id = id,
    occurredAt = occurredAt.toString(),
    businessDate = businessDate.toString(),
    actorUserId = actorUserId,
    actorUsername = actorUsername,
    actorName = actorName,
    actorRoles = actorRoles,
    channel = channel,
    entityType = entityType,
    entityId = entityId,
    entityLabel = entityLabel,
    action = action,
    level = level,
    previousState = previousState,
    newState = newState,
    reason = reason,
    note = note,
    permissionKey = permissionKey,
    decision = decision,
    result = result,
    errorCode = errorCode,
    errorMessage = errorMessage,
    serviceKeyId = serviceKeyId,
    requestId = requestId,
    idempotencyKey = idempotencyKey,
)
