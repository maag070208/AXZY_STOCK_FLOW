package dev.axzy.stock.mobile.feature.home

import dev.axzy.stock.mobile.core.network.Loaded
import dev.axzy.stock.mobile.core.receiving.InvoiceHeader

/** How many open invoices the home lists. The rest are one tap away, in receiving. */
private const val NEXT_INVOICES_SHOWN = 3

/**
 * What the home shows: who is signed in and how the receiving stands. Every figure is added up from the open
 * invoices the receiving list shows, so the two screens cannot disagree.
 */
data class HomeUiState(
    val userName: String = "",
    val invoices: Loaded<List<InvoiceHeader>> = Loaded(isLoading = true),
) {
    private val open: List<InvoiceHeader> get() = invoices.value.orEmpty()

    val openInvoices: Int get() = open.size
    val totalLines: Int get() = open.sumOf { it.totalLines }
    val blockingLines: Int get() = open.sumOf { it.blockingLines }
    val resolvedLines: Int get() = totalLines - blockingLines

    /** How much of the open work is resolved, from 0 to 1. */
    val progress: Float get() = if (totalLines == 0) 0f else resolvedLines.toFloat() / totalLines

    /** The first open invoices, so the operator can start one from here. */
    val nextInvoices: List<InvoiceHeader> get() = open.take(NEXT_INVOICES_SHOWN)
}
