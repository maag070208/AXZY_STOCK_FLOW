package dev.axzy.stock.adapters.web.inventory

import dev.axzy.stock.adapters.web.http.RequiresPermission
import dev.axzy.stock.adapters.web.http.toDto
import dev.axzy.stock.adapters.web.http.toResponse
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.inventory.AdjustStock
import dev.axzy.stock.application.inventory.AdjustmentInput
import dev.axzy.stock.application.inventory.ApplyStockMovement
import dev.axzy.stock.application.inventory.InventoryFilters
import dev.axzy.stock.application.inventory.KardexFilter
import dev.axzy.stock.application.inventory.ListKardex
import dev.axzy.stock.application.inventory.ListStock
import dev.axzy.stock.application.inventory.MovementInput
import dev.axzy.stock.application.inventory.MovementResult
import dev.axzy.stock.application.inventory.StockFilter
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.SortSpec
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.value.Quantity
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

/**
 * Existencias, kardex y movimientos (docs/api/inventario.md).
 *
 * El controlador **no decide permisos**: el de un ajuste depende de la dirección y
 * del motivo, y eso lo resuelve el caso de uso. La anotación de cada ruta declara el
 * permiso que se comprueba; en el ajuste declara el más amplio de los cinco, porque
 * el real se sabe al leer el cuerpo — y el caso de uso vuelve a decidir.
 */
@RestController
@RequestMapping("/v1/inventory")
class InventoryController(
    private val listStock: ListStock,
    private val listKardex: ListKardex,
    private val adjustStock: AdjustStock,
    private val locateStock: ApplyStockMovement,
    private val moveStock: ApplyStockMovement,
) {

    @GetMapping("/stock")
    @RequiresPermission("inventory.view")
    fun stock(
        @RequestParam(required = false) productCode: String?,
        @RequestParam(required = false) locationCode: String?,
        @RequestParam(required = false) onlyWithStock: Boolean?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) sort: String?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> {
        val analyzed = PageRequest.analyze(page, size, sort, setOf("productCode"), SortSpec("productCode"))
        val request = when (analyzed) {
            is Either.Left -> return analyzed.error.toResponse()
            is Either.Right -> analyzed.value
        }
        val code = when (val parsed = InventoryFilters.productCode(productCode)) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value
        }
        val location = when (val parsed = InventoryFilters.locationRef(locationCode)) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value
        }
        val filter = StockFilter(code, location, onlyWithStock = onlyWithStock ?: true)

        return listStock.execute(actor, filter, request, origin).toResponse { it.toDto { line -> line.toDto() } }
    }

    @GetMapping("/kardex")
    @RequiresPermission("kardex.view")
    fun kardex(
        @RequestParam(required = false) productCode: String?,
        @RequestParam(required = false) moveType: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) sort: String?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> {
        val analyzed = PageRequest.analyze(page, size, sort, setOf("movedAt", "productCode", "moveType"), SortSpec("movedAt"))
        val request = when (analyzed) {
            is Either.Left -> return analyzed.error.toResponse()
            is Either.Right -> analyzed.value
        }
        val code = when (val parsed = InventoryFilters.productCode(productCode)) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value
        }
        val type = when (val parsed = InventoryFilters.moveType(moveType)) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value
        }
        val fromDate = when (val parsed = InventoryFilters.date(from, "from")) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value
        }
        val toDate = when (val parsed = InventoryFilters.date(to, "to")) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value
        }
        when (val range = InventoryFilters.range(fromDate, toDate)) {
            is Either.Left -> return range.error.toResponse()
            is Either.Right -> Unit
        }

        val filter = KardexFilter(code, type, fromDate, toDate)
        return listKardex.execute(actor, filter, request, origin).toResponse { it.toDto { line -> line.toDto() } }
    }

    /**
     * El ajuste. Responde **`201`** si se aplicó y **`202`** si quedó pendiente de
     * aprobación: son dos desenlaces distintos y el cliente tiene que poder
     * distinguirlos sin leer el cuerpo.
     */
    @PostMapping("/adjustments")
    @RequiresPermission(
        "inventory.adjust_increase",
        "inventory.adjust_theft",
        "inventory.adjust_damage",
        "inventory.adjust_shortage",
        "inventory.adjust_count_error",
    )
    fun adjust(
        @RequestBody body: AdjustmentRequestDto,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> {
        val key = when (val parsed = idempotencyKeyOf(idempotencyKey)) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value
        }
        val quantity = when (val parsed = quantityOf(body.quantity)) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value
        }
        return adjustStock.execute(
            actor,
            AdjustmentInput(
                productCode = body.productCode,
                locationCode = body.locationCode,
                quantity = quantity,
                direction = body.direction,
                reason = body.reason,
                origin = body.origin,
                note = body.note,
                approverId = body.approverId,
            ),
            key,
            origin,
        ).toMovementResponse()
    }

    @PostMapping("/locate")
    @RequiresPermission("invoice.locate")
    fun locate(
        @RequestBody body: MovementRequestDto,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = movement(locateStock, actor, body, idempotencyKey, origin)

    @PostMapping("/move")
    @RequiresPermission("inventory.move")
    fun move(
        @RequestBody body: MovementRequestDto,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = movement(moveStock, actor, body, idempotencyKey, origin)

    private fun movement(
        useCase: ApplyStockMovement,
        actor: Actor,
        body: MovementRequestDto,
        idempotencyKey: String?,
        origin: RequestOrigin,
    ): ResponseEntity<Any> {
        val key = when (val parsed = idempotencyKeyOf(idempotencyKey)) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value
        }
        val quantity = when (val parsed = quantityOf(body.quantity)) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value
        }
        val input = MovementInput(
            body.productCode,
            body.fromLocationCode,
            body.toLocationCode,
            quantity,
            body.invoiceLineId,
        )
        return useCase.execute(actor, input, key, origin).toMovementResponse()
    }
}

/** El resultado a HTTP: `201` aplicado, `202` pendiente de aprobación. */
private fun Outcome<MovementResult>.toMovementResponse(): ResponseEntity<Any> =
    when (this) {
        is Either.Left -> error.toResponse()
        is Either.Right -> ResponseEntity
            .status(if (value.isPendingApproval) HttpStatus.ACCEPTED else HttpStatus.CREATED)
            .body(value.toDto())
    }

/**
 * La clave de idempotencia es un UUID y viaja en la cabecera.
 *
 * Se valida aquí y **no** se deja al `CAST` de PostgreSQL: una clave mal formada
 * saldría como `500` por un dato del cliente (docs/api/convenciones.md §1.5).
 */
internal fun idempotencyKeyOf(raw: String?): Outcome<UUID?> {
    if (raw.isNullOrBlank()) return null.asRight()
    return runCatching { UUID.fromString(raw.trim()) }.fold(
        onSuccess = { it.asRight() },
        onFailure = {
            failure(
                FailureKind.INVALID_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                "The Idempotency-Key header is not a UUID.",
                mapOf("Idempotency-Key" to raw),
            )
        },
    )
}

/** La cantidad llega como número JSON y se convierte al tipo del dominio sin perder precisión. */
private fun quantityOf(raw: BigDecimal?): Outcome<Quantity?> {
    if (raw == null) return null.asRight()
    return Quantity.parse(raw.toPlainString()).fold(
        onSuccess = { it.asRight() },
        onFailure = {
            failure(
                FailureKind.RULE_VIOLATION, "INVALID_QUANTITY",
                it.message ?: "The quantity is not valid.",
            )
        },
    )
}
