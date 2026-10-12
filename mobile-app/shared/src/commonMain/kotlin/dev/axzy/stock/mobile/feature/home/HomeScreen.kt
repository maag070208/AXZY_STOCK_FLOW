package dev.axzy.stock.mobile.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.axzy.stock.mobile.core.network.text
import dev.axzy.stock.mobile.core.receiving.InvoiceHeader
import dev.axzy.stock.mobile.core.receiving.InvoiceRow
import dev.axzy.stock.mobile.core.theme.Brand
import dev.axzy.stock.mobile.core.theme.ITColors
import dev.axzy.stock.mobile.core.theme.ITShape
import dev.axzy.stock.mobile.core.theme.ITSpacing
import dev.axzy.stock.mobile.core.ui.ITAlert
import dev.axzy.stock.mobile.core.ui.ITBrandLogo
import dev.axzy.stock.mobile.core.ui.ITCard
import dev.axzy.stock.mobile.core.ui.ITErrorState
import dev.axzy.stock.mobile.core.ui.ITLoadingState
import dev.axzy.stock.mobile.core.ui.ITPage
import dev.axzy.stock.mobile.core.ui.ITProgress
import dev.axzy.stock.mobile.core.ui.ITStatCard
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.app_tagline
import dev.axzy.stock.mobile.generated.resources.home_greeting
import dev.axzy.stock.mobile.generated.resources.home_hero_progress
import dev.axzy.stock.mobile.generated.resources.home_modules
import dev.axzy.stock.mobile.generated.resources.home_next_empty
import dev.axzy.stock.mobile.generated.resources.home_next_title
import dev.axzy.stock.mobile.generated.resources.home_receiving_subtitle
import dev.axzy.stock.mobile.generated.resources.home_receiving_title
import dev.axzy.stock.mobile.generated.resources.home_stat_blocking
import dev.axzy.stock.mobile.generated.resources.home_stat_invoices
import dev.axzy.stock.mobile.generated.resources.home_stat_resolved
import dev.axzy.stock.mobile.generated.resources.nav_home
import org.jetbrains.compose.resources.stringResource

/** The room the loader and the failure take where the invoices will be. */
private val PLACEHOLDER_HEIGHT = 200.dp

/**
 * The start of the app: who is signed in and how the open receiving stands in the hero, its figures, the first
 * open invoices, and the module to open. Everything comes from the same invoices the receiving list shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenReceiving: () -> Unit,
    onOpenInvoice: (InvoiceHeader) -> Unit,
    onMenu: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    ITPage(title = stringResource(Res.string.nav_home), onMenu = onMenu, bottomBar = bottomBar) { padding ->
        PullToRefreshBox(
            isRefreshing = state.invoices.isLoading && state.invoices.value != null,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(ITSpacing.L),
                verticalArrangement = Arrangement.spacedBy(ITSpacing.L),
            ) {
                HeroCard(state = state)

                Row(horizontalArrangement = Arrangement.spacedBy(ITSpacing.S)) {
                    ITStatCard(
                        value = state.openInvoices,
                        label = stringResource(Res.string.home_stat_invoices),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    ITStatCard(
                        value = state.blockingLines,
                        label = stringResource(Res.string.home_stat_blocking),
                        color = ITColors.Warning,
                        modifier = Modifier.weight(1f),
                    )
                    ITStatCard(
                        value = state.resolvedLines,
                        label = stringResource(Res.string.home_stat_resolved),
                        color = ITColors.Success,
                        modifier = Modifier.weight(1f),
                    )
                }

                SectionTitle(stringResource(Res.string.home_next_title))
                NextInvoices(state = state, onOpenInvoice = onOpenInvoice, onRetry = viewModel::refresh)

                SectionTitle(stringResource(Res.string.home_modules))
                ModuleCard(
                    title = stringResource(Res.string.home_receiving_title),
                    subtitle = stringResource(Res.string.home_receiving_subtitle),
                    onClick = onOpenReceiving,
                )
            }
        }
    }
}

/** The first open invoices, or what stands in their place: the loader, the failure with its retry, or "all done". */
@Composable
private fun NextInvoices(state: HomeUiState, onOpenInvoice: (InvoiceHeader) -> Unit, onRetry: () -> Unit) {
    val invoices = state.invoices
    val failure = invoices.failure
    val placeholder = Modifier.fillMaxWidth().height(PLACEHOLDER_HEIGHT)
    when {
        invoices.value == null && failure != null ->
            ITErrorState(message = failure.text(), modifier = placeholder, onRetry = onRetry)
        invoices.value == null -> ITLoadingState(modifier = placeholder)
        state.nextInvoices.isEmpty() ->
            ITAlert(text = stringResource(Res.string.home_next_empty), color = ITColors.Success)
        else -> Column(verticalArrangement = Arrangement.spacedBy(ITSpacing.M)) {
            // A reload that failed keeps the invoices on screen: say they may be out of date.
            if (failure != null) ITAlert(text = failure.text(), color = ITColors.Warning)
            state.nextInvoices.forEach { invoice ->
                InvoiceRow(invoice = invoice, onClick = { onOpenInvoice(invoice) })
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

/** The hero: the brand, who is signed in, and how much of the open receiving is already resolved. */
@Composable
private fun HeroCard(state: HomeUiState) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ITShape.Card)
            .background(Brand.Hero, ITShape.Card)
            .padding(ITSpacing.L),
        verticalArrangement = Arrangement.spacedBy(ITSpacing.M),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ITSpacing.M)) {
            Box(
                modifier = Modifier
                    .size(width = 92.dp, height = 56.dp)
                    .clip(ITShape.Row)
                    .background(colors.onPrimary, ITShape.Row),
                contentAlignment = Alignment.Center,
            ) {
                ITBrandLogo(modifier = Modifier.width(72.dp))
            }
            Column {
                Text(
                    text = stringResource(Res.string.home_greeting, state.userName),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.app_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onPrimary.copy(alpha = 0.8f),
                )
            }
        }
        Text(
            text = stringResource(Res.string.home_hero_progress, state.resolvedLines, state.totalLines),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onPrimary,
        )
        ITProgress(progress = state.progress, onDark = true, modifier = Modifier.fillMaxWidth())
    }
}

/** The receiving module: the card that opens the list of invoice lines. */
@Composable
private fun ModuleCard(title: String, subtitle: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ITCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ITSpacing.M)) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(ITShape.Row)
                    .background(colors.primary.copy(alpha = 0.10f), ITShape.Row),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = Icons.Filled.Inventory2, contentDescription = null, tint = colors.primary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Icon(imageVector = Icons.Filled.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
}
