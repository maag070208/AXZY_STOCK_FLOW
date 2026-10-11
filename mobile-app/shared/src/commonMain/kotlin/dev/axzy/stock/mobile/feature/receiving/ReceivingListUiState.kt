package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.receiving.InvoiceLine

/** What the receiving list shows: the invoice lines, in the order the source gives them. */
data class ReceivingListUiState(
    val lines: List<InvoiceLine> = emptyList(),
)
