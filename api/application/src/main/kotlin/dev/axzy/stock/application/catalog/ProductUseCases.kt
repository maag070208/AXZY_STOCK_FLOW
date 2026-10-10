package dev.axzy.stock.application.catalog

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.application.shared.toOutcome
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.catalog.CatalogLimits
import dev.axzy.stock.domain.catalog.Product
import dev.axzy.stock.domain.catalog.UnitOfMeasure
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.common.flatMap
import dev.axzy.stock.domain.common.map
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.application.shared.toFailure
import dev.axzy.stock.domain.value.ProductCode

/**
 * Los casos de uso de productos (M01).
 *
 * Todos autorizan con `product.*` **antes** de tocar la base, y todos los que
 * escriben dejan su registro en la bitácora **en la misma transacción** (U1): un
 * catálogo que cambia sin dejar rastro no se puede reconstruir.
 */

/** El producto tal como llega del cliente. Los campos son opcionales para poder decir CUÁL falta. */
data class ProductInput(
    val productCode: String? = null,
    val name: String? = null,
    val description: String? = null,
    val unitMeasure: String? = null,
)

class ListProducts(private val products: Products, private val access: AccessDecider) {

    fun execute(actor: Actor, filter: ProductFilter, request: PageRequest, origin: RequestOrigin): Outcome<Page<ProductView>> =
        access.authorizeOr(actor, "product.view", origin) { products.page(filter, request).asRight() }
}

class GetProduct(private val products: Products, private val access: AccessDecider) {

    fun execute(actor: Actor, id: Long, origin: RequestOrigin): Outcome<ProductView> =
        access.authorizeOr(actor, "product.view", origin, entityId = "$id") {
            products.findById(id)?.takeIf { it.product.active }
                ?.asRight()
                ?: DomainError.ProductNotFound("$id").toFailure().asLeft()
        }
}

class CreateProduct(
    private val products: Products,
    private val units: UnitsOfMeasure,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {
    fun execute(actor: Actor, body: ProductInput, origin: RequestOrigin): Outcome<ProductView> =
        access.authorizeOr(actor, "product.create", origin) { grant ->
            prepared(body).flatMap { input: PreparedProduct ->
                val created: Outcome<ProductView> = if (products.findByCode(input.code) != null) {
                    DomainError.DuplicateProductCode(input.code.value).toFailure().asLeft()
                } else {
                    tx.inTransaction {
                        val created = products.insert(
                            NewProduct(
                                code = input.code,
                                name = input.name,
                                description = input.description,
                                unitMeasureId = input.unit.id,
                                unitMeasure = input.unit.code,
                            ),
                        )
                        audit.record(
                            auditEvents.byActor(
                                actor = actor,
                                origin = origin,
                                action = "PRODUCT_CREATED",
                                level = AuditLevel.RELEVANT,
                                entity = AuditEntity("product", created.product.id.toString(), created.product.code.value),
                                grant = grant,
                                newState = created.product.snapshot(),
                            ),
                        )
                        created.asRight()
                    }
                }
                created
            }
        }

    private fun prepared(body: ProductInput): Outcome<PreparedProduct> =
        requireCode(body.productCode).flatMap { code: ProductCode ->
            requireName(body.name).flatMap { name: String ->
                resolveUnit(units, body.unitMeasure).map { unit: UnitOfMeasure ->
                    PreparedProduct(code, name, body.description?.trim()?.ifBlank { null }, unit)
                }
            }
        }
}

class UpdateProduct(
    private val products: Products,
    private val units: UnitsOfMeasure,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {
    fun execute(actor: Actor, id: Long, body: ProductInput, origin: RequestOrigin): Outcome<ProductView> =
        access.authorizeOr(actor, "product.edit", origin, entityId = "$id") { grant ->
            val current = products.findById(id)?.takeIf { it.product.active }
                ?: return@authorizeOr DomainError.ProductNotFound("$id").toFailure().asLeft()

            prepared(body).flatMap { input: PreparedProduct ->
                current.product.withUpdated(
                    code = input.code,
                    name = input.name,
                    description = input.description,
                    unitMeasureId = input.unit.id,
                    unitMeasure = input.unit.code,
                    active = current.product.active,
                ).toOutcome().flatMap { updated: Product ->
                    val savedOutcome: Outcome<ProductView> = tx.inTransaction {
                        val saved = products.update(updated)
                        audit.record(
                            auditEvents.byActor(
                                actor = actor,
                                origin = origin,
                                action = "PRODUCT_UPDATED",
                                level = AuditLevel.RELEVANT,
                                entity = AuditEntity("product", saved.product.id.toString(), saved.product.code.value),
                                grant = grant,
                                previousState = current.product.snapshot(),
                                newState = saved.product.snapshot(),
                            ),
                        )
                        saved.asRight()
                    }
                    savedOutcome
                }
            }
        }

    private fun prepared(body: ProductInput): Outcome<PreparedProduct> =
        requireCode(body.productCode).flatMap { code: ProductCode ->
            requireName(body.name).flatMap { name: String ->
                resolveUnit(units, body.unitMeasure).map { unit: UnitOfMeasure ->
                    PreparedProduct(code, name, body.description?.trim()?.ifBlank { null }, unit)
                }
            }
        }
}

class DeactivateProduct(
    private val products: Products,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {
    /**
     * La baja es lógica y **exige motivo**: es la diferencia entre «esta
     * referencia se dejó de usar» y un borrado que nadie puede explicar después.
     */
    fun execute(actor: Actor, id: Long, reason: String?, origin: RequestOrigin): Outcome<ProductView> =
        access.authorizeOr(actor, "product.delete", origin, entityId = "$id") { grant ->
            val current = products.findById(id)
                ?: return@authorizeOr DomainError.ProductNotFound("$id").toFailure().asLeft()
            if (reason.isNullOrBlank()) {
                return@authorizeOr failure(
                    FailureKind.RULE_VIOLATION, "REASON_REQUIRED",
                    "Se requiere un motivo para desactivar un producto.",
                )
            }
            current.product.deactivate().toOutcome().flatMap { deactivated: Product ->
                val stock = products.stockOf(deactivated.id)
                val deactivatedOutcome: Outcome<ProductView> = if (stock.isPositive) {
                    DomainError.ProductHasStock(stock.toString()).toFailure().asLeft()
                } else {
                    tx.inTransaction {
                        val saved = products.update(deactivated)
                        audit.record(
                            auditEvents.byActor(
                                actor = actor,
                                origin = origin,
                                action = "PRODUCT_DEACTIVATED",
                                level = AuditLevel.RELEVANT,
                                entity = AuditEntity("product", saved.product.id.toString(), saved.product.code.value),
                                grant = grant,
                                previousState = current.product.snapshot(),
                                newState = saved.product.snapshot(),
                                reason = reason.trim(),
                            ),
                        )
                        saved.asRight()
                    }
                }
                deactivatedOutcome
            }
        }
}

// ── Validación de campos, compartida por alta y edición ──────────────────────

private data class PreparedProduct(
    val code: ProductCode,
    val name: String,
    val description: String?,
    val unit: UnitOfMeasure,
)

private fun requireCode(raw: String?): Outcome<ProductCode> = when {
    raw.isNullOrBlank() ->
        failure(FailureKind.RULE_VIOLATION, "FIELD_REQUIRED", "El código de producto es obligatorio.")
    else -> ProductCode.validate(raw).fold(
        onSuccess = { it.asRight() },
        onFailure = { DomainError.InvalidProductCode(raw, it.message ?: "formato inválido").toFailure().asLeft() },
    )
}

private fun requireName(raw: String?): Outcome<String> = when {
    raw.isNullOrBlank() ->
        failure(FailureKind.RULE_VIOLATION, "FIELD_REQUIRED", "El nombre del producto es obligatorio.")
    raw.trim().length > CatalogLimits.PRODUCT_NAME ->
        failure(FailureKind.RULE_VIOLATION, "FIELD_TOO_LONG", "El nombre supera ${CatalogLimits.PRODUCT_NAME} caracteres.")
    else -> raw.trim().asRight()
}

/**
 * La unidad de medida es un **catálogo cerrado**: no se acepta texto libre, que es
 * lo que en el legacy producía `KG`/`Kg`/`KGS` como tres unidades distintas.
 */
private fun resolveUnit(units: UnitsOfMeasure, raw: String?): Outcome<UnitOfMeasure> = when {
    raw.isNullOrBlank() ->
        failure(FailureKind.RULE_VIOLATION, "FIELD_REQUIRED", "La unidad de medida es obligatoria.")
    else -> {
        val wanted = raw.trim().uppercase()
        units.all().firstOrNull { it.code == wanted }?.asRight()
            ?: DomainError.UnknownUnitMeasure(raw).toFailure().asLeft()
    }
}

private fun Product.snapshot(): Map<String, Any?> = mapOf(
    "productCode" to code.value,
    "name" to name,
    "description" to description,
    "unitMeasure" to unitMeasure,
    "active" to active,
)
