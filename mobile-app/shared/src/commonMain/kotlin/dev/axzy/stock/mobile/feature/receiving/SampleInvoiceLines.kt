package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox

/**
 * Stand-in lines until the API client loads real invoices. They only fill the list so the flow can be
 * walked on a device; nothing here calculates a balance.
 */
val SampleInvoiceLines: List<InvoiceLine> = listOf(
    InvoiceLineStatus.PENDING,
    InvoiceLineStatus.ASSIGNED,
    InvoiceLineStatus.CAPTURED,
    InvoiceLineStatus.DISCREPANCY,
    InvoiceLineStatus.APPROVED,
).mapIndexed { index, status ->
    InvoiceLine(
        id = index + 1L,
        productCode = ProductCode.of("ABRAA${index + 1}B"),
        invoicedQuantity = Quantity.of(100),
        unitsPerBox = UnitsPerBox.of(50),
        status = status,
    )
}
