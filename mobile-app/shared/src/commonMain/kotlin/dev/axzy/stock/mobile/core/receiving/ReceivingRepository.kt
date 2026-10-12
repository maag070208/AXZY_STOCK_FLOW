package dev.axzy.stock.mobile.core.receiving

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.mobile.core.network.ApiClient
import dev.axzy.stock.mobile.core.network.ApiFailure
import dev.axzy.stock.mobile.core.network.ApiResult
import dev.axzy.stock.mobile.core.network.Loaded
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The receiving work as the API has it: the invoices that still take counts and the lines of the one that is
 * open. Screens read [invoices] and [invoice] and change them only through this class, so the home, the lists and
 * the count always show the same numbers.
 */
class ReceivingRepository(private val api: ApiClient) {

    private val _invoices = MutableStateFlow(Loaded<List<InvoiceHeader>>())
    val invoices: StateFlow<Loaded<List<InvoiceHeader>>> = _invoices.asStateFlow()

    private val _invoice = MutableStateFlow(Loaded<InvoiceDetail>())
    val invoice: StateFlow<Loaded<InvoiceDetail>> = _invoice.asStateFlow()

    /** Loads the invoices that still take counts. A list already on screen stays there while this runs. */
    suspend fun loadInvoices() {
        _invoices.update { it.copy(isLoading = true, failure = null) }
        _invoices.value = when (val result = openInvoices()) {
            is Either.Right -> Loaded(value = result.value)
            is Either.Left -> _invoices.value.copy(isLoading = false, failure = result.error)
        }
    }

    /** Loads the invoice with its lines. Another invoice held from before is dropped first, never shown in its place. */
    suspend fun loadInvoice(id: Long) {
        _invoice.update {
            if (it.value?.header?.id == id) it.copy(isLoading = true, failure = null) else Loaded(isLoading = true)
        }
        val result = api.get<InvoiceDetailDto>("$INVOICES/$id").strictly { it.toDetail() }
        _invoice.value = when (result) {
            is Either.Right -> Loaded(value = result.value)
            is Either.Left -> _invoice.value.copy(isLoading = false, failure = result.error)
        }
    }

    /** A line of the invoice that is open. */
    fun line(lineId: Long): InvoiceLine? = _invoice.value.value?.lines?.firstOrNull { it.id == lineId }

    /**
     * Sends a count to the API. The line that comes back is the API's, with the status it decided, and it takes
     * the place of the old one in the open invoice.
     */
    suspend fun capture(lineId: Long, units: Long, boxes: Long): ApiResult<InvoiceLine> {
        val invoiceId = _invoice.value.value?.header?.id ?: return Either.Left(ApiFailure.Unexpected)
        return api.post<CaptureRequest, InvoiceLineDto>(
            path = "$INVOICES/$invoiceId/lines/$lineId/capture",
            body = CaptureRequest(physicalQuantity = units, physicalBoxQuantity = boxes),
        ).strictly { it.toLine() }.also { result ->
            if (result is Either.Right) {
                _invoice.update { held -> held.copy(value = held.value?.withLine(result.value)) }
            }
        }
    }

    /**
     * The invoices not counted yet and the ones being counted: the API filters by one status per request. Each
     * request asks for the largest page the API serves; a warehouse with more open invoices than that would see
     * only the first ones.
     */
    private suspend fun openInvoices(): ApiResult<List<InvoiceHeader>> {
        val inProgress = when (val result = invoicesWith(InvoiceStatus.IN_PROGRESS)) {
            is Either.Left -> return result
            is Either.Right -> result.value
        }
        return when (val result = invoicesWith(InvoiceStatus.PENDING)) {
            is Either.Left -> result
            is Either.Right -> Either.Right(inProgress + result.value)
        }
    }

    private suspend fun invoicesWith(status: InvoiceStatus): ApiResult<List<InvoiceHeader>> =
        api.get<InvoicePageDto>("$INVOICES?status=${status.name}&size=$MAX_PAGE_SIZE")
            .strictly { page -> page.items.map { it.toHeader() } }

    private companion object {
        const val INVOICES = "/v1/invoices"
        const val MAX_PAGE_SIZE = 200
    }
}

/**
 * Maps an answer into the app's model; a value the domain refuses makes the whole answer unexpected.
 * Written with `when` and not with the domain's `flatMap`: that one is inline and compiled for a newer JVM
 * than the Android target of the app.
 */
private inline fun <T, R> ApiResult<T>.strictly(transform: (T) -> R): ApiResult<R> = when (this) {
    is Either.Left -> this
    is Either.Right -> try {
        Either.Right(transform(value))
    } catch (_: IllegalArgumentException) {
        Either.Left(ApiFailure.Unexpected)
    }
}
