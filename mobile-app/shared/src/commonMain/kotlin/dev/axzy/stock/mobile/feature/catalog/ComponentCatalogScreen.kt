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
import dev.axzy.stock.mobile.core.theme.AppSpacing
import dev.axzy.stock.mobile.core.ui.AppButton
import dev.axzy.stock.mobile.core.ui.AppList
import dev.axzy.stock.mobile.core.ui.AppScaffold
import dev.axzy.stock.mobile.core.ui.AppTextField
import dev.axzy.stock.mobile.core.ui.BrandLoader
import dev.axzy.stock.mobile.core.ui.EmptyState
import dev.axzy.stock.mobile.core.ui.ErrorState
import dev.axzy.stock.mobile.core.ui.StatusChip
import dev.axzy.stock.mobile.feature.receiving.InvoiceLineRow
import dev.axzy.stock.mobile.feature.receiving.SampleInvoiceLines
import dev.axzy.stock.mobile.feature.receiving.chipColor
import dev.axzy.stock.mobile.feature.receiving.labelRes
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.catalog_error_message
import dev.axzy.stock.mobile.generated.resources.catalog_section_chips
import dev.axzy.stock.mobile.generated.resources.catalog_section_input
import dev.axzy.stock.mobile.generated.resources.catalog_section_list
import dev.axzy.stock.mobile.generated.resources.catalog_section_states
import dev.axzy.stock.mobile.generated.resources.catalog_subtitle
import dev.axzy.stock.mobile.generated.resources.nav_component_catalog
import dev.axzy.stock.mobile.generated.resources.receiving_boxes_counted
import dev.axzy.stock.mobile.generated.resources.receiving_capture
import dev.axzy.stock.mobile.generated.resources.receiving_lines_empty_message
import dev.axzy.stock.mobile.generated.resources.receiving_lines_empty_title
import dev.axzy.stock.mobile.generated.resources.receiving_units_counted
import org.jetbrains.compose.resources.stringResource

/**
 * Every reusable piece in one place, so the design is reviewed as a whole on each platform. It is a development
 * screen: the lines are the stand-in data, and the states are shown with the real components.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComponentCatalogScreen(onBack: () -> Unit) {
    var units by remember { mutableStateOf("") }

    AppScaffold(title = stringResource(Res.string.nav_component_catalog), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(AppSpacing.L),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.XL),
        ) {
            Text(
                text = stringResource(Res.string.catalog_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CatalogSection(stringResource(Res.string.catalog_section_input)) {
                AppTextField(
                    value = units,
                    onValueChange = { units = it },
                    label = stringResource(Res.string.receiving_units_counted),
                )
                AppButton(
                    text = stringResource(Res.string.receiving_capture),
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            CatalogSection(stringResource(Res.string.catalog_section_chips)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.S),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.S),
                ) {
                    InvoiceLineStatus.entries.forEach { status ->
                        StatusChip(label = stringResource(status.labelRes()), color = status.chipColor())
                    }
                }
            }

            CatalogSection(stringResource(Res.string.catalog_section_list)) {
                AppList(
                    items = SampleInvoiceLines,
                    key = { it.id },
                    emptyTitle = stringResource(Res.string.receiving_lines_empty_title),
                    modifier = Modifier.height(320.dp),
                    row = { line -> InvoiceLineRow(line = line, onClick = {}) },
                )
            }

            CatalogSection(stringResource(Res.string.catalog_section_states)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.M)) {
                    BrandLoader(modifier = Modifier.size(96.dp))
                    EmptyState(
                        title = stringResource(Res.string.receiving_lines_empty_title),
                        message = stringResource(Res.string.receiving_lines_empty_message),
                        modifier = Modifier.weight(1f),
                    )
                }
                ErrorState(message = stringResource(Res.string.catalog_error_message), onRetry = {})
            }
        }
    }
}

@Composable
private fun CatalogSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.M)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        content()
    }
}
