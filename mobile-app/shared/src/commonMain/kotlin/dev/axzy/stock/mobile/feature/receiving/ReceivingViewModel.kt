package dev.axzy.stock.mobile.feature.receiving

import androidx.lifecycle.ViewModel
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.receiving.InvoiceLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Drives the count of one invoice line. It checks what the operator typed (text that is not a whole number,
 * a negative count) and hands the numbers to the domain, which decides the outcome. An accepted line is reported
 * through [onCaptured], so the list shows it at once.
 */
class ReceivingViewModel(
    line: InvoiceLine,
    private val capturedBy: Long,
    private val onCaptured: (InvoiceLine) -> Unit,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceivingUiState(line = line))
    val uiState: StateFlow<ReceivingUiState> = _uiState.asStateFlow()

    fun onUnitsChanged(units: String) = _uiState.update { it.copy(units = units, error = null, lastCapture = null) }

    fun onBoxesChanged(boxes: String) = _uiState.update { it.copy(boxes = boxes, error = null, lastCapture = null) }

    fun submit() {
        val state = _uiState.value
        val units = state.units.toCountOrNull()
        val boxes = state.boxes.toCountOrNull()
        if (units == null || boxes == null) {
            _uiState.update { it.copy(error = CountError.InvalidInput, lastCapture = null) }
            return
        }

        when (val result = captureLine(state.line, CountInput(units, boxes), capturedBy)) {
            is Either.Right -> {
                _uiState.update { it.copy(line = result.value, error = null, lastCapture = result.value.status) }
                onCaptured(result.value)
            }
            is Either.Left -> _uiState.update {
                it.copy(error = CountError.Rejected(result.error), lastCapture = null)
            }
        }
    }
}

private fun String.toCountOrNull(): Long? = trim().toLongOrNull()?.takeIf { it >= 0 }
