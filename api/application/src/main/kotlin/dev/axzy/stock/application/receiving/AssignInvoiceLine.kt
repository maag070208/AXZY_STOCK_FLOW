package dev.axzy.stock.application.receiving

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.identity.UserAccounts
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.application.shared.toFailure
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import java.time.Clock

/**
 * Asigna una línea de factura a un capturista (M03, `invoice.assign`).
 *
 * Es la ruta que faltaba: la columna `assigned_user_id` está en la base desde la primera
 * migración, la consulta la leía y **nadie la escribía**, así que el estado `ASSIGNED` decía
 * «alguien la tiene» sin decir quién. Repartir el conteo entre varias personas —que es como se
 * descarga un camión— no tenía forma de hacerse por la API.
 *
 * `userId` es opcional a propósito: sin él, quien llama **se asigna a sí mismo**, que es el caso
 * diario del capturista. Con él, un supervisor reparte el trabajo.
 */
class AssignInvoiceLine(
    private val invoices: Invoices,
    private val accounts: UserAccounts,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(
        actor: Actor,
        invoiceId: Long,
        lineId: Long,
        assigneeId: Long?,
        origin: RequestOrigin,
    ): Outcome<InvoiceLineView> = access.authorizeOr(actor, "invoice.assign", origin) { grant ->
        tx.inTransaction {
            val detail = invoices.detail(invoiceId)
                ?: return@inTransaction failure(
                    FailureKind.NOT_FOUND, "INVOICE_NOT_FOUND",
                    "Invoice $invoiceId does not exist.",
                    mapOf("invoiceId" to invoiceId.toString()),
                )
            val view = detail.lines.firstOrNull { it.line.id == lineId }
                ?: return@inTransaction failure(
                    FailureKind.NOT_FOUND, "INVOICE_LINE_NOT_FOUND",
                    "Invoice $invoiceId does not have line $lineId.",
                    mapOf("invoiceId" to invoiceId.toString(), "lineId" to lineId.toString()),
                )

            val userId = assigneeId ?: actor.id
            val assignee = accounts.loadActor(userId)?.actor
                ?: return@inTransaction failure(
                    FailureKind.RULE_VIOLATION, "ASSIGNEE_NOT_FOUND",
                    "User $userId, to whom the line should be assigned, does not exist.",
                    mapOf("userId" to userId.toString()),
                )

            when (val assigned = view.line.assign(userId)) {
                is Either.Left -> assigned.error.toFailure().asLeft()
                is Either.Right -> {
                    // Condicional: si alguien la contó entre la lectura y la escritura, no se le
                    // asigna trabajo ya hecho.
                    if (invoices.assignLine(lineId, userId, clock.instant()) == 0) {
                        return@inTransaction failure(
                            FailureKind.CONFLICT, "LINE_CHANGED",
                            "The line changed while it was being assigned. Look it up again.",
                            mapOf("lineId" to lineId.toString()),
                        )
                    }
                    audit.record(
                        auditEvents.byActor(
                            actor = actor,
                            origin = origin,
                            action = "INVOICE_LINE_ASSIGNED",
                            level = AuditLevel.RELEVANT,
                            entity = AuditEntity("invoice_line", lineId.toString(), detail.header.invoiceNumber),
                            grant = grant,
                            previousState = mapOf("status" to view.line.status.name),
                            newState = mapOf("status" to assigned.value.status.name, "assignedUserId" to userId.toString()),
                        ),
                    )
                    view.copy(line = assigned.value, assignedUserName = assignee.displayName).asRight()
                }
            }
        }
    }
}
