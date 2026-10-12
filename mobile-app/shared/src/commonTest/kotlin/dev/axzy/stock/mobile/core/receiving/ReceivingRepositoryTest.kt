package dev.axzy.stock.mobile.core.receiving

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.value.BoxQuantity
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.mobile.core.network.ApiFailure
import dev.axzy.stock.mobile.core.network.TestApi
import dev.axzy.stock.mobile.core.network.errorJson
import dev.axzy.stock.mobile.core.network.invoiceDetailJson
import dev.axzy.stock.mobile.core.network.invoiceHeaderJson
import dev.axzy.stock.mobile.core.network.invoiceLineJson
import dev.axzy.stock.mobile.core.network.invoicePageJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

private const val IN_PROGRESS = "/v1/invoices?status=IN_PROGRESS&size=200"
private const val PENDING = "/v1/invoices?status=PENDING&size=200"

class ReceivingRepositoryTest {

    private val api = TestApi()
    private val repository = ReceivingRepository(api.client)

    private fun openInvoice15() {
        api.answerNow(
            "GET", "/v1/invoices/15",
            body = invoiceDetailJson(
                15, "IN_PROGRESS", 2,
                invoiceLineJson(88, "PENDING"),
                invoiceLineJson(89, "DISCREPANCY", physicalQuantity = "80.000", physicalBoxQuantity = "1.000"),
            ),
        )
    }

    @Test
    fun `the open invoices are the ones being counted and then the ones not started`() = runTest {
        api.answer("GET", IN_PROGRESS, body = invoicePageJson(invoiceHeaderJson(15, "IN_PROGRESS", 12, 4)))
        api.answer("GET", PENDING, body = invoicePageJson(invoiceHeaderJson(16, "PENDING", 3, 3)))

        repository.loadInvoices()

        val loaded = repository.invoices.value
        assertEquals(
            listOf(
                InvoiceHeader(15, "F-15", "2026-10-07", InvoiceStatus.IN_PROGRESS, totalLines = 12, blockingLines = 4),
                InvoiceHeader(16, "F-16", "2026-10-07", InvoiceStatus.PENDING, totalLines = 3, blockingLines = 3),
            ),
            loaded.value,
        )
        assertFalse(loaded.isLoading)
        assertNull(loaded.failure)
        assertEquals(8, loaded.value!!.first().resolvedLines)
    }

    @Test
    fun `a reload that fails keeps the invoices already on screen next to the failure`() = runTest {
        api.answer("GET", IN_PROGRESS, body = invoicePageJson(invoiceHeaderJson(15, "IN_PROGRESS", 12, 4)))
        api.answer("GET", PENDING, body = invoicePageJson())
        repository.loadInvoices()
        api.cutOff("GET", PENDING)

        repository.loadInvoices()

        val loaded = repository.invoices.value
        assertEquals(listOf(15L), loaded.value?.map { it.id })
        assertEquals(ApiFailure.Unreachable, loaded.failure)
        assertFalse(loaded.isLoading)
    }

    @Test
    fun `an invoice arrives with its lines as the domain's lines, and the names of their products apart`() = runTest {
        openInvoice15()

        repository.loadInvoice(15)

        val invoice = repository.invoice.value.value!!
        assertEquals(InvoiceHeader(15, "F-15", "2026-10-07", InvoiceStatus.IN_PROGRESS, totalLines = 2, blockingLines = 2), invoice.header)
        assertEquals(mapOf(88L to "Clamp 88", 89L to "Clamp 89"), invoice.productNames)
        val counted = invoice.lines.last()
        assertEquals(InvoiceLineStatus.DISCREPANCY, counted.status)
        assertEquals(Quantity.of(100), counted.invoicedQuantity)
        assertEquals(Quantity.of(80), counted.physicalQuantity)
        assertEquals(BoxQuantity.of(1), counted.physicalBoxQuantity)
        assertEquals(50, counted.unitsPerBox.value)
        // The difference is the domain's own figure, not a number read from the answer.
        assertEquals(Quantity.of(-20), counted.difference)
        assertEquals(counted, repository.line(89))
        assertNull(repository.line(1))
    }

    @Test
    fun `a line the domain cannot hold makes the whole answer unexpected instead of being shown rounded`() = runTest {
        api.answer(
            "GET", "/v1/invoices/15",
            body = invoiceDetailJson(15, "IN_PROGRESS", 1, invoiceLineJson(88, "PENDING", unitsPerBox = "12.500")),
        )
        api.answer(
            "GET", "/v1/invoices/16",
            body = invoiceDetailJson(16, "IN_PROGRESS", 1, invoiceLineJson(90, "A_STATUS_FROM_THE_FUTURE")),
        )

        repository.loadInvoice(15)
        assertEquals(ApiFailure.Unexpected, repository.invoice.value.failure)
        assertNull(repository.invoice.value.value)

        repository.loadInvoice(16)
        assertEquals(ApiFailure.Unexpected, repository.invoice.value.failure)
    }

    @Test
    fun `opening another invoice drops the one held before, even when the new one fails to load`() = runTest {
        openInvoice15()
        repository.loadInvoice(15)
        api.cutOff("GET", "/v1/invoices/16")

        repository.loadInvoice(16)

        assertNull(repository.invoice.value.value)
        assertNull(repository.line(88))
        assertEquals(ApiFailure.Unreachable, repository.invoice.value.failure)
    }

    @Test
    fun `a capture sends whole numbers, and the API's line takes the place of the old one with the header's count`() = runTest {
        openInvoice15()
        repository.loadInvoice(15)
        api.answer(
            "POST", "/v1/invoices/15/lines/88/capture",
            body = invoiceLineJson(88, "CAPTURED", physicalQuantity = "100.000", physicalBoxQuantity = "2.000"),
        )

        val result = repository.capture(lineId = 88, units = 100, boxes = 2)

        val captured = assertIs<Either.Right<InvoiceLine>>(result).value
        assertEquals(InvoiceLineStatus.CAPTURED, captured.status)
        assertEquals("""{"physicalQuantity":100,"physicalBoxQuantity":2}""", api.calls.last().body)
        val invoice = repository.invoice.value.value!!
        assertEquals(captured, repository.line(88))
        assertEquals(listOf(88L, 89L), invoice.lines.map { it.id })
        // One line resolved, the discrepancy still blocks: the count follows the domain's rule.
        assertEquals(1, invoice.header.blockingLines)
        assertEquals(1, invoice.header.resolvedLines)
    }

    @Test
    fun `a capture the API refuses changes nothing`() = runTest {
        openInvoice15()
        repository.loadInvoice(15)
        val before = repository.invoice.value
        api.answer("POST", "/v1/invoices/15/lines/88/capture", status = 409, body = errorJson("LINE_CHANGED"))

        val result = repository.capture(lineId = 88, units = 100, boxes = 2)

        assertEquals(Either.Left(ApiFailure.Rejected(409, "LINE_CHANGED")), result)
        assertEquals(before, repository.invoice.value)
    }
}
