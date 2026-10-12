package dev.axzy.stock.mobile.core.receiving

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.value.BoxQuantity
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox
import kotlin.test.Test
import kotlin.test.assertEquals

class ReceivingSummaryTest {

    private fun line(id: Long, status: InvoiceLineStatus) = InvoiceLine(
        id = id,
        productCode = ProductCode.of("ABRAA${id}B"),
        invoicedQuantity = Quantity.of(100),
        unitsPerBox = UnitsPerBox.of(50),
        status = status,
    )

    @Test
    fun `a line awaits its count exactly when the domain accepts a capture from its status`() {
        InvoiceLineStatus.entries.forEach { status ->
            val line = line(1, status)
            val result = line.capture(
                physicalQuantity = Quantity.of(100),
                physicalBoxQuantity = BoxQuantity.of(2),
                capturedBy = 7,
            )
            val domainRefusesTheStatus = result is Either.Left && result.error is DomainError.InvalidTransition

            assertEquals(!domainRefusesTheStatus, line.awaitsCount, "status $status")
        }
    }

    @Test
    fun `pending and assigned lines are pending the rest are counted and discrepancies are counted apart`() {
        val summary = ReceivingSummary.of(
            listOf(
                line(1, InvoiceLineStatus.PENDING),
                line(2, InvoiceLineStatus.ASSIGNED),
                line(3, InvoiceLineStatus.CAPTURED),
                line(4, InvoiceLineStatus.DISCREPANCY),
                line(5, InvoiceLineStatus.APPROVED),
            ),
        )

        assertEquals(5, summary.total)
        assertEquals(2, summary.pending)
        assertEquals(3, summary.counted)
        assertEquals(1, summary.discrepancies)
    }

    @Test
    fun `a cancelled line is not work and is left out of every count`() {
        val summary = ReceivingSummary.of(
            listOf(line(1, InvoiceLineStatus.PENDING), line(2, InvoiceLineStatus.CANCELLED)),
        )

        assertEquals(1, summary.total)
        assertEquals(1, summary.pending)
        assertEquals(0, summary.counted)
    }

    @Test
    fun `progress is the share of counted lines and zero when there is no work`() {
        assertEquals(0f, ReceivingSummary.of(emptyList()).progress)
        val half = ReceivingSummary.of(listOf(line(1, InvoiceLineStatus.PENDING), line(2, InvoiceLineStatus.CAPTURED)))

        assertEquals(0.5f, half.progress)
    }
}
