package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.mobile.core.network.ApiFailure
import dev.axzy.stock.mobile.core.receiving.ReceivingSummary

/**
 * What the lines of an invoice show: the invoice's number, its lines in the order the API gives them, how the
 * work stands, and the filter in force. The summary always counts every line; the filter only chooses the rows.
 */
data class ReceivingListUiState(
    val invoiceNumber: String = "",
    val lines: List<InvoiceLine> = emptyList(),
    /** The product's name for each line id, when the API gave one. */
    val productNames: Map<Long, String> = emptyMap(),
    val summary: ReceivingSummary = ReceivingSummary.of(emptyList()),
    val filter: ReceivingFilter = ReceivingFilter.ALL,
    val isLoading: Boolean = true,
    val failure: ApiFailure? = null,
) {
    /** The rows on screen: the lines the filter lets through. */
    val visibleLines: List<InvoiceLine> get() = lines.filter(filter::matches)
}
