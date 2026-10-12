package dev.axzy.stock.mobile.feature.home

import dev.axzy.stock.mobile.core.network.ApiFailure
import dev.axzy.stock.mobile.core.network.TestApi
import dev.axzy.stock.mobile.core.network.invoiceHeaderJson
import dev.axzy.stock.mobile.core.network.invoicePageJson
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
import kotlin.test.assertNull

private const val IN_PROGRESS = "/v1/invoices?status=IN_PROGRESS&size=200"
private const val PENDING = "/v1/invoices?status=PENDING&size=200"

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

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

    @Test
    fun `the home greets the operator and adds up the open invoices`() = runTest {
        api.answer("GET", IN_PROGRESS, body = invoicePageJson(invoiceHeaderJson(15, "IN_PROGRESS", 12, 4)))
        api.answer("GET", PENDING, body = invoicePageJson(invoiceHeaderJson(16, "PENDING", 3, 3)))

        val state = HomeViewModel(repository, "Juan Pérez").uiState.first { it.invoices.value != null }

        assertEquals("Juan Pérez", state.userName)
        assertEquals(2, state.openInvoices)
        assertEquals(15, state.totalLines)
        assertEquals(7, state.blockingLines)
        assertEquals(8, state.resolvedLines)
        assertEquals(8f / 15f, state.progress)
    }

    @Test
    fun `the home lists three open invoices at most, in the order the receiving list shows them`() = runTest {
        api.answer(
            "GET", IN_PROGRESS,
            body = invoicePageJson(
                invoiceHeaderJson(1, "IN_PROGRESS", 2, 1),
                invoiceHeaderJson(2, "IN_PROGRESS", 2, 1),
            ),
        )
        api.answer(
            "GET", PENDING,
            body = invoicePageJson(invoiceHeaderJson(3, "PENDING", 2, 2), invoiceHeaderJson(4, "PENDING", 2, 2)),
        )

        val state = HomeViewModel(repository, "Juan Pérez").uiState.first { it.invoices.value != null }

        assertEquals(listOf(1L, 2L, 3L), state.nextInvoices.map { it.id })
        assertEquals(4, state.openInvoices)
    }

    @Test
    fun `with no open invoices every figure is zero and so is the progress`() = runTest {
        api.answer("GET", IN_PROGRESS, body = invoicePageJson())
        api.answer("GET", PENDING, body = invoicePageJson())

        val state = HomeViewModel(repository, "Juan Pérez").uiState.first { it.invoices.value != null }

        assertEquals(0, state.openInvoices)
        assertEquals(0f, state.progress)
        assertEquals(emptyList(), state.nextInvoices)
    }

    @Test
    fun `a first load that fails is reported, and trying again loads the invoices`() = runTest {
        api.cutOff("GET", IN_PROGRESS)
        val viewModel = HomeViewModel(repository, "Juan Pérez")
        val failed = viewModel.uiState.first { it.invoices.failure != null }
        assertEquals(ApiFailure.Unreachable, failed.invoices.failure)
        assertNull(failed.invoices.value)

        api.answer("GET", IN_PROGRESS, body = invoicePageJson(invoiceHeaderJson(15, "IN_PROGRESS", 12, 4)))
        api.answer("GET", PENDING, body = invoicePageJson())
        viewModel.refresh()

        val loaded = viewModel.uiState.first { it.invoices.value != null }
        assertEquals(1, loaded.openInvoices)
        assertNull(loaded.invoices.failure)
    }
}
