package dev.axzy.stock.mobile.feature.receiving

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.axzy.stock.mobile.core.receiving.awaitsCount
import dev.axzy.stock.mobile.core.receiving.chipColor
import dev.axzy.stock.mobile.core.receiving.labelRes
import dev.axzy.stock.mobile.core.theme.ITColors
import dev.axzy.stock.mobile.core.theme.ITSpacing
import dev.axzy.stock.mobile.core.ui.ITButton
import dev.axzy.stock.mobile.core.ui.ITCard
import dev.axzy.stock.mobile.core.ui.ITNumberPad
import dev.axzy.stock.mobile.core.ui.ITPage
import dev.axzy.stock.mobile.core.ui.ITInputNumber
import dev.axzy.stock.mobile.core.ui.ITAlert
import dev.axzy.stock.mobile.core.ui.ITStat
import dev.axzy.stock.mobile.core.ui.ITChip
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.count_invoiced_boxes
import dev.axzy.stock.mobile.generated.resources.count_invoiced_units
import dev.axzy.stock.mobile.generated.resources.count_per_box
import dev.axzy.stock.mobile.generated.resources.receiving_boxes_counted
import dev.axzy.stock.mobile.generated.resources.receiving_capture
import dev.axzy.stock.mobile.generated.resources.receiving_count_title
import dev.axzy.stock.mobile.generated.resources.receiving_error_invalid_transition
import dev.axzy.stock.mobile.generated.resources.receiving_units_counted
import org.jetbrains.compose.resources.stringResource

/**
 * Counts one invoice line. The top shows what was invoiced; the operator taps the field to fill and types what is
 * on the floor with the number pad; the domain decides the outcome. A line that is already counted shows what was
 * counted and what that means against the invoice (it matches, or how many units are missing or over), and offers
 * no pad: the screen does not invite an action the domain would refuse.
 */
@Composable
fun ReceivingCountScreen(viewModel: ReceivingViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val line = state.line
    val awaitsCount = line.awaitsCount
    val hasError = state.error != null
    val units = line.invoicedQuantity.units.toInt()
    val boxes = if (line.unitsPerBox.value == 0L) 0 else units / line.unitsPerBox.value.toInt()

    // While the line waits, the tiles show what is being typed; afterwards, what the domain recorded.
    val unitsShown = if (awaitsCount) state.units else line.physicalQuantity?.units?.toString().orEmpty()
    val boxesShown = if (awaitsCount) state.boxes else line.physicalBoxQuantity?.value?.toString().orEmpty()

    ITPage(title = stringResource(Res.string.receiving_count_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(ITSpacing.L),
            verticalArrangement = Arrangement.spacedBy(ITSpacing.M),
        ) {
            ITCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = line.productCode.value,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    ITChip(label = stringResource(line.status.labelRes()), color = line.status.chipColor())
                }
                state.productName?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(ITSpacing.M))
                Row(horizontalArrangement = Arrangement.spacedBy(ITSpacing.S)) {
                    ITStat(
                        value = units,
                        label = stringResource(Res.string.count_invoiced_units),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    ITStat(
                        value = boxes,
                        label = stringResource(Res.string.count_invoiced_boxes),
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f),
                    )
                    ITStat(
                        value = line.unitsPerBox.value.toInt(),
                        label = stringResource(Res.string.count_per_box),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(ITSpacing.M)) {
                ITInputNumber(
                    label = stringResource(Res.string.receiving_units_counted),
                    value = unitsShown,
                    selected = awaitsCount && state.activeField == CountField.UNITS,
                    onClick = { if (awaitsCount) viewModel.onFieldSelected(CountField.UNITS) },
                    modifier = Modifier.weight(1f),
                    isError = hasError,
                )
                ITInputNumber(
                    label = stringResource(Res.string.receiving_boxes_counted),
                    value = boxesShown,
                    selected = awaitsCount && state.activeField == CountField.BOXES,
                    onClick = { if (awaitsCount) viewModel.onFieldSelected(CountField.BOXES) },
                    modifier = Modifier.weight(1f),
                    isError = hasError,
                )
            }

            state.error?.let { countError ->
                ITAlert(text = countError.text(), color = MaterialTheme.colorScheme.error)
            }

            if (awaitsCount) {
                ITNumberPad(
                    onDigit = viewModel::onDigit,
                    onDelete = viewModel::onDelete,
                    modifier = Modifier.fillMaxWidth(),
                )
                ITButton(
                    text = stringResource(Res.string.receiving_capture),
                    onClick = viewModel::submit,
                    modifier = Modifier.fillMaxWidth(),
                    isLoading = state.isSubmitting,
                )
            } else {
                // The line is counted: say what the count means against the invoice, in the domain's own figure.
                val differenceText = line.differenceText()
                if (differenceText != null) {
                    val matches = line.difference?.isZero == true
                    ITAlert(text = differenceText, color = if (matches) ITColors.Success else ITColors.Danger)
                } else {
                    ITAlert(
                        text = stringResource(Res.string.receiving_error_invalid_transition),
                        color = ITColors.Info,
                    )
                }
            }
        }
    }
}
