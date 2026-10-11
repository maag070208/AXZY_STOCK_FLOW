package dev.axzy.stock.mobile.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The list every screen with rows uses. It shows exactly one of four things: the loader while the first
 * load runs, the error when the first load failed, the empty state, or the rows. The caller builds each row
 * with [row]; the list places them and keeps their order. [contentPadding] is the screen's margin.
 */
@Composable
fun <T> AppList(
    items: List<T>,
    key: (T) -> Any,
    emptyTitle: String,
    modifier: Modifier = Modifier,
    emptyMessage: String? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    isLoading: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    row: @Composable (T) -> Unit,
) {
    when {
        items.isEmpty() && isLoading -> LoadingState(modifier = modifier)
        items.isEmpty() && error != null -> ErrorState(message = error, modifier = modifier, onRetry = onRetry)
        items.isEmpty() -> EmptyState(title = emptyTitle, message = emptyMessage, modifier = modifier)
        else -> LazyColumn(
            modifier = modifier,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = items, key = key) { row(it) }
        }
    }
}
