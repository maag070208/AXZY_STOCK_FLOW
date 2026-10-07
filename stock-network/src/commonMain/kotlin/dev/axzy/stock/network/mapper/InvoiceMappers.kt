package dev.axzy.stock.network.mapper

import dev.axzy.stock.domain.common.DomainResult
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.receiving.InvoiceLine as DomainInvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus as DomainLineStatus
import dev.axzy.stock.domain.value.BoxQuantity
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.network.dto.invoice.CaptureLineRequest
import dev.axzy.stock.network.dto.invoice.InvoiceLineDto
import dev.axzy.stock.network.dto.invoice.InvoiceLineStatus as ContractLineStatus

/**
 * Mappers de recepción: línea del contrato → línea del dominio, y al revés para
 * la captura.
 *
 * Es la traducción donde el legacy dejó el defecto más caro: `difference` estaba
 * declarada en el modelo, el DTO, el mapper y la respuesta, y **siempre valía
 * cero**. Aquí el dominio la **deriva** (`physicalQuantity - quantity`) y el
 * mapper comprueba que la del servidor coincide. Si un día el servidor vuelve a
 * mandar ceros, lo dice un test, no un conteo físico tres meses después.
 */

/**
 * Traduce el estado del contrato al del dominio.
 *
 * `PENDING_APPROVAL` → `DISCREPANCY`, y es una decisión, no un descuido: el
 * dominio **no tiene** ese estado porque allí «discrepancia sin aprobar» ya es
 * `DISCREPANCY`, que es lo que impide ubicar (regla B1). Los dos estados del
 * contrato describen la misma situación con dos nombres, y colapsarlos en el
 * dominio es lo que hace que la regla B1 tenga una sola implementación.
 */
fun ContractLineStatus.toDomain(): DomainLineStatus = when (this) {
    ContractLineStatus.PENDING -> DomainLineStatus.PENDING
    ContractLineStatus.CAPTURED -> DomainLineStatus.CAPTURED
    ContractLineStatus.DISCREPANCY, ContractLineStatus.PENDING_APPROVAL -> DomainLineStatus.DISCREPANCY
    ContractLineStatus.APPROVED -> DomainLineStatus.APPROVED
    ContractLineStatus.ASSIGNED -> DomainLineStatus.ASSIGNED
    ContractLineStatus.LOCATED -> DomainLineStatus.LOCATED
    ContractLineStatus.CANCELLED -> DomainLineStatus.CANCELLED
}

/** El camino de vuelta, para pintar el estado de una línea del dominio en la UI del contrato. */
fun DomainLineStatus.toContract(): ContractLineStatus = when (this) {
    DomainLineStatus.PENDING -> ContractLineStatus.PENDING
    DomainLineStatus.CAPTURED -> ContractLineStatus.CAPTURED
    DomainLineStatus.DISCREPANCY -> ContractLineStatus.DISCREPANCY
    DomainLineStatus.APPROVED -> ContractLineStatus.APPROVED
    DomainLineStatus.ASSIGNED -> ContractLineStatus.ASSIGNED
    DomainLineStatus.LOCATED -> ContractLineStatus.LOCATED
    DomainLineStatus.CANCELLED -> ContractLineStatus.CANCELLED
}

/**
 * La línea del API como línea del dominio.
 *
 * Puede fallar, y ese es el punto: el dominio exige un código de producto válido
 * y **unas unidades por caja mayores que cero** (el legacy dividía por cero y
 * producía `Array(NaN)` → `RangeError` en el móvil). Si el servidor manda 0, la
 * línea **no se convierte** y se devuelve el error; inventar un 1 «para que
 * funcione» calcularía mal todas las cajas de esa línea.
 */
fun InvoiceLineDto.toDomain(): DomainResult<DomainInvoiceLine> {
    val code: ProductCode = ProductCode.validate(productCode).getOrElse { failure ->
        return DomainError.InvalidQuantity(
            "El código de producto '$productCode' de la línea $id no es válido: " +
                (failure.message ?: "formato incorrecto")
        ).asLeft()
    }

    val unitsPerBox = unitsPerBox.toUnitsPerBoxOrNull()
        ?: return DomainError.InvalidQuantity(
            "La línea $id trae unidades por caja = $unitsPerBox. " +
                "El dominio no admite 0 porque dividir por cero fue el RangeError del móvil del legacy."
        ).asLeft()

    val physical = physicalQuantity?.toQuantity()
    val physicalBoxes = physicalBoxQuantity?.let { boxes ->
        val rounded = kotlin.math.round(boxes).toLong()
        if (rounded >= 0) BoxQuantity.of(rounded) else null
    }

    return try {
        DomainInvoiceLine(
            id = id,
            productCode = code,
            invoicedQuantity = quantity.toQuantity(),
            unitsPerBox = unitsPerBox,
            status = status.toDomain(),
            physicalQuantity = physical,
            physicalBoxQuantity = physicalBoxes,
            locatedQuantity = locatedQuantity.toQuantity(),
            capturedBy = capturedBy,
            approvedBy = approvedBy,
            adjustmentReason = adjustmentReason,
            location = null,
        ).asRight()
    } catch (t: IllegalArgumentException) {
        DomainError.InvalidQuantity(
            t.message ?: "La línea $id no cumple las reglas del dominio"
        ).asLeft()
    }
}

/**
 * La diferencia que el dominio calcula por su cuenta.
 *
 * Es la comprobación contra el `difference: 0` del legacy: el dominio no lee el
 * campo, lo recalcula.
 */
fun InvoiceLineDto.derivedDifference(): Quantity? =
    physicalQuantity?.toQuantity()?.minus(quantity.toQuantity())

/**
 * Indica si el `difference` que mandó el servidor coincide con el derivado.
 *
 * `true` también cuando la línea no se ha contado (no hay diferencia que
 * comprobar). Está aquí, y no en un test suelto, para que el móvil pueda
 * registrarlo como incidencia de datos en lugar de pintar una cifra en la que
 * no se puede confiar.
 */
fun InvoiceLineDto.hasConsistentDifference(): Boolean {
    val derived = derivedDifference() ?: return true
    val fromServer = difference ?: return false
    return (fromServer * 1000).toLong() == derived.value
}

/**
 * El cuerpo de la captura, a partir de la línea del dominio.
 *
 * Lo usa el flujo offline: se construye la petición **una vez**, con su clave de
 * idempotencia, y se encola. Reintentar con la misma clave es un no-op en el
 * servidor; generar una clave nueva en cada reintento duplicaría movimientos.
 */
fun DomainInvoiceLine.toCaptureRequest(
    physicalQuantity: Quantity,
    physicalBoxQuantity: BoxQuantity,
    idempotencyKey: String,
): CaptureLineRequest = CaptureLineRequest(
    physicalQuantity = physicalQuantity.toContractNumber(),
    physicalBoxQuantity = physicalBoxQuantity.value.toDouble(),
    idempotencyKey = idempotencyKey,
)
