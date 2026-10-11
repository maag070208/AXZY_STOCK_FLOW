package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ReceivingListViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun line(id: Long, status: InvoiceLineStatus) = InvoiceLine(
        id = id,
        productCode = ProductCode.of("ABRAA${id}B"),
        invoicedQuantity = Quantity.of(100),
        unitsPerBox = UnitsPerBox.of(50),
        status = status,
    )

    @Test
    fun `the list shows the lines of its source and follows a change made there`() {
        val source = ReceivingLines(initial = listOf(line(1, InvoiceLineStatus.PENDING)))
        val viewModel = ReceivingListViewModel(source)
        assertEquals(InvoiceLineStatus.PENDING, viewModel.uiState.value.lines.single().status)

        source.replace(line(1, InvoiceLineStatus.CAPTURED))

        assertEquals(InvoiceLineStatus.CAPTURED, viewModel.uiState.value.lines.single().status)
    }
}
