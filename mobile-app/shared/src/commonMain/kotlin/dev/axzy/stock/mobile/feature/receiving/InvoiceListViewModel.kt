package dev.axzy.stock.mobile.feature.receiving

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.axzy.stock.mobile.core.receiving.ReceivingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The list of open invoices follows the repository, and asks for them again each time the section opens. */
class InvoiceListViewModel(private val repository: ReceivingRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(InvoiceListUiState())
    val uiState: StateFlow<InvoiceListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.invoices.collect { invoices -> _uiState.update { it.copy(invoices = invoices) } }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { repository.loadInvoices() }
    }
}
