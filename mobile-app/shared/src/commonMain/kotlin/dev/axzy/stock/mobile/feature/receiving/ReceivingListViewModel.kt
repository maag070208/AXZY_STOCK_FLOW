package dev.axzy.stock.mobile.feature.receiving

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.axzy.stock.mobile.core.receiving.ReceivingRepository
import dev.axzy.stock.mobile.core.receiving.ReceivingSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The lines of one invoice. It follows the repository's open invoice: a line counted on the count screen shows
 * here at once, with the summary.
 */
class ReceivingListViewModel(
    private val repository: ReceivingRepository,
    private val invoiceId: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceivingListUiState())
    val uiState: StateFlow<ReceivingListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.invoice.collect { held ->
                // The repository may still hold the invoice opened before this one: its lines are not ours.
                val invoice = held.value?.takeIf { it.header.id == invoiceId }
                _uiState.update {
                    it.copy(
                        invoiceNumber = invoice?.header?.number.orEmpty(),
                        lines = invoice?.lines.orEmpty(),
                        productNames = invoice?.productNames.orEmpty(),
                        summary = ReceivingSummary.of(invoice?.lines.orEmpty()),
                        isLoading = held.isLoading,
                        failure = held.failure,
                    )
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { repository.loadInvoice(invoiceId) }
    }

    /** Chooses a filter. Choosing the one already in force removes it, so a second tap shows every line again. */
    fun onFilterSelected(filter: ReceivingFilter) = _uiState.update {
        it.copy(filter = if (it.filter == filter) ReceivingFilter.ALL else filter)
    }
}
