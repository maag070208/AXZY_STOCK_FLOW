package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.receiving.InvoiceLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The invoice lines the receiving flow works on, held in memory until the API client loads them (phase 6).
 * Screens read [lines] and change the list only through [replace], so the list and the count always agree.
 */
class ReceivingLines(initial: List<InvoiceLine> = SampleInvoiceLines) {

    private val _lines = MutableStateFlow(initial)
    val lines: StateFlow<List<InvoiceLine>> = _lines.asStateFlow()

    fun line(id: Long): InvoiceLine? = _lines.value.firstOrNull { it.id == id }

    /** Replaces the line with the same id. The other lines keep their place. */
    fun replace(line: InvoiceLine) {
        _lines.update { current -> current.map { if (it.id == line.id) line else it } }
    }
}
