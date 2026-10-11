package dev.axzy.stock.mobile.feature.receiving

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.mobile.core.theme.AppColors
import dev.axzy.stock.mobile.core.theme.AppSpacing
import dev.axzy.stock.mobile.core.ui.AppButton
import dev.axzy.stock.mobile.core.ui.AppCard
import dev.axzy.stock.mobile.core.ui.AppScaffold
import dev.axzy.stock.mobile.core.ui.AppTextField
import dev.axzy.stock.mobile.core.ui.StatusChip
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.receiving_boxes_counted
import dev.axzy.stock.mobile.generated.resources.receiving_capture
import dev.axzy.stock.mobile.generated.resources.receiving_count_title
import dev.axzy.stock.mobile.generated.resources.receiving_invoiced_summary
import dev.axzy.stock.mobile.generated.resources.receiving_units_counted
import org.jetbrains.compose.resources.stringResource

/** Counts one invoice line. The operator types the units and the boxes; the domain decides the outcome. */
@Composable
fun ReceivingCountScreen(viewModel: ReceivingViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val line = state.line
    val hasError = state.error != null

    AppScaffold(title = stringResource(Res.string.receiving_count_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(AppSpacing.L),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.L),
        ) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = line.productCode.value,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        Res.string.receiving_invoiced_summary,
                        line.invoicedQuantity.units,
                        line.unitsPerBox.value,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(AppSpacing.S))
                StatusChip(label = stringResource(line.status.labelRes()), color = line.status.chipColor())
            }

            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.M)) {
                AppTextField(
                    value = state.units,
                    onValueChange = viewModel::onUnitsChanged,
                    label = stringResource(Res.string.receiving_units_counted),
                    isError = hasError,
                    modifier = Modifier.weight(1f),
                )
                AppTextField(
                    value = state.boxes,
                    onValueChange = viewModel::onBoxesChanged,
                    label = stringResource(Res.string.receiving_boxes_counted),
                    isError = hasError,
                    modifier = Modifier.weight(1f),
                )
            }

            state.error?.let { countError ->
                Text(
                    text = countError.text(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.lastCapture?.captureResultRes()?.let { result ->
                val color = if (state.lastCapture == InvoiceLineStatus.DISCREPANCY) AppColors.Danger else AppColors.Success
                Text(
                    text = stringResource(result),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                )
            }

            AppButton(
                text = stringResource(Res.string.receiving_capture),
                onClick = viewModel::submit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
