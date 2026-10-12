package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.mobile.core.network.Loaded
import dev.axzy.stock.mobile.core.receiving.InvoiceHeader

/** What the receiving section opens with: the invoices that still take counts. */
data class InvoiceListUiState(
    val invoices: Loaded<List<InvoiceHeader>> = Loaded(isLoading = true),
)
