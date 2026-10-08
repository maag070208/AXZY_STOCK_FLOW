package dev.axzy.stock.backend.receiving

import java.sql.ResultSet

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MAPPERS DE RECEPCIÓN — de fila de base de datos a DTO
 *
 * Ver `inventory/InventoryMappers.kt` para el razonamiento completo. Lo esencial:
 *
 * **Un mapper lee la columna por su nombre en una CADENA.** `getString("product_code")`
 * compila igual si el SQL dice `AS productCode`, y devuelve `null` en silencio. **Un
 * mapper mal escrito no rompe: miente.** Por eso el contrato se verifica con `curl`
 * contra la respuesta real, además de con tests.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Convierte una fila de `invoice_lines` en su DTO.
 *
 * **Aquí conviven las dos trampas de la lectura de un `ResultSet`**, y las dos están
 * resueltas a propósito:
 *
 *  1. **`getBigDecimal(...)?.toPlainString()` para los campos anulables** — `getBigDecimal`
 *     devuelve `null` para un NULL, así que el `?.` es correcto. Pero para un campo
 *     **NO** anulable, llamar sin `?.` reventaría con NPE en vez de dar un error legible:
 *     si el esquema dice que la columna no puede ser nula, la excepción es la respuesta
 *     correcta —significa que la base está mal—, pero conviene saberlo.
 *
 *  2. **`getLong(...).takeIf { !wasNull() }` para las claves foráneas anulables** —
 *     `getLong` devuelve **0** para un NULL, así que sin el `wasNull()` una línea sin
 *     capturar aparecería como del **usuario 0, que no existe**. `wasNull()` es la única
 *     forma de distinguir «cero» de «ninguno», y **hay que llamarlo inmediatamente
 *     después del `get`**: cualquier otra lectura lo sobrescribe.
 *
 * `difference` viene de una **columna generada** de la base y no se calcula aquí. Es
 * deliberado: en el legacy se declaraba en cuatro sitios y **siempre valía 0**.
 */
internal fun ResultSet.toInvoiceLineDto(): InvoiceLineDto = InvoiceLineDto(
    id = getLong("id"),
    productId = getLong("product_id"),
    productCode = getString("product_code"),
    productName = getString("product_name"),
    container = getString("container"),
    quantity = getBigDecimal("quantity").toPlainString(),
    boxQuantity = getBigDecimal("box_quantity").toPlainString(),
    unitsPerBox = getBigDecimal("units_per_box")?.toPlainString(),
    physicalQuantity = getBigDecimal("physical_quantity")?.toPlainString(),
    physicalBoxQuantity = getBigDecimal("physical_box_quantity")?.toPlainString(),
    locatedQuantity = getBigDecimal("located_quantity").toPlainString(),
    // `difference` viene de la BASE, de una columna generada.
    // No se calcula aquí, y es deliberado: en el legacy se
    // declaraba en cuatro sitios y siempre valía 0.
    difference = getBigDecimal("difference")?.toPlainString(),
    status = getString("status"),
    capturedBy = getLong("captured_by").takeIf { !wasNull() },
    capturedByName = getString("captured_by_name"),
    capturedAt = getTimestamp("captured_at")?.toInstant()?.toString(),
    approvedBy = getLong("approved_by").takeIf { !wasNull() },
    approvedByName = getString("approved_by_name"),
    approvedAt = getTimestamp("approved_at")?.toInstant()?.toString(),
    adjustmentReason = getString("adjustment_reason"),
    locationDisplayName = getString("hall")?.let {
    displayNameFor(it, getString("code"), getString("position"), getString("kind"))
    },
)

/**
 * Convierte una fila del listado de `pending-approval` en su DTO.
 *
 * `totalDifference` es un **valor absoluto** (`SUM(ABS(difference))`) y por eso va
 * positivo aunque la discrepancia sea un faltante. **No confundir con el `difference` de
 * la línea**, que sí lleva signo (`physical_quantity - quantity`) y es negativo cuando
 * falta mercancía. Son dos números distintos para el mismo hecho, y el contrato lo
 * documenta para que nadie los compare.
 */
internal fun ResultSet.toPendingApprovalDto(): PendingApprovalDto = PendingApprovalDto(
    invoiceId = getLong("id"),
    invoiceNumber = getString("invoice_number"),
    receiptDate = getDate("receipt_date").toLocalDate().toString(),
    status = getString("status"),
    pendingLines = getInt("pending_lines"),
    totalDifference = getBigDecimal("total_difference").toPlainString(),
)

internal fun displayNameFor(hall: String, code: String, position: String, kind: String): String =
    when (kind) {
        "RECEIVING" -> "RECEPCION"
        "UNASSIGNED" -> "SIN-UBICAR"
        else -> "$hall-$code-$position"
}
