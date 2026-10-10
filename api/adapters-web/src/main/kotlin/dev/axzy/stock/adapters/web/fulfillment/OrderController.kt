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
import dev.axzy.stock.application.fulfillment.AssignOrderLine
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

/**
 * Fulfillment orders (docs/api/surtido.md).
 *
 * Creation **does not touch the inventory**: stock leaves when packing and confirming, which
 * are acts with someone responsible. Confirming triggers the export to the ERP, and if the ERP
 * does not respond, the confirmation is **not rolled back**: the stock has already left.
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
    private val assignOrderLine: AssignOrderLine,
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
                "The status is not in the list of allowed values.",
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
     * The shortage delegates to the single adjustment rule, and **its permission is that of the
     * adjustment** (`inventory.adjust_shortage`): the delegated use case checks it. Without
     * it, anyone who could pack could move inventory through shrinkage.
     */
    /**
     * Assigns the line to the person who is going to fulfill it (M04).
     *
     * `userId` is optional: without it, the caller **assigns to themselves**, which is the daily case
     * for the operator. With it, a supervisor distributes the work.
     */
    @PutMapping("/{id}/lines/{lineId}/assign")
    @RequiresPermission("order.assign")
    fun assign(
        @PathVariable id: Long,
        @PathVariable lineId: Long,
        @RequestBody body: AssignLineDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = assignOrderLine
        .execute(actor, id, lineId, body.userId, body.locationId, origin)
        .toResponse { it.toDto() }

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
                        "The reason is not in the list of allowed values.",
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

/** The quantity that arrives as a number or as a string, without losing precision. */
private fun quantityOf(raw: BigDecimal): Quantity =
    Quantity.parse(raw.toPlainString()).getOrElse {
        throw FailureException(
            Failure(FailureKind.RULE_VIOLATION, "INVALID_QUANTITY", it.message ?: "Invalid quantity."),
        )
    }

/** Without `userId`, the assignment is to the caller. */
data class AssignLineDto(val userId: Long? = null, val locationId: Long? = null)
