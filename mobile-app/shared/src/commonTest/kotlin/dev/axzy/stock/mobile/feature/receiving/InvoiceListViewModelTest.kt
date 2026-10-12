package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.mobile.core.network.ApiCodes
import dev.axzy.stock.mobile.core.network.ApiFailure
import dev.axzy.stock.mobile.core.network.TestApi
import dev.axzy.stock.mobile.core.network.errorJson
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

private const val IN_PROGRESS = "/v1/invoices?status=IN_PROGRESS&size=200"
private const val PENDING = "/v1/invoices?status=PENDING&size=200"

@OptIn(ExperimentalCoroutinesApi::class)
class InvoiceListViewModelTest {

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
    fun `the section opens loading and then shows the open invoices`() = runTest {
        api.answer("GET", IN_PROGRESS, body = invoicePageJson(invoiceHeaderJson(15, "IN_PROGRESS", 12, 4)))
        api.answer("GET", PENDING, body = invoicePageJson(invoiceHeaderJson(16, "PENDING", 3, 3)))

        val viewModel = InvoiceListViewModel(repository)

        val loaded = viewModel.uiState.first { it.invoices.value != null }
        assertEquals(listOf(15L, 16L), loaded.invoices.value?.map { it.id })
    }

    @Test
    fun `an operator without the permission sees the API's refusal, by its code`() = runTest {
        api.answer("GET", IN_PROGRESS, status = 403, body = errorJson(ApiCodes.PERMISSION_DENIED))

        val failed = InvoiceListViewModel(repository).uiState.first { it.invoices.failure != null }

        assertEquals(ApiFailure.Rejected(403, ApiCodes.PERMISSION_DENIED), failed.invoices.failure)
    }
}
