package dev.axzy.stock.mobile.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.axzy.stock.mobile.core.theme.ITShape

/**
 * The card under every row and panel. It always fills the width its parent gives, and clips to its shape
 * before the click is set, so the ripple stays inside the rounded corners. [contentPadding] is zero for a
 * card that paints its own edges (a row with a status bar). A [selected] card takes the brand border: it is the
 * choice in force.
 */
@Composable
fun ITCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = ITShape.Card
    val colors = MaterialTheme.colorScheme
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .then(clickable)
            .padding(contentPadding),
        content = content,
    )
}
