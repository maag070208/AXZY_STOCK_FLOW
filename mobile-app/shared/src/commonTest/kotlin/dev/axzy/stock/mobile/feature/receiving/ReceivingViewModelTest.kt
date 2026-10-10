package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReceivingViewModelTest {

    private fun pendingLine(status: InvoiceLineStatus = InvoiceLineStatus.PENDING) = InvoiceLine(
        id = 1,
        productCode = ProductCode.of("ABRAA1B"),
        invoicedQuantity = Quantity.of(100),
        unitsPerBox = UnitsPerBox.of(50),
        status = status,
    )

    private fun ReceivingViewModel.count(units: String, boxes: String) {
        onUnitsChanged(units)
        onBoxesChanged(boxes)
        submit()
    }

    @Test
    fun `a count equal to the invoice is captured and the error is cleared`() {
        val viewModel = ReceivingViewModel(pendingLine(), capturedBy = 7)

        viewModel.count(units = "100", boxes = "2")

        val state = viewModel.uiState.value
        assertEquals(InvoiceLineStatus.CAPTURED, state.line.status)
        assertNull(state.error)
    }

    @Test
    fun `text that is not a whole number is refused before reaching the domain`() {
        val viewModel = ReceivingViewModel(pendingLine(), capturedBy = 7)

        viewModel.count(units = "ten", boxes = "2")

        val state = viewModel.uiState.value
        assertEquals(InvoiceLineStatus.PENDING, state.line.status)
        assertNotNull(state.error)
    }

    @Test
    fun `negative boxes are refused with a message, not with an exception`() {
        val viewModel = ReceivingViewModel(pendingLine(), capturedBy = 7)

        viewModel.count(units = "100", boxes = "-1")

        val state = viewModel.uiState.value
        assertEquals(InvoiceLineStatus.PENDING, state.line.status)
        assertNotNull(state.error)
    }

    @Test
    fun `a rejection of the domain is shown and the line stays as it was`() {
        val viewModel = ReceivingViewModel(pendingLine(InvoiceLineStatus.CAPTURED), capturedBy = 7)

        viewModel.count(units = "100", boxes = "2")

        val state = viewModel.uiState.value
        assertEquals(InvoiceLineStatus.CAPTURED, state.line.status)
        assertNotNull(state.error)
    }
}
