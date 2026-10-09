package dev.axzy.stock.backend.application.transfer

import dev.axzy.stock.backend.error.ConflictException
import dev.axzy.stock.backend.error.NotFoundException
import dev.axzy.stock.backend.error.UnprocessableException
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.transfer.Transfer
import dev.axzy.stock.domain.transfer.TransferLine
import dev.axzy.stock.domain.transfer.TransferStatus
import dev.axzy.stock.domain.value.Quantity
import java.math.BigDecimal

/**
 * Casos de uso de traslados (contrato §7).
 *
 * **La invariante:** `Σ stock_by_location + Σ stock_in_transit` se conserva en cada transición.
 * Despachar saca de la ubicación y deja en tránsito; recibir y cancelar cierran el tránsito.
 * Cada caso de uso hace **todo su movimiento en una sola transacción** ([TransferRepository.inTransaction]):
 * si las dos mitades no van juntas, el inventario se descuadra entre dos reportes.
 *
 * Las reglas de estado las decide el dominio ([Transfer]); este caso de uso solo las invoca y
 * ordena los efectos.
 */
class TransferUseCases(private val repository: TransferRepository) {

    fun byId(id: Long): TransferDetail? = repository.find(id)

    fun list(filter: TransferFilter, offset: Int, limit: Int): List<TransferHeader> =
        repository.list(filter, offset, limit)

    fun count(filter: TransferFilter): Long = repository.count(filter)

    fun inTransit(): List<InTransitRow> = repository.inTransit()

    /** Crea un traslado en `DRAFT`. No mueve inventario: es una intención. */
    fun create(input: NewTransfer, userId: Long?): TransferDetail {
        val id = repository.inTransaction {
            val lines = input.lines.map { resolveLine(it) }

            // Las reglas del dominio antes de tocar la base: dan un 422 que señala el campo.
            Transfer.validate(input.fromWarehouseId, input.toWarehouseId, lines)
                .errorOrNull()?.let { throw it.toUnprocessable() }

            val route = TransferRoute(input.fromWarehouseId, input.toWarehouseId)
            val code = nextCode()
            val draftId = insertDraft(code, route, input.note?.trim()?.takeIf { it.isNotEmpty() }, userId, lines)

            audit(draftId, userId, "transfer.create", previous = null, next = TransferStatus.DRAFT)
            draftId
        }
        return repository.find(id)!!
    }

    fun dispatch(id: Long, userId: Long?): TransferDetail {
        repository.inTransaction {
            val current = status(id) ?: throw notFound(id)
            val transfer = aggregate(id, current, lines(id))
            transfer.dispatch(userId ?: 0L).errorOrNull()?.let { throw it.toUnprocessable() }

            val route = route(id)
            for (line in lines(id)) moveOut(id, route, line, userId)

            audit(id, userId, "transfer.dispatch", previous = current, next = TransferStatus.DISPATCHED)
            markDispatched(id, userId)
        }
        return repository.find(id)!!
    }

    fun receive(id: Long, userId: Long?): TransferDetail {
        repository.inTransaction {
            val current = status(id) ?: throw notFound(id)
            val transfer = aggregate(id, current, lines(id))
            transfer.receive(userId ?: 0L).errorOrNull()?.let { throw it.toUnprocessable() }

            val route = route(id)
            // La mercancía llega a la ubicación de recepción de la bodega de destino, de donde
            // se ubica después con el flujo de recepción de siempre.
            val destination = receivingLocation(route.toWarehouseId)
                ?: throw UnprocessableException(
                    code = "NO_RECEIVING_LOCATION",
                    message = "La bodega de destino no tiene ubicación de recepción.",
                    details = mapOf("toWarehouseId" to route.toWarehouseId.toString()),
                )

            // Primero se suma en destino, después se cierra el tránsito: al revés, un fallo entre
            // las dos dejaría la mercancía fuera de las dos sumas.
            val open = openTransit(id)
            for (transit in open) land(transit, id, route.toWarehouseId, destination, userId)

            // Si no había tránsito abierto y el traslado tiene líneas, no hay nada que recibir.
            if (open.isEmpty() && lines(id).isNotEmpty()) {
                throw ConflictException(
                    code = "NOTHING_IN_TRANSIT",
                    message = "El traslado $id no tiene mercancía en tránsito que recibir.",
                )
            }

            audit(id, userId, "transfer.receive", previous = current, next = TransferStatus.RECEIVED)
            markReceived(id, userId)
        }
        return repository.find(id)!!
    }

    /**
     * Cancela el traslado. Desde `DRAFT` no hay nada que deshacer. Un despachado devuelve su
     * mercancía al origen en la misma transacción, para no dejarla en un limbo que ningún reporte
     * explica.
     */
    fun cancel(id: Long, userId: Long?): TransferDetail {
        repository.inTransaction {
            val current = status(id) ?: throw notFound(id)
            val transfer = aggregate(id, current, emptyList())
            transfer.cancel().errorOrNull()?.let { throw it.toUnprocessable() }

            val route = route(id)
            for (transit in openTransit(id)) {
                // Vuelve al origen de la línea. El total no cambia: sale del tránsito y entra en
                // la ubicación.
                land(transit, id, route.fromWarehouseId, transit.fromLocationId, userId)
            }

            audit(id, userId, "transfer.cancel", previous = current, next = TransferStatus.CANCELLED)
            markCancelled(id)
        }
        return repository.find(id)!!
    }

    // ── Piezas dentro de la transacción ───────────────────────────────────────

    /** Las operaciones de [TransferWork] se llaman dentro de un bloque; estas las reexponen. */
    private fun TransferWork.resolveLine(input: NewTransferLine): TransferLine {
        val product = activeProduct(input.productCode.trim())
            ?: throw UnprocessableException(
                code = "PRODUCT_NOT_FOUND",
                message = "El producto '${input.productCode}' no existe o está inactivo.",
                details = mapOf("productCode" to input.productCode),
            )

        val parsed = runCatching { BigDecimal(input.quantity.trim()) }.getOrElse {
            throw UnprocessableException(
                code = "INVALID_QUANTITY",
                message = "La cantidad '${input.quantity}' no es un número.",
                details = mapOf("quantity" to input.quantity),
            )
        }
        if (parsed <= BigDecimal.ZERO) {
            throw UnprocessableException(
                code = "INVALID_QUANTITY",
                message = "La cantidad del traslado debe ser positiva. Se recibió '${input.quantity}'.",
                details = mapOf("quantity" to input.quantity),
            )
        }

        return TransferLine(
            id = 0,
            productId = product.id,
            productCode = input.productCode.trim(),
            productName = product.name,
            quantity = Quantity.ofScaled(parsed.movePointRight(3).toLong()),
            fromLocationId = input.fromLocationId,
            note = input.note?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun TransferWork.aggregate(id: Long, status: TransferStatus, lines: List<TransferLine>) = Transfer(
        id = id,
        code = "",
        fromWarehouseId = 0,
        toWarehouseId = 0,
        status = status,
        lines = lines,
    )

    private fun notFound(id: Long) = NotFoundException("El traslado $id no existe.", "TRANSFER_NOT_FOUND")
}

/** Traduce un fallo del dominio al `422` del contrato. El código viaja tal cual. */
private fun DomainError.toUnprocessable() = UnprocessableException(code = code, message = message, details = emptyMap())
