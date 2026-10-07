package dev.axzy.stock.network.mapper

import dev.axzy.stock.domain.common.DomainResult
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.inventory.AdjustmentIn
import dev.axzy.stock.domain.inventory.AdjustmentOut
import dev.axzy.stock.domain.inventory.MovementType
import dev.axzy.stock.domain.inventory.MoveWithinWarehouse
import dev.axzy.stock.domain.inventory.StockMovement
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.network.dto.inventory.AdjustmentDirection
import dev.axzy.stock.network.dto.inventory.AdjustmentRequest
import dev.axzy.stock.network.dto.inventory.KardexEntryDto
import dev.axzy.stock.network.dto.inventory.KardexMoveType
import dev.axzy.stock.network.dto.inventory.MoveRequest

/**
 * Mappers de inventario: del **tipo de movimiento del contrato** al **movimiento
 * del dominio** (que declara su efecto), y de la **petición** al movimiento.
 *
 * Es la traducción que impide el defecto más grave del legacy: allí el efecto de
 * cada movimiento no estaba declarado, vivía en un `if/else` con una regla por
 * defecto —«cualquier otro tipo resta»— que hacía que ubicar restara del total
 * sin que nadie lo hubiera decidido.
 *
 * Aquí el `when` es **exhaustivo**: si el contrato añade un tipo de movimiento,
 * [KardexMoveType.toDomain] deja de compilar hasta que alguien decida su efecto.
 */

/** Traduce el tipo de movimiento del contrato al del dominio. */
fun KardexMoveType.toDomain(): MovementType = when (this) {
    KardexMoveType.INITIAL -> MovementType.INITIAL
    KardexMoveType.RECEIPT_IN -> MovementType.RECEIPT_IN
    KardexMoveType.ADJUSTMENT_IN -> MovementType.ADJUSTMENT_IN
    KardexMoveType.FULFILLMENT_OUT -> MovementType.FULFILLMENT_OUT
    KardexMoveType.ADJUSTMENT_OUT -> MovementType.ADJUSTMENT_OUT
    KardexMoveType.LOCATE -> MovementType.LOCATE
    KardexMoveType.MOVE_WITHIN_WAREHOUSE -> MovementType.MOVE_WITHIN_WAREHOUSE
    KardexMoveType.TRANSFER_OUT -> MovementType.TRANSFER_OUT
    KardexMoveType.TRANSFER_IN -> MovementType.TRANSFER_IN
    KardexMoveType.PENDING_OUT -> MovementType.PENDING_OUT
}

/** Las unidades de la entrada del kardex, como cantidad del dominio. */
val KardexEntryDto.unitsQuantity: Quantity get() = units.toQuantity()

/** La cantidad resultante tras el movimiento, como cantidad del dominio. */
val KardexEntryDto.quantityAfterQuantity: Quantity get() = quantityAfter.toQuantity()

/**
 * Convierte una petición de ajuste en el **movimiento del dominio**.
 *
 * Se necesita el [LocationCode] (no el `locationId`) porque el dominio razona
 * con códigos legibles —los mismos que están impresos en los estantes—, y el
 * móvil los tiene en su caché offline.
 *
 * Devuelve `DomainResult` y no el movimiento a secas porque el dominio **puede
 * rechazarlo**: un ajuste sin motivo o una cantidad no positiva son errores de
 * negocio, y lo correcto es que aparezcan al validar en local, antes de
 * encolar la operación, en lugar de después de un `422` del servidor.
 */
fun AdjustmentRequest.toStockMovement(location: LocationCode): DomainResult<StockMovement> {
    val quantity = try {
        quantity.toQuantity()
    } catch (t: IllegalArgumentException) {
        return DomainError.InvalidQuantity("La cantidad del ajuste no es válida: $quantity").asLeft()
    }

    return try {
        when (direction) {
            AdjustmentDirection.IN -> AdjustmentIn(
                quantity = quantity,
                location = location,
                // El dominio exige motivo en los ajustes y lo valida en el
                // constructor: aquí no se reimplementa la regla, se invoca.
                reason = reason.name,
            )

            AdjustmentDirection.OUT -> AdjustmentOut(
                quantity = quantity,
                location = location,
                reason = reason.name,
            )
        }.asRight()
    } catch (t: IllegalArgumentException) {
        // El constructor del dominio ya explicó el motivo; se conserva tal cual
        // porque es el mensaje que el operador tiene que leer.
        DomainError.InvalidQuantity(t.message ?: "El ajuste no cumple las reglas del dominio").asLeft()
    }
}

/**
 * Convierte una petición de traslado en el movimiento del dominio.
 *
 * `422 SAME_SOURCE_AND_DESTINATION` deja de ser un error que solo existe en el
 * servidor: el dominio **no puede construir** un traslado con origen igual al
 * destino, así que el móvil lo detecta antes de gastar red.
 */
fun MoveRequest.toStockMovement(
    from: LocationCode,
    to: LocationCode,
): DomainResult<StockMovement> {
    if (from == to) {
        return DomainError.SameSourceAndDestination(from.value).asLeft()
    }
    return try {
        MoveWithinWarehouse(
            quantity = quantity.toQuantity(),
            from = from,
            to = to,
        ).asRight()
    } catch (t: IllegalArgumentException) {
        DomainError.InvalidQuantity(t.message ?: "El traslado no cumple las reglas del dominio").asLeft()
    }
}

/** Utilidad de comodidad para leer el resultado del mapper sin ceremonia. */
fun DomainResult<StockMovement>.movementOrNull(): StockMovement? =
    (this as? Either.Right)?.value
