package dev.axzy.stock.mobile.feature.receiving

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.mobile.core.theme.AppSpacing
import dev.axzy.stock.mobile.core.ui.AppList
import dev.axzy.stock.mobile.core.ui.AppScaffold
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.nav_receiving
import dev.axzy.stock.mobile.generated.resources.receiving_lines_empty_message
import dev.axzy.stock.mobile.generated.resources.receiving_lines_empty_title
import org.jetbrains.compose.resources.stringResource

/** The invoice lines waiting to be received. Tapping a line opens its count. */
@Composable
fun ReceivingListScreen(
    viewModel: ReceivingListViewModel,
    onOpenLine: (InvoiceLine) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    AppScaffold(title = stringResource(Res.string.nav_receiving), onBack = onBack) { padding ->
        AppList(
            items = state.lines,
            key = { it.id },
            emptyTitle = stringResource(Res.string.receiving_lines_empty_title),
            emptyMessage = stringResource(Res.string.receiving_lines_empty_message),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = AppSpacing.L, vertical = AppSpacing.L),
            row = { line -> InvoiceLineRow(line = line, onClick = { onOpenLine(line) }) },
        )
    }
}
