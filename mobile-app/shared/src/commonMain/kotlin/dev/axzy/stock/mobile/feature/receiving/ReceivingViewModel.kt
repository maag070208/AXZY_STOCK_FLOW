package dev.axzy.stock.mobile.feature.receiving

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.mobile.core.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The longest count the pad accepts: six digits. A longer number is a slip of the finger, not a count. */
private const val MAX_COUNT_DIGITS = 6

/**
 * Drives the count of one invoice line. It checks what the operator typed (text that is not a whole number,
 * a negative count), asks the domain whether the count can be captured, and only then sends it with [capture].
 * The domain answers at once and without network; the API records the count and its answer is the line shown.
 */
class ReceivingViewModel(
    line: InvoiceLine,
    productName: String?,
    private val capturedBy: Long,
    private val capture: suspend (lineId: Long, units: Long, boxes: Long) -> ApiResult<InvoiceLine>,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceivingUiState(line = line, productName = productName))
    val uiState: StateFlow<ReceivingUiState> = _uiState.asStateFlow()

    fun onUnitsChanged(units: String) = _uiState.update { it.copy(units = units, error = null) }

    fun onBoxesChanged(boxes: String) = _uiState.update { it.copy(boxes = boxes, error = null) }

    /** Chooses the field the number pad writes to. */
    fun onFieldSelected(field: CountField) = _uiState.update { it.copy(activeField = field) }

    /** A key of the number pad: the digit goes to the end of the active field. Anything that is not a digit is ignored. */
    fun onDigit(digit: Int) {
        if (digit !in 0..9) return
        val state = _uiState.value
        when (state.activeField) {
            CountField.UNITS -> onUnitsChanged(state.units.withDigit(digit))
            CountField.BOXES -> onBoxesChanged(state.boxes.withDigit(digit))
        }
    }

    /** The delete key of the number pad: it removes the last digit of the active field. */
    fun onDelete() {
        val state = _uiState.value
        when (state.activeField) {
            CountField.UNITS -> onUnitsChanged(state.units.dropLast(1))
            CountField.BOXES -> onBoxesChanged(state.boxes.dropLast(1))
        }
    }

    fun submit() {
        val state = _uiState.value
        if (state.isSubmitting) return

        val units = state.units.toCountOrNull()
        val boxes = state.boxes.toCountOrNull()
        if (units == null || boxes == null) {
            _uiState.update { it.copy(error = CountError.InvalidInput) }
            return
        }

        // The domain's verdict comes first: a count it refuses is never sent.
        val verdict = captureLine(state.line, CountInput(units, boxes), capturedBy)
        if (verdict is Either.Left) {
            _uiState.update { it.copy(error = CountError.Rejected(verdict.error)) }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            when (val result = capture(state.line.id, units, boxes)) {
                is Either.Right -> _uiState.update { it.copy(line = result.value, isSubmitting = false) }
                is Either.Left -> _uiState.update {
                    it.copy(isSubmitting = false, error = CountError.Failed(result.error))
                }
            }
        }
    }
}

private fun String.toCountOrNull(): Long? = trim().toLongOrNull()?.takeIf { it >= 0 }

/** The text after one more key: a lone zero is replaced, and a full field stays as it is. */
private fun String.withDigit(digit: Int): String = when {
    length >= MAX_COUNT_DIGITS -> this
    this == "0" -> digit.toString()
    else -> this + digit
}
