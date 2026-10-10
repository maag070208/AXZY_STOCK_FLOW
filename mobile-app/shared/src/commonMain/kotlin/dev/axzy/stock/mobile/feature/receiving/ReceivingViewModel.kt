package dev.axzy.stock.mobile.feature.receiving

import androidx.lifecycle.ViewModel
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.receiving.InvoiceLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Drives the count of one invoice line. It checks what the operator typed (text that is not a
 * whole number, a negative count) and hands the numbers to the domain, which decides the outcome:
 * captured, discrepancy, or rejected.
 */
class ReceivingViewModel(
    line: InvoiceLine,
    private val capturedBy: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceivingUiState(line = line))
    val uiState: StateFlow<ReceivingUiState> = _uiState.asStateFlow()

    fun onUnitsChanged(units: String) = _uiState.update { it.copy(units = units, error = null) }

    fun onBoxesChanged(boxes: String) = _uiState.update { it.copy(boxes = boxes, error = null) }

    fun submit() {
        val state = _uiState.value
        val units = state.units.toCountOrNull()
        val boxes = state.boxes.toCountOrNull()
        if (units == null || boxes == null) {
            _uiState.update { it.copy(error = "Enter whole numbers, zero or more, for units and boxes.") }
            return
        }

        val result = captureLine(state.line, CountInput(units, boxes), capturedBy)
        _uiState.update {
            when (result) {
                is Either.Right -> it.copy(line = result.value, error = null)
                is Either.Left -> it.copy(error = result.error.message)
            }
        }
    }
}

private fun String.toCountOrNull(): Long? = trim().toLongOrNull()?.takeIf { it >= 0 }
