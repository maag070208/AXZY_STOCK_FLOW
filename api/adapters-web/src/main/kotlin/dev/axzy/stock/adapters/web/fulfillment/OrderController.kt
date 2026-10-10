package dev.axzy.stock.adapters.web.fulfillment

import dev.axzy.stock.adapters.web.http.RequiresPermission
import dev.axzy.stock.adapters.web.http.toDto
import dev.axzy.stock.adapters.web.http.toResponse
import dev.axzy.stock.adapters.web.inventory.idempotencyKeyOf
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.fulfillment.ConfirmOrder
import dev.axzy.stock.application.fulfillment.CreateOrder
import dev.axzy.stock.application.fulfillment.ExportOrder
import dev.axzy.stock.application.fulfillment.GetOrder
import dev.axzy.stock.application.fulfillment.ListOrders
import dev.axzy.stock.application.fulfillment.NewOrder
import dev.axzy.stock.application.fulfillment.NewOrderLine
import dev.axzy.stock.application.fulfillment.OrderFilter
import dev.axzy.stock.application.fulfillment.PackageInput
import dev.axzy.stock.application.fulfillment.PackageLine
import dev.axzy.stock.application.fulfillment.RegisterShortage
import dev.axzy.stock.application.fulfillment.ShortageInput
import dev.axzy.stock.application.shared.Failure
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.SortSpec
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.adapters.web.http.FailureException
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.inventory.AdjustmentReason
import dev.axzy.stock.domain.value.Quantity
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

/**
 * Órdenes de surtido (docs/api/surtido.md).
 *
 * La creación **no toca el inventario**: el stock sale al empacar y al confirmar, que
 * son actos con responsable. Confirmar dispara la exportación al ERP, y si el ERP no
 * responde la confirmación **no se revierte**: el stock ya salió.
 */
@RestController
@RequestMapping("/v1/orders")
class OrderController(
    private val listOrders: ListOrders,
    private val getOrder: GetOrder,
    private val createOrder: CreateOrder,
    private val packageLine: PackageLine,
    private val confirmOrder: ConfirmOrder,
    private val exportOrder: ExportOrder,
    private val registerShortage: RegisterShortage,
) {

    @GetMapping
    @RequiresPermission("order.view")
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) sort: String?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> {
        val analyzed = PageRequest.analyze(page, size, sort, setOf("id"), SortSpec("id"))
        val request = when (analyzed) {
            is Either.Left -> return analyzed.error.toResponse()
            is Either.Right -> analyzed.value
        }
        val orderStatus = status?.trim()?.uppercase()?.takeUnless { it.isBlank() }
        if (orderStatus != null && orderStatus !in ORDER_STATUSES) {
            return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "El estado no está en la lista admitida.",
                mapOf("field" to "status", "value" to orderStatus, "allowed" to ORDER_STATUSES.joinToString(",")),
            ).toResponse()
        }
        val filter = OrderFilter(orderStatus, q?.trim()?.takeUnless { it.isBlank() })

        return listOrders.execute(actor, filter, request, origin).toResponse { it.toDto { header -> header.toDto() } }
    }

    @PostMapping
    @RequiresPermission("order.create")
    fun create(
        @RequestBody body: NewOrderDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = when (
        val result = createOrder.execute(
            actor,
            NewOrder(
                orderNumber = body.orderNumber,
                packagingDate = body.packagingDate,
                customerCode = body.customerCode,
                concept = body.concept,
                lines = body.lines.map { NewOrderLine(it.productCode, it.quantity?.let { value -> quantityOf(value) }) },
            ),
            origin,
        )
    ) {
        is Either.Left -> result.error.toResponse()
        is Either.Right -> ResponseEntity.status(HttpStatus.CREATED).body(result.value.toDto())
    }

    @GetMapping("/{id}")
    @RequiresPermission("order.view")
    fun detail(
        @PathVariable id: Long,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = getOrder.execute(actor, id, origin).toResponse { it.toDto() }

    @PostMapping("/{id}/lines/{lineId}/package")
    @RequiresPermission("order.package")
    fun packageLine(
        @PathVariable id: Long,
        @PathVariable lineId: Long,
        @RequestBody body: PackageDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = packageLine.execute(
        actor,
        id,
        lineId,
        PackageInput(body.quantity?.let { quantityOf(it) }, body.locationId),
        origin,
    ).toResponse { it.toDto() }

    @PostMapping("/{id}/confirm")
    @RequiresPermission("order.confirm")
    fun confirm(
        @PathVariable id: Long,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = confirmOrder.execute(actor, id, origin).toResponse { it.toDto() }

    @PostMapping("/{id}/export")
    @RequiresPermission("order.convert")
    fun export(
        @PathVariable id: Long,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = exportOrder.execute(actor, id, origin).toResponse { it.toDto() }

    /**
     * El faltante delega en la regla única de ajuste, y **su permiso es el del
     * ajuste** (`inventory.adjust_shortage`): el caso de uso delegado lo comprueba. Sin
     * él, cualquiera que pudiera empacar podría mover inventario por merma.
     */
    @PostMapping("/{id}/shortage")
    @RequiresPermission("order.package", "inventory.adjust_shortage")
    fun shortage(
        @PathVariable id: Long,
        @RequestBody body: ShortageDto,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> {
        val key = when (val parsed = idempotencyKeyOf(idempotencyKey)) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value
        }
        val reason = body.reason?.trim()?.uppercase()?.takeUnless { it.isBlank() }?.let { raw ->
            AdjustmentReason.entries.firstOrNull { it.name == raw }
                ?: throw FailureException(
                    Failure(
                        FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                        "El motivo no está en la lista admitida.",
                        mapOf(
                            "reason" to raw,
                            "allowed" to AdjustmentReason.entries.joinToString(",") { it.name },
                        ),
                    ),
                )
        }
        return registerShortage.execute(
            actor,
            id,
            ShortageInput(
                lineId = body.lineId,
                quantity = body.quantity?.let { quantityOf(it) },
                reason = reason,
                note = body.note,
            ),
            key,
            origin,
        ).toResponse { it.toDto() }
    }

    private companion object {
        val ORDER_STATUSES = listOf(
            "PENDING", "IN_PROGRESS", "PACKAGED", "PENDING_TO_OUT", "COMPLETED", "CANCELLED",
        )
    }
}

/** La cantidad que llega como número o como cadena, sin perder precisión. */
private fun quantityOf(raw: BigDecimal): Quantity =
    Quantity.parse(raw.toPlainString()).getOrElse {
        throw FailureException(
            Failure(FailureKind.RULE_VIOLATION, "INVALID_QUANTITY", it.message ?: "Cantidad inválida."),
        )
    }

