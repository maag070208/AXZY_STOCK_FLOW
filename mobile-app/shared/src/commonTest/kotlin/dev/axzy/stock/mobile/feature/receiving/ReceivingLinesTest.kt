package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReceivingLinesTest {

    private fun line(id: Long, status: InvoiceLineStatus) = InvoiceLine(
        id = id,
        productCode = ProductCode.of("ABRAA${id}B"),
        invoicedQuantity = Quantity.of(100),
        unitsPerBox = UnitsPerBox.of(50),
        status = status,
    )

    @Test
    fun `replace changes only the line with the same id and keeps the order`() {
        val lines = ReceivingLines(initial = listOf(line(1, InvoiceLineStatus.PENDING), line(2, InvoiceLineStatus.PENDING)))

        lines.replace(line(2, InvoiceLineStatus.CAPTURED))

        assertEquals(listOf(1L, 2L), lines.lines.value.map { it.id })
        assertEquals(InvoiceLineStatus.PENDING, lines.line(1)?.status)
        assertEquals(InvoiceLineStatus.CAPTURED, lines.line(2)?.status)
    }

    @Test
    fun `an unknown id has no line`() {
        val lines = ReceivingLines(initial = listOf(line(1, InvoiceLineStatus.PENDING)))

        assertNull(lines.line(99))
    }
}
