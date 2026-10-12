package dev.axzy.stock.mobile.feature.receiving

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.mobile.core.network.text
import dev.axzy.stock.mobile.core.receiving.InvoiceLineRow
import dev.axzy.stock.mobile.core.theme.ITColors
import dev.axzy.stock.mobile.core.theme.ITSpacing
import dev.axzy.stock.mobile.core.ui.ITList
import dev.axzy.stock.mobile.core.ui.ITPage
import dev.axzy.stock.mobile.core.ui.ITStatCard
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.nav_receiving
import dev.axzy.stock.mobile.generated.resources.receiving_filter_empty
import dev.axzy.stock.mobile.generated.resources.receiving_lines_empty_message
import dev.axzy.stock.mobile.generated.resources.receiving_lines_empty_title
import dev.axzy.stock.mobile.generated.resources.summary_counted
import dev.axzy.stock.mobile.generated.resources.summary_discrepancies
import dev.axzy.stock.mobile.generated.resources.summary_pending
import org.jetbrains.compose.resources.stringResource

/**
 * The lines of one invoice. The figures at the top say how the work stands and are also the filters: tapping one
 * shows only its lines, and tapping it again shows them all. Tapping a line opens its count.
 */
@Composable
fun ReceivingListScreen(
    viewModel: ReceivingListViewModel,
    onOpenLine: (InvoiceLine) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = MaterialTheme.colorScheme
    val filtered = state.filter != ReceivingFilter.ALL

    // Until the invoice arrives the bar reads "Recepción"; then, the invoice's own number.
    val title = state.invoiceNumber.ifEmpty { stringResource(Res.string.nav_receiving) }

    ITPage(title = title, onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = ITSpacing.L, vertical = ITSpacing.M),
            verticalArrangement = Arrangement.spacedBy(ITSpacing.M),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(ITSpacing.S)) {
                ITStatCard(
                    value = state.summary.pending,
                    label = stringResource(Res.string.summary_pending),
                    color = ITColors.Warning,
                    modifier = Modifier.weight(1f),
                    selected = state.filter == ReceivingFilter.PENDING,
                    onClick = { viewModel.onFilterSelected(ReceivingFilter.PENDING) },
                )
                ITStatCard(
                    value = state.summary.counted,
                    label = stringResource(Res.string.summary_counted),
                    color = colors.primary,
                    modifier = Modifier.weight(1f),
                    selected = state.filter == ReceivingFilter.COUNTED,
                    onClick = { viewModel.onFilterSelected(ReceivingFilter.COUNTED) },
                )
                ITStatCard(
                    value = state.summary.discrepancies,
                    label = stringResource(Res.string.summary_discrepancies),
                    color = ITColors.Danger,
                    modifier = Modifier.weight(1f),
                    selected = state.filter == ReceivingFilter.DISCREPANCIES,
                    onClick = { viewModel.onFilterSelected(ReceivingFilter.DISCREPANCIES) },
                )
            }

            ITList(
                items = state.visibleLines,
                key = { it.id },
                // With a filter on, an empty list means the filter matched nothing, not that there is no invoice.
                emptyTitle = stringResource(
                    if (filtered) Res.string.receiving_filter_empty else Res.string.receiving_lines_empty_title,
                ),
                emptyMessage = if (filtered) null else stringResource(Res.string.receiving_lines_empty_message),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = ITSpacing.XL),
                isLoading = state.isLoading,
                error = state.failure?.text(),
                onRetry = viewModel::refresh,
                onRefresh = viewModel::refresh,
                row = { line ->
                    InvoiceLineRow(
                        line = line,
                        onClick = { onOpenLine(line) },
                        productName = state.productNames[line.id],
                    )
                },
            )
        }
    }
}
