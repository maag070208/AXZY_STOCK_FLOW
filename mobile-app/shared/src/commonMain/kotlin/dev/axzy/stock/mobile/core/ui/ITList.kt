package dev.axzy.stock.mobile.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The list every screen with rows uses. It shows exactly one of four things: the loader while the first
 * load runs, the error when the first load failed, the empty state, or the rows. The caller builds each row
 * with [row]; the list places them and keeps their order. [contentPadding] is the screen's margin.
 *
 * With [onRefresh] the list can be pulled down to load again; the spinner shows while [isLoading] is true and rows
 * are already on screen. The empty state can be pulled too, so a list that came back empty is one gesture from
 * checking again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ITList(
    items: List<T>,
    key: (T) -> Any,
    emptyTitle: String,
    modifier: Modifier = Modifier,
    emptyMessage: String? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    isLoading: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    row: @Composable (T) -> Unit,
) {
    val content: @Composable () -> Unit = {
        when {
            items.isEmpty() && isLoading -> ITLoadingState(modifier = Modifier.fillMaxSize())
            items.isEmpty() && error != null -> ITErrorState(message = error, modifier = Modifier.fillMaxSize(), onRetry = onRetry)
            items.isEmpty() -> ITEmptyState(
                title = emptyTitle,
                message = emptyMessage,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = items, key = key) { row(it) }
            }
        }
    }

    if (onRefresh == null) {
        Box(modifier = modifier) { content() }
    } else {
        PullToRefreshBox(
            isRefreshing = isLoading && items.isNotEmpty(),
            onRefresh = onRefresh,
            modifier = modifier,
            content = { content() },
        )
    }
}
