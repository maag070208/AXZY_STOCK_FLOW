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

/** La bodega tal como llega del cliente. */
data class WarehouseInput(
    val code: String? = null,
    val name: String? = null,
    val address: String? = null,
    val isDefault: Boolean? = null,
)

/**
 * El listado de bodegas **no se pagina** y es deliberado: es un catálogo cerrado y
 * diminuto que la interfaz necesita completo para poblar un desplegable
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
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED", "El código de la bodega es obligatorio.",
                )
            }
            if (rawCode.trim().length > CatalogLimits.WAREHOUSE_CODE) {
                return@authorizeOr failure(
                    FailureKind.RULE_VIOLATION, "FIELD_TOO_LONG",
                    "El código de la bodega supera ${CatalogLimits.WAREHOUSE_CODE} caracteres.",
                )
            }
            val code = rawCode.trim().uppercase()

            val rawName = body.name
            if (rawName.isNullOrBlank()) {
                return@authorizeOr failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED", "El nombre de la bodega es obligatorio.",
                )
            }
            if (rawName.trim().length > CatalogLimits.WAREHOUSE_NAME) {
                return@authorizeOr failure(
                    FailureKind.RULE_VIOLATION, "FIELD_TOO_LONG",
                    "El nombre de la bodega supera ${CatalogLimits.WAREHOUSE_NAME} caracteres.",
                )
            }
            val name = rawName.trim()

            if (warehouses.findByCode(code) != null) {
                return@authorizeOr DomainError.DuplicateWarehouseCode(code).toFailure().asLeft()
            }

            tx.inTransaction {
                // El índice parcial `warehouses_one_default_idx` admite una sola
                // predeterminada: si esta lo es, la anterior deja de serlo en la
                // MISMA transacción. Nunca hay dos, ni un instante.
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
                    FailureKind.NOT_FOUND, "UNKNOWN_WAREHOUSE", "No existe la bodega $id.",
                )
            val code = body.code?.trim()?.uppercase() ?: current.code
            val name = body.name?.trim()?.ifBlank { null } ?: current.name
            if (code != current.code && warehouses.findByCode(code) != null) {
                return@authorizeOr DomainError.DuplicateWarehouseCode(code).toFailure().asLeft()
            }

            /*
             * La marca de predeterminada **no se puede quitar sin poner otra**: el
             * sistema necesita una bodega por defecto para saber dónde aterriza lo
             * que llega sin destino declarado. Un `isDefault = false` sobre la
             * predeterminada la conserva en lugar de dejar el sistema sin ninguna.
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
