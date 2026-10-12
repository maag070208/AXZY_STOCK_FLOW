package dev.axzy.stock.mobile.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.axzy.stock.mobile.core.receiving.ReceivingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The start screen follows the open invoices of the repository, and asks for them again each time it opens. */
class HomeViewModel(private val repository: ReceivingRepository, userName: String) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState(userName = userName))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

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
