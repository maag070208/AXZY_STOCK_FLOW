package dev.axzy.stock.mobile.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.axzy.stock.mobile.core.theme.AppShape

/**
 * The card under every row and panel. It clips the ripple to its shape before the click is set,
 * so the highlight stays inside the rounded corners.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = AppShape.Card
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .then(clickable)
            .padding(16.dp),
        content = content,
    )
}

/** The status pill. The color says the state; the label says it in words. */
@Composable
fun StatusChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = label,
        modifier = modifier
            .clip(AppShape.Pill)
            .background(color.copy(alpha = 0.12f), AppShape.Pill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}

/** The app's button. Every action goes through it, so the shape and the text style match everywhere. */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = AppShape.Control,
    ) {
        Text(text)
    }
}

/** Full-area loading state: the brand loader, centered. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BrandLoader(modifier = Modifier.size(120.dp))
    }
}

/** Nothing to show yet. The title says what is missing; the message says why or what comes next. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A failed load. The retry button is shown only when the screen can load again. */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            AppButton(text = "Try again", onClick = onRetry)
        }
    }
}

/**
 * The list every screen with rows uses. It shows exactly one of four things: the loader while the
 * first load runs, the error when the first load failed, the empty state, or the rows. The caller
 * builds each row with [row]; the list only places them and keeps their order.
 */
@Composable
fun <T> AppList(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    emptyTitle: String = "Nothing to show",
    emptyMessage: String? = null,
    row: @Composable (T) -> Unit,
) {
    when {
        items.isEmpty() && isLoading -> LoadingState(modifier = modifier)
        items.isEmpty() && error != null -> ErrorState(message = error, modifier = modifier, onRetry = onRetry)
        items.isEmpty() -> EmptyState(title = emptyTitle, message = emptyMessage, modifier = modifier)
        else -> LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = items, key = key) { row(it) }
        }
    }
}
