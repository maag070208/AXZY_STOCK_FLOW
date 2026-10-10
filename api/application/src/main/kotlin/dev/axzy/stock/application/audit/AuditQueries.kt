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
 * The audit log query (M08, docs/flujos/auditoria.md §5, U5).
 *
 * The audit log **is already written** on every write since phase 3; what was missing was
 * being able to read it. And reading it is an act that is also recorded: whoever queries the audit log
 * is seeing who did what, and that is sensitive information.
 */

/** An audit log row, with what is needed to answer "who, what, when and why". */
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
    /** `SERVICE_PRINTER` with its credential: a fact without a person behind it is told apart. */
    val serviceKeyId: Long?,
    val requestId: String?,
    val idempotencyKey: String?,
)

/**
 * The query filters.
 *
 * `from` and `to` are **business dates** (`business_date`), which the table already stores: a
 * close is explained by working day, not by the time zone of whoever queries. An
 * inverted range is rejected instead of returning nothing, which is what makes it impossible to
 * tell "there is nothing" from "I asked for the wrong range".
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
        /** An inverted date range is not an empty range: it is an error on the part of the one asking. */
        fun isInverted(from: LocalDate?, to: LocalDate?): Boolean = from != null && to != null && from > to
    }
}

interface AuditQueries {
    fun page(filters: AuditFilters, request: PageRequest): Page<AuditEntryView>
}

/**
 * Queries the audit log and **records the query**.
 *
 * The record goes at level `ACCESS`: it does not move inventory or money, but it does expose who
 * did what, and the module itself says that its query is recorded as access. It is written
 * **after** reading, in its own transaction: if the record failed, the query would already
 * have been done, and what cannot remain is the fact without a trace.
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
                entity = AuditEntity("audit_log", null, "Audit log query"),
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
