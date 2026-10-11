package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val viewModel = ReceivingViewModel(pendingLine(), capturedBy = 7, onCaptured = {})

        viewModel.count(units = "100", boxes = "2")

        val state = viewModel.uiState.value
        assertEquals(InvoiceLineStatus.CAPTURED, state.line.status)
        assertNull(state.error)
    }

    @Test
    fun `a count that matches the invoice reports a match as the result`() {
        val viewModel = ReceivingViewModel(pendingLine(), capturedBy = 7, onCaptured = {})

        viewModel.count(units = "100", boxes = "2")

        assertEquals(InvoiceLineStatus.CAPTURED, viewModel.uiState.value.lastCapture)
    }

    @Test
    fun `a short count is accepted as a discrepancy and reports it as the result`() {
        val viewModel = ReceivingViewModel(pendingLine(), capturedBy = 7, onCaptured = {})

        viewModel.count(units = "80", boxes = "1")

        val state = viewModel.uiState.value
        assertEquals(InvoiceLineStatus.DISCREPANCY, state.line.status)
        assertEquals(InvoiceLineStatus.DISCREPANCY, state.lastCapture)
    }

    @Test
    fun `an accepted capture is reported to the caller with the captured line`() {
        var reported: InvoiceLine? = null
        val viewModel = ReceivingViewModel(pendingLine(), capturedBy = 7, onCaptured = { reported = it })

        viewModel.count(units = "100", boxes = "2")

        assertEquals(InvoiceLineStatus.CAPTURED, reported?.status)
        assertEquals(7L, reported?.capturedBy)
    }

    @Test
    fun `editing a count clears the result of the last capture`() {
        val viewModel = ReceivingViewModel(pendingLine(), capturedBy = 7, onCaptured = {})
        viewModel.count(units = "100", boxes = "2")

        viewModel.onUnitsChanged("90")

        assertNull(viewModel.uiState.value.lastCapture)
    }

    @Test
    fun `text that is not a whole number is refused before reaching the domain`() {
        var reported = false
        val viewModel = ReceivingViewModel(pendingLine(), capturedBy = 7, onCaptured = { reported = true })

        viewModel.count(units = "ten", boxes = "2")

        val state = viewModel.uiState.value
        assertEquals(InvoiceLineStatus.PENDING, state.line.status)
        assertIs<CountError.InvalidInput>(state.error)
        assertTrue(!reported)
    }

    @Test
    fun `negative boxes are refused as input, not with an exception`() {
        val viewModel = ReceivingViewModel(pendingLine(), capturedBy = 7, onCaptured = {})

        viewModel.count(units = "100", boxes = "-1")

        val state = viewModel.uiState.value
        assertEquals(InvoiceLineStatus.PENDING, state.line.status)
        assertIs<CountError.InvalidInput>(state.error)
    }

    @Test
    fun `the domain's rejection is kept as the error and the line stays as it was`() {
        val viewModel = ReceivingViewModel(
            pendingLine(InvoiceLineStatus.CAPTURED),
            capturedBy = 7,
            onCaptured = {},
        )

        viewModel.count(units = "100", boxes = "2")

        val state = viewModel.uiState.value
        assertEquals(InvoiceLineStatus.CAPTURED, state.line.status)
        assertIs<CountError.Rejected>(state.error)
        assertNull(state.lastCapture)
    }
}
