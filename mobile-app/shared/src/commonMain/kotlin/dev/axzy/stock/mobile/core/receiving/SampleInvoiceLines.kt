package dev.axzy.stock.mobile.core.receiving

import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.value.BoxQuantity
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox

/**
 * Stand-in lines until the API client loads real invoices. They only fill the list so the flow can be
 * walked on a device; nothing here calculates a balance. A line that is already counted carries its count,
 * so the count screen shows the same figures its status implies.
 */
val SampleInvoiceLines: List<InvoiceLine> = listOf(
    sampleLine(1, InvoiceLineStatus.PENDING),
    sampleLine(2, InvoiceLineStatus.ASSIGNED),
    sampleLine(3, InvoiceLineStatus.CAPTURED, countedUnits = 100, countedBoxes = 2),
    sampleLine(4, InvoiceLineStatus.DISCREPANCY, countedUnits = 80, countedBoxes = 1),
    sampleLine(5, InvoiceLineStatus.APPROVED, countedUnits = 100, countedBoxes = 2),
)

private fun sampleLine(
    id: Long,
    status: InvoiceLineStatus,
    countedUnits: Long? = null,
    countedBoxes: Long? = null,
) = InvoiceLine(
    id = id,
    productCode = ProductCode.of("ABRAA${id}B"),
    invoicedQuantity = Quantity.of(100),
    unitsPerBox = UnitsPerBox.of(50),
    status = status,
    physicalQuantity = countedUnits?.let(Quantity::of),
    physicalBoxQuantity = countedBoxes?.let(BoxQuantity::of),
)
