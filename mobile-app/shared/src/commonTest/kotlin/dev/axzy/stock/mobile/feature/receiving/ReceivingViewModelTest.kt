package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.value.BoxQuantity
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox
import dev.axzy.stock.mobile.core.network.ApiFailure
import dev.axzy.stock.mobile.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReceivingViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun line(status: InvoiceLineStatus = InvoiceLineStatus.PENDING) = InvoiceLine(
        id = 1,
        productCode = ProductCode.of("ABRAA1B"),
        invoicedQuantity = Quantity.of(100),
        unitsPerBox = UnitsPerBox.of(50),
        status = status,
    )

    /** The line as the API would answer after a count: with its own status and with who it says captured it. */
    private fun answered(status: InvoiceLineStatus, units: Long, boxes: Long) = line(status).copy(
        physicalQuantity = Quantity.of(units),
        physicalBoxQuantity = BoxQuantity.of(boxes),
        capturedBy = 99,
    )

    /** What was sent to the API, as (line, units, boxes). */
    private val sent = mutableListOf<Triple<Long, Long, Long>>()

    private fun viewModel(
        line: InvoiceLine = line(),
        answer: suspend () -> ApiResult<InvoiceLine> = { Either.Left(ApiFailure.Unexpected) },
    ) = ReceivingViewModel(line = line, productName = "Clamp", capturedBy = 7) { lineId, units, boxes ->
        sent += Triple(lineId, units, boxes)
        answer()
    }

    private fun ReceivingViewModel.count(units: String, boxes: String) {
        onUnitsChanged(units)
        onBoxesChanged(boxes)
        submit()
    }

    @Test
    fun `a count the domain accepts is sent and the line shown is the one the API answered with`() {
        val fromApi = answered(InvoiceLineStatus.CAPTURED, units = 100, boxes = 2)
        val viewModel = viewModel { Either.Right(fromApi) }

        viewModel.count(units = "100", boxes = "2")

        val state = viewModel.uiState.value
        assertEquals(listOf(Triple(1L, 100L, 2L)), sent)
        assertEquals(fromApi, state.line)
        assertEquals(true, state.line.difference?.isZero)
        assertNull(state.error)
        assertFalse(state.isSubmitting)
        assertEquals("Clamp", state.productName)
    }

    @Test
    fun `a short count is sent too and shows the API's discrepancy with the units missing`() {
        val viewModel = viewModel { Either.Right(answered(InvoiceLineStatus.DISCREPANCY, units = 80, boxes = 1)) }

        viewModel.count(units = "80", boxes = "1")

        val state = viewModel.uiState.value
        assertEquals(InvoiceLineStatus.DISCREPANCY, state.line.status)
        assertEquals(-20L, state.line.difference?.units)
    }

    @Test
    fun `text that is not a whole number is refused before anything is sent`() {
        val viewModel = viewModel()

        viewModel.count(units = "abc", boxes = "2")

        assertEquals(CountError.InvalidInput, viewModel.uiState.value.error)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `negative boxes are refused as input not with an exception`() {
        val viewModel = viewModel()

        viewModel.count(units = "100", boxes = "-1")

        assertEquals(CountError.InvalidInput, viewModel.uiState.value.error)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a count the domain refuses is never sent and the line stays as it was`() {
        val viewModel = viewModel()

        viewModel.count(units = "100", boxes = "3")

        val state = viewModel.uiState.value
        val error = assertIs<CountError.Rejected>(state.error)
        assertIs<DomainError.InconsistentBoxCount>(error.error)
        assertEquals(InvoiceLineStatus.PENDING, state.line.status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a line that no longer waits for its count is refused by the domain without a request`() {
        val viewModel = viewModel(line = line(InvoiceLineStatus.CAPTURED))

        viewModel.count(units = "100", boxes = "2")

        val error = assertIs<CountError.Rejected>(viewModel.uiState.value.error)
        assertIs<DomainError.InvalidTransition>(error.error)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a count the API does not record is reported and the line stays as it was`() {
        val refusal = ApiFailure.Rejected(409, "LINE_CHANGED")
        val viewModel = viewModel { Either.Left(refusal) }

        viewModel.count(units = "100", boxes = "2")

        val state = viewModel.uiState.value
        assertEquals(CountError.Failed(refusal), state.error)
        assertEquals(InvoiceLineStatus.PENDING, state.line.status)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `while a count is on its way to the API a second tap sends nothing`() {
        val pending = CompletableDeferred<ApiResult<InvoiceLine>>()
        val viewModel = viewModel { pending.await() }

        viewModel.count(units = "100", boxes = "2")
        viewModel.submit()

        assertTrue(viewModel.uiState.value.isSubmitting)
        assertEquals(1, sent.size)

        pending.complete(Either.Right(answered(InvoiceLineStatus.CAPTURED, units = 100, boxes = 2)))

        assertFalse(viewModel.uiState.value.isSubmitting)
        assertEquals(InvoiceLineStatus.CAPTURED, viewModel.uiState.value.line.status)
    }

    @Test
    fun `the number pad writes to the units first and to the boxes once that field is selected`() {
        val viewModel = viewModel()

        viewModel.onDigit(8)
        viewModel.onDigit(0)
        viewModel.onFieldSelected(CountField.BOXES)
        viewModel.onDigit(1)

        val state = viewModel.uiState.value
        assertEquals("80", state.units)
        assertEquals("1", state.boxes)
        assertEquals(CountField.BOXES, state.activeField)
    }

    @Test
    fun `a count typed on the number pad is sent like any other`() {
        val viewModel = viewModel { Either.Right(answered(InvoiceLineStatus.CAPTURED, units = 100, boxes = 2)) }

        listOf(1, 0, 0).forEach(viewModel::onDigit)
        viewModel.onFieldSelected(CountField.BOXES)
        viewModel.onDigit(2)
        viewModel.submit()

        assertEquals(listOf(Triple(1L, 100L, 2L)), sent)
        assertEquals(InvoiceLineStatus.CAPTURED, viewModel.uiState.value.line.status)
    }

    @Test
    fun `delete removes the last digit of the active field and does nothing on an empty one`() {
        val viewModel = viewModel()
        viewModel.onDigit(1)
        viewModel.onDigit(2)

        viewModel.onDelete()
        assertEquals("1", viewModel.uiState.value.units)

        viewModel.onDelete()
        viewModel.onDelete()
        assertEquals("", viewModel.uiState.value.units)
    }

    @Test
    fun `a lone zero is replaced by the next digit`() {
        val viewModel = viewModel()

        viewModel.onDigit(0)
        viewModel.onDigit(5)

        assertEquals("5", viewModel.uiState.value.units)
    }

    @Test
    fun `a seventh digit is ignored and so is a key that is not a digit`() {
        val viewModel = viewModel()

        repeat(7) { viewModel.onDigit(9) }
        viewModel.onDigit(12)
        viewModel.onDigit(-1)

        assertEquals("999999", viewModel.uiState.value.units)
    }
}
