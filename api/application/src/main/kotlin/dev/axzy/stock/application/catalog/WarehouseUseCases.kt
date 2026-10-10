package dev.axzy.stock.application.catalog

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.catalog.CatalogLimits
import dev.axzy.stock.domain.catalog.Warehouse
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.application.shared.toFailure

/** The warehouse as it arrives from the client. */
data class WarehouseInput(
    val code: String? = null,
    val name: String? = null,
    val address: String? = null,
    val isDefault: Boolean? = null,
)

/**
 * The warehouse listing **is not paginated**, and deliberately so: it is a closed and tiny
 * catalog that the interface needs whole to fill a dropdown.
 * (docs/api/convenciones.md §1.4).
 */
class ListWarehouses(private val warehouses: Warehouses, private val access: AccessDecider) {

    fun execute(actor: Actor, query: String?, active: Boolean?, origin: RequestOrigin): Outcome<List<Warehouse>> =
        access.authorizeOr(actor, "warehouse.view", origin) { warehouses.list(query, active).asRight() }
}

class CreateWarehouse(
    private val warehouses: Warehouses,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {
    fun execute(actor: Actor, body: WarehouseInput, origin: RequestOrigin): Outcome<Warehouse> =
        access.authorizeOr(actor, "warehouse.create", origin) { grant ->
            val rawCode = body.code
            if (rawCode.isNullOrBlank()) {
                return@authorizeOr failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED", "The warehouse code is required.",
                )
            }
            if (rawCode.trim().length > CatalogLimits.WAREHOUSE_CODE) {
                return@authorizeOr failure(
                    FailureKind.RULE_VIOLATION, "FIELD_TOO_LONG",
                    "The warehouse code exceeds ${CatalogLimits.WAREHOUSE_CODE} characters.",
                )
            }
            val code = rawCode.trim().uppercase()

            val rawName = body.name
            if (rawName.isNullOrBlank()) {
                return@authorizeOr failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED", "The warehouse name is required.",
                )
            }
            if (rawName.trim().length > CatalogLimits.WAREHOUSE_NAME) {
                return@authorizeOr failure(
                    FailureKind.RULE_VIOLATION, "FIELD_TOO_LONG",
                    "The warehouse name exceeds ${CatalogLimits.WAREHOUSE_NAME} characters.",
                )
            }
            val name = rawName.trim()

            if (warehouses.findByCode(code) != null) {
                return@authorizeOr DomainError.DuplicateWarehouseCode(code).toFailure().asLeft()
            }

            tx.inTransaction {
                // The partial index `warehouses_one_default_idx` admits only one
                // default: if this one is, the previous one stops being it in the
                // SAME transaction. There are never two, not even for an instant.
                if (body.isDefault == true) warehouses.clearDefault()
                val created = warehouses.insert(
                    NewWarehouse(
                        code = code,
                        name = name,
                        address = body.address?.trim()?.ifBlank { null },
                        isDefault = body.isDefault == true,
                    ),
                )
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "WAREHOUSE_CREATED",
                        level = AuditLevel.RELEVANT,
                        entity = AuditEntity("warehouse", created.id.toString(), created.code),
                        grant = grant,
                        newState = created.snapshot(),
                    ),
                )
                created.asRight()
            }
        }
}

class UpdateWarehouse(
    private val warehouses: Warehouses,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {
    fun execute(actor: Actor, id: Long, body: WarehouseInput, origin: RequestOrigin): Outcome<Warehouse> =
        access.authorizeOr(actor, "warehouse.edit", origin, entityType = "warehouse", entityId = "$id") { grant ->
            val current = warehouses.findById(id)
                ?: return@authorizeOr failure(
                    FailureKind.NOT_FOUND, "UNKNOWN_WAREHOUSE", "Warehouse $id does not exist.",
                )
            val code = body.code?.trim()?.uppercase() ?: current.code
            val name = body.name?.trim()?.ifBlank { null } ?: current.name
            if (code != current.code && warehouses.findByCode(code) != null) {
                return@authorizeOr DomainError.DuplicateWarehouseCode(code).toFailure().asLeft()
            }

            /*
             * The default flag **cannot be removed without setting another one**: the
             * system needs a default warehouse to know where what arrives
             * without a declared destination lands. An `isDefault = false` on the
             * default one keeps it, instead of leaving the system without one.
             */
            val wantsDefault = when {
                body.isDefault == true -> true
                current.isDefault -> true
                else -> false
            }
            val updated = current.copy(
                code = code,
                name = name,
                address = body.address?.trim()?.ifBlank { null } ?: current.address,
                isDefault = wantsDefault,
            )

            tx.inTransaction {
                if (wantsDefault && !current.isDefault) warehouses.clearDefault()
                val saved = warehouses.update(updated)
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "WAREHOUSE_UPDATED",
                        level = AuditLevel.RELEVANT,
                        entity = AuditEntity("warehouse", saved.id.toString(), saved.code),
                        grant = grant,
                        previousState = current.snapshot(),
                        newState = saved.snapshot(),
                    ),
                )
                saved.asRight()
            }
        }
}

private fun Warehouse.snapshot(): Map<String, Any?> = mapOf(
    "code" to code,
    "name" to name,
    "address" to address,
    "isDefault" to isDefault,
    "active" to active,
)
