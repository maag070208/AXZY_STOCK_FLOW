package dev.axzy.stock.adapters.web.transfer

import dev.axzy.stock.adapters.web.http.FailureException
import dev.axzy.stock.adapters.web.http.RequiresPermission
import dev.axzy.stock.adapters.web.http.toDto
import dev.axzy.stock.adapters.web.http.toResponse
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.shared.Failure
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.SortSpec
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.application.transfer.CancelTransfer
import dev.axzy.stock.application.transfer.CreateTransfer
import dev.axzy.stock.application.transfer.DispatchTransfer
import dev.axzy.stock.application.transfer.GetTransfer
import dev.axzy.stock.application.transfer.InTransitReport
import dev.axzy.stock.application.transfer.ListTransfers
import dev.axzy.stock.application.transfer.NewTransfer
import dev.axzy.stock.application.transfer.NewTransferLine
import dev.axzy.stock.application.transfer.ReceiveTransfer
import dev.axzy.stock.application.transfer.TransferFilter
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.value.Quantity
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

/**
 * Traslados entre bodegas (docs/api/traslados.md).
 *
 * El informe de tránsito va **antes** que `/{id}` en el mismo controlador y con su
 * propio permiso: `transit.view` es una entrada del catálogo, y quien vigila la deuda
 * de los traslados no tiene por qué poder ver ni crear traslados.
 */
@RestController
@RequestMapping("/v1/transfers")
class TransferController(
    private val listTransfers: ListTransfers,
    private val getTransfer: GetTransfer,
    private val createTransfer: CreateTransfer,
    private val dispatchTransfer: DispatchTransfer,
    private val receiveTransfer: ReceiveTransfer,
    private val cancelTransfer: CancelTransfer,
    private val inTransitReport: InTransitReport,
) {

    @GetMapping
    @RequiresPermission("transfer.view")
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) warehouseId: Long?,
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
        val transferStatus = status?.trim()?.uppercase()?.takeUnless { it.isBlank() }
        if (transferStatus != null && transferStatus !in TRANSFER_STATUSES) {
            return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "El estado no está en la lista admitida.",
                mapOf("field" to "status", "value" to transferStatus, "allowed" to TRANSFER_STATUSES.joinToString(",")),
            ).toResponse()
        }
        val filter = TransferFilter(transferStatus, warehouseId)

        return listTransfers.execute(actor, filter, request, origin).toResponse { it.toDto { header -> header.toDto() } }
    }

    @PostMapping
    @RequiresPermission("transfer.create")
    fun create(
        @RequestBody body: NewTransferDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = when (
        val result = createTransfer.execute(
            actor,
            NewTransfer(
                fromWarehouseId = body.fromWarehouseId,
                toWarehouseId = body.toWarehouseId,
                note = body.note,
                lines = body.lines.map { line ->
                    NewTransferLine(
                        productCode = line.productCode,
                        quantity = line.quantity?.let { quantityOf(it) },
                        fromLocationId = line.fromLocationId,
                        note = line.note,
                    )
                },
            ),
            origin,
        )
    ) {
        is Either.Left -> result.error.toResponse()
        is Either.Right -> ResponseEntity.status(HttpStatus.CREATED).body(result.value.toDto())
    }

    /**
     * La mercancía en tránsito. **Va antes que `/{id}`** para que `in-transit` no se lea
     * como un identificador.
     */
    @GetMapping("/in-transit")
    @RequiresPermission("transit.view")
    fun inTransit(actor: Actor, origin: RequestOrigin): ResponseEntity<Any> =
        inTransitReport.execute(actor, origin).toResponse { rows -> rows.map { it.toDto() } }

    @GetMapping("/{id}")
    @RequiresPermission("transfer.view")
    fun detail(
        @PathVariable id: Long,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = getTransfer.execute(actor, id, origin).toResponse { it.toDto() }

    @PostMapping("/{id}/dispatch")
    @RequiresPermission("transfer.dispatch")
    fun dispatch(
        @PathVariable id: Long,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = dispatchTransfer.execute(actor, id, origin).toResponse { it.toDto() }

    @PostMapping("/{id}/receive")
    @RequiresPermission("transfer.receive")
    fun receive(
        @PathVariable id: Long,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = receiveTransfer.execute(actor, id, origin).toResponse { it.toDto() }

    @PostMapping("/{id}/cancel")
    @RequiresPermission("transfer.cancel")
    fun cancel(
        @PathVariable id: Long,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = cancelTransfer.execute(actor, id, origin).toResponse { it.toDto() }

    private companion object {
        val TRANSFER_STATUSES = listOf("DRAFT", "DISPATCHED", "IN_TRANSIT", "RECEIVED", "CANCELLED")
    }
}

/** La cantidad que llega como número o como cadena, sin perder precisión. */
private fun quantityOf(raw: BigDecimal): Quantity =
    Quantity.parse(raw.toPlainString()).getOrElse {
        throw FailureException(
            Failure(FailureKind.RULE_VIOLATION, "INVALID_QUANTITY", it.message ?: "Cantidad inválida."),
        )
    }
