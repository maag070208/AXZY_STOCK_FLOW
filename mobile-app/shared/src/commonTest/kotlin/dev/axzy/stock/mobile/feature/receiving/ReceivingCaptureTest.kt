package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReceivingCaptureTest {

    /** A pending line: 100 pieces invoiced, boxes of 50. */
    private val pending = InvoiceLine(
        id = 1,
        productCode = ProductCode.of("ABRAA1B"),
        invoicedQuantity = Quantity.of(100),
        unitsPerBox = UnitsPerBox.of(50),
        status = InvoiceLineStatus.PENDING,
    )

    @Test
    fun `a count equal to the invoice is captured without a discrepancy`() {
        val result = captureLine(pending, CountInput(units = 100, boxes = 2), userId = 7)

        val line = assertIs<Either.Right<InvoiceLine>>(result).value
        assertEquals(InvoiceLineStatus.CAPTURED, line.status)
        assertEquals(7L, line.capturedBy)
    }

    @Test
    fun `a different count becomes a discrepancy, decided by the domain`() {
        val result = captureLine(pending, CountInput(units = 80, boxes = 1), userId = 7)

        val line = assertIs<Either.Right<InvoiceLine>>(result).value
        assertEquals(InvoiceLineStatus.DISCREPANCY, line.status)
    }

    @Test
    fun `a line that is already captured is rejected by the domain`() {
        val captured = pending.copy(status = InvoiceLineStatus.CAPTURED)

        val result = captureLine(captured, CountInput(units = 100, boxes = 2), userId = 7)

        assertIs<Either.Left<*>>(result)
    }
}
