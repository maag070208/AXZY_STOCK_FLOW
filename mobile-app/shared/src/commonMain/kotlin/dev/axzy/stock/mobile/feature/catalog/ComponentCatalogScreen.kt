package dev.axzy.stock.mobile.feature.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.mobile.core.theme.ITColors
import dev.axzy.stock.mobile.core.theme.ITSpacing
import dev.axzy.stock.mobile.core.ui.ITButton
import dev.axzy.stock.mobile.core.ui.ITList
import dev.axzy.stock.mobile.core.ui.ITNumberPad
import dev.axzy.stock.mobile.core.ui.ITProgress
import dev.axzy.stock.mobile.core.ui.ITPage
import dev.axzy.stock.mobile.core.ui.ITInput
import dev.axzy.stock.mobile.core.ui.ITInputNumber
import dev.axzy.stock.mobile.core.ui.ITLoader
import dev.axzy.stock.mobile.core.ui.ITEmptyState
import dev.axzy.stock.mobile.core.ui.ITErrorState
import dev.axzy.stock.mobile.core.ui.ITAlert
import dev.axzy.stock.mobile.core.ui.ITStatCard
import dev.axzy.stock.mobile.core.ui.ITChip
import dev.axzy.stock.mobile.core.receiving.InvoiceLineRow
import dev.axzy.stock.mobile.core.receiving.SampleInvoiceLines
import dev.axzy.stock.mobile.core.receiving.chipColor
import dev.axzy.stock.mobile.core.receiving.labelRes
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.catalog_error_message
import dev.axzy.stock.mobile.generated.resources.catalog_section_chips
import dev.axzy.stock.mobile.generated.resources.catalog_section_feedback
import dev.axzy.stock.mobile.generated.resources.catalog_section_input
import dev.axzy.stock.mobile.generated.resources.catalog_section_keypad
import dev.axzy.stock.mobile.generated.resources.catalog_section_list
import dev.axzy.stock.mobile.generated.resources.catalog_section_states
import dev.axzy.stock.mobile.generated.resources.catalog_subtitle
import dev.axzy.stock.mobile.generated.resources.nav_component_catalog
import dev.axzy.stock.mobile.generated.resources.receiving_capture
import dev.axzy.stock.mobile.generated.resources.receiving_capture_matches
import dev.axzy.stock.mobile.generated.resources.receiving_lines_empty_message
import dev.axzy.stock.mobile.generated.resources.receiving_lines_empty_title
import dev.axzy.stock.mobile.generated.resources.receiving_units_counted
import dev.axzy.stock.mobile.generated.resources.summary_counted
import dev.axzy.stock.mobile.generated.resources.summary_discrepancies
import org.jetbrains.compose.resources.stringResource

/**
 * Every reusable piece in one place, so the design is reviewed as a whole on each platform. It is a development
 * screen: the lines are the stand-in data, and each state is shown with the real components.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComponentCatalogScreen(onMenu: () -> Unit, bottomBar: @Composable () -> Unit) {
    var units by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }

    ITPage(title = stringResource(Res.string.nav_component_catalog), onMenu = onMenu, bottomBar = bottomBar) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(ITSpacing.L),
            verticalArrangement = Arrangement.spacedBy(ITSpacing.XL),
        ) {
            Text(
                text = stringResource(Res.string.catalog_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CatalogSection(stringResource(Res.string.catalog_section_input)) {
                ITInput(
                    value = units,
                    onValueChange = { units = it },
                    label = stringResource(Res.string.receiving_units_counted),
                )
                ITButton(
                    text = stringResource(Res.string.receiving_capture),
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            CatalogSection(stringResource(Res.string.catalog_section_keypad)) {
                ITInputNumber(
                    label = stringResource(Res.string.receiving_units_counted),
                    value = typed,
                    selected = true,
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                )
                ITNumberPad(
                    onDigit = { digit -> if (typed.length < 6) typed += digit },
                    onDelete = { typed = typed.dropLast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            CatalogSection(stringResource(Res.string.catalog_section_chips)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ITSpacing.S),
                    verticalArrangement = Arrangement.spacedBy(ITSpacing.S),
                ) {
                    InvoiceLineStatus.entries.forEach { status ->
                        ITChip(label = stringResource(status.labelRes()), color = status.chipColor())
                    }
                }
            }

            CatalogSection(stringResource(Res.string.catalog_section_feedback)) {
                Row(horizontalArrangement = Arrangement.spacedBy(ITSpacing.S)) {
                    ITStatCard(
                        value = 12,
                        label = stringResource(Res.string.summary_counted),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    ITStatCard(
                        value = 3,
                        label = stringResource(Res.string.summary_discrepancies),
                        color = ITColors.Danger,
                        modifier = Modifier.weight(1f),
                    )
                }
                ITProgress(progress = 0.6f, modifier = Modifier.fillMaxWidth())
                ITAlert(
                    text = stringResource(Res.string.receiving_capture_matches),
                    color = ITColors.Success,
                )
                ITAlert(
                    text = stringResource(Res.string.catalog_error_message),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            CatalogSection(stringResource(Res.string.catalog_section_list)) {
                ITList(
                    items = SampleInvoiceLines,
                    key = { it.id },
                    emptyTitle = stringResource(Res.string.receiving_lines_empty_title),
                    modifier = Modifier.height(340.dp),
                    row = { line -> InvoiceLineRow(line = line, onClick = {}) },
                )
            }

            CatalogSection(stringResource(Res.string.catalog_section_states)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ITSpacing.M),
                ) {
                    ITLoader(modifier = Modifier.size(96.dp))
                    ITEmptyState(
                        title = stringResource(Res.string.receiving_lines_empty_title),
                        message = stringResource(Res.string.receiving_lines_empty_message),
                        modifier = Modifier.weight(1f),
                    )
                }
                ITErrorState(message = stringResource(Res.string.catalog_error_message), onRetry = {})
            }
        }
    }
}

@Composable
private fun CatalogSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ITSpacing.M)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        content()
    }
}
