package dev.axzy.stock.mobile.feature.receiving

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.axzy.stock.mobile.core.network.text
import dev.axzy.stock.mobile.core.receiving.InvoiceHeader
import dev.axzy.stock.mobile.core.receiving.InvoiceRow
import dev.axzy.stock.mobile.core.theme.ITSpacing
import dev.axzy.stock.mobile.core.ui.ITList
import dev.axzy.stock.mobile.core.ui.ITPage
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.invoices_empty_message
import dev.axzy.stock.mobile.generated.resources.invoices_empty_title
import dev.axzy.stock.mobile.generated.resources.nav_receiving
import org.jetbrains.compose.resources.stringResource

/** The invoices that still take counts. Tapping one opens its lines. */
@Composable
fun InvoiceListScreen(
    viewModel: InvoiceListViewModel,
    onOpenInvoice: (InvoiceHeader) -> Unit,
    onMenu: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val invoices = state.invoices

    ITPage(title = stringResource(Res.string.nav_receiving), onMenu = onMenu, bottomBar = bottomBar) { padding ->
        ITList(
            items = invoices.value.orEmpty(),
            key = { it.id },
            emptyTitle = stringResource(Res.string.invoices_empty_title),
            emptyMessage = stringResource(Res.string.invoices_empty_message),
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = ITSpacing.L),
            contentPadding = PaddingValues(vertical = ITSpacing.L),
            isLoading = invoices.isLoading,
            error = invoices.failure?.text(),
            onRetry = viewModel::refresh,
            onRefresh = viewModel::refresh,
            row = { invoice -> InvoiceRow(invoice = invoice, onClick = { onOpenInvoice(invoice) }) },
        )
    }
}
