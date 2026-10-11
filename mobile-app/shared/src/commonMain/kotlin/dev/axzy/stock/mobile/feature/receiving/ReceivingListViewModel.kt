package dev.axzy.stock.mobile.feature.receiving

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The receiving list follows its source: a line counted on another screen shows here at once. */
class ReceivingListViewModel(lines: ReceivingLines) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceivingListUiState())
    val uiState: StateFlow<ReceivingListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            lines.lines.collect { current -> _uiState.update { it.copy(lines = current) } }
        }
    }
}
