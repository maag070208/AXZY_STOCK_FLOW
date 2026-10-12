package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.mobile.core.network.ApiFailure
import dev.axzy.stock.mobile.core.network.TestApi
import dev.axzy.stock.mobile.core.network.invoiceDetailJson
import dev.axzy.stock.mobile.core.network.invoiceLineJson
import dev.axzy.stock.mobile.core.receiving.ReceivingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReceivingListViewModelTest {

    private val api = TestApi()
    private val repository = ReceivingRepository(api.client)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Invoice 15 with one line of each status that matters to the filters. */
    private fun prepareInvoice() {
        api.answer(
            "GET", "/v1/invoices/15",
            body = invoiceDetailJson(
                15, "IN_PROGRESS", 3,
                invoiceLineJson(1, "PENDING"),
                invoiceLineJson(2, "ASSIGNED"),
                invoiceLineJson(3, "CAPTURED", physicalQuantity = "100.000", physicalBoxQuantity = "2.000"),
                invoiceLineJson(4, "DISCREPANCY", physicalQuantity = "80.000", physicalBoxQuantity = "1.000"),
                invoiceLineJson(5, "CANCELLED"),
            ),
        )
    }

    private suspend fun loadedViewModel(): ReceivingListViewModel {
        prepareInvoice()
        return ReceivingListViewModel(repository, invoiceId = 15).also { viewModel ->
            viewModel.uiState.first { it.lines.isNotEmpty() }
        }
    }

    @Test
    fun `the screen opens loading, then shows the invoice's number, its lines and the names of their products`() = runTest {
        prepareInvoice()
        val viewModel = ReceivingListViewModel(repository, invoiceId = 15)
        assertTrue(viewModel.uiState.value.isLoading)

        val state = viewModel.uiState.first { it.lines.isNotEmpty() }

        assertEquals("F-15", state.invoiceNumber)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), state.lines.map { it.id })
        assertEquals("Clamp 3", state.productNames[3])
        assertEquals(false, state.isLoading)
    }

    @Test
    fun `a line counted on another screen shows here at once, with the summary`() = runTest {
        val viewModel = loadedViewModel()
        assertEquals(2, viewModel.uiState.value.summary.pending)
        api.answer(
            "POST", "/v1/invoices/15/lines/1/capture",
            body = invoiceLineJson(1, "CAPTURED", physicalQuantity = "100.000", physicalBoxQuantity = "2.000"),
        )

        repository.capture(lineId = 1, units = 100, boxes = 2)

        val state = viewModel.uiState.first { it.summary.pending == 1 }
        assertEquals(InvoiceLineStatus.CAPTURED, state.lines.first().status)
        assertEquals(3, state.summary.counted)
    }

    @Test
    fun `the lines of an invoice opened before are never shown under this one`() = runTest {
        loadedViewModel()
        api.cutOff("GET", "/v1/invoices/16")

        val other = ReceivingListViewModel(repository, invoiceId = 16)

        val state = other.uiState.first { it.failure != null }
        assertEquals(emptyList(), state.lines)
        assertEquals("", state.invoiceNumber)
        assertEquals(ApiFailure.Unreachable, state.failure)
    }

    @Test
    fun `a filter shows only its lines and leaves the summary counting them all`() = runTest {
        val viewModel = loadedViewModel()

        viewModel.onFilterSelected(ReceivingFilter.DISCREPANCIES)

        val state = viewModel.uiState.value
        assertEquals(listOf(4L), state.visibleLines.map { it.id })
        assertEquals(4, state.summary.total)
        assertEquals(2, state.summary.pending)
    }

    @Test
    fun `choosing the filter in force removes it and every line is shown again`() = runTest {
        val viewModel = loadedViewModel()

        viewModel.onFilterSelected(ReceivingFilter.PENDING)
        viewModel.onFilterSelected(ReceivingFilter.PENDING)

        assertEquals(ReceivingFilter.ALL, viewModel.uiState.value.filter)
        assertEquals(5, viewModel.uiState.value.visibleLines.size)
    }

    @Test
    fun `each figure of the summary equals the number of lines its filter leaves`() = runTest {
        val viewModel = loadedViewModel()
        val summary = viewModel.uiState.value.summary

        fun shown(filter: ReceivingFilter): Int {
            viewModel.onFilterSelected(ReceivingFilter.ALL)
            viewModel.onFilterSelected(filter)
            return viewModel.uiState.value.visibleLines.size
        }

        assertEquals(summary.pending, shown(ReceivingFilter.PENDING))
        assertEquals(summary.counted, shown(ReceivingFilter.COUNTED))
        assertEquals(summary.discrepancies, shown(ReceivingFilter.DISCREPANCIES))
    }
}
