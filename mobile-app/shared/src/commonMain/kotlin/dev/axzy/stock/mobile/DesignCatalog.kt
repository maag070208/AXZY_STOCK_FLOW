package dev.axzy.stock.mobile

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
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox
import dev.axzy.stock.mobile.core.ui.AppButton
import dev.axzy.stock.mobile.core.ui.AppList
import dev.axzy.stock.mobile.core.ui.AppTextField
import dev.axzy.stock.mobile.core.ui.BrandLoader
import dev.axzy.stock.mobile.core.ui.BrandLogo
import dev.axzy.stock.mobile.core.ui.EmptyState
import dev.axzy.stock.mobile.core.ui.ErrorState
import dev.axzy.stock.mobile.core.ui.StatusChip
import dev.axzy.stock.mobile.feature.receiving.InvoiceLineRow
import dev.axzy.stock.mobile.feature.receiving.chipColor

/**
 * The reusable pieces in one place, so the design is reviewed as a whole on each platform. It is a
 * development screen: the app shows it until sign-in and the real lists exist. The lines below are
 * sample data for the catalog only; the real lines come from the API.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesignCatalog() {
    var units by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BrandLogo(modifier = Modifier.size(56.dp))
            Column {
                Text(
                    text = "StockFlow",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Design catalog: the pieces every screen reuses.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        CatalogSection("Button and input") {
            AppTextField(value = units, onValueChange = { units = it }, label = "Units counted")
            AppButton(text = "Capture count", onClick = {}, modifier = Modifier.fillMaxWidth())
        }

        CatalogSection("Status chips") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InvoiceLineStatus.entries.forEach { StatusChip(label = it.name, color = it.chipColor()) }
            }
        }

        CatalogSection("List of rows") {
            AppList(
                items = sampleLines,
                key = { it.id },
                modifier = Modifier.height(320.dp),
                row = { InvoiceLineRow(line = it) },
            )
        }

        CatalogSection("Loader, empty and error") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandLoader(modifier = Modifier.size(96.dp))
                EmptyState(
                    title = "No invoice lines",
                    message = "Lines appear when an invoice is loaded.",
                    modifier = Modifier.weight(1f),
                )
            }
            ErrorState(message = "The API did not answer.", onRetry = {})
        }
    }
}

@Composable
private fun CatalogSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        content()
    }
}

private val sampleLines: List<InvoiceLine> = listOf(
    InvoiceLineStatus.PENDING,
    InvoiceLineStatus.ASSIGNED,
    InvoiceLineStatus.CAPTURED,
    InvoiceLineStatus.DISCREPANCY,
    InvoiceLineStatus.LOCATED,
    InvoiceLineStatus.APPROVED,
    InvoiceLineStatus.CANCELLED,
).mapIndexed { index, status ->
    InvoiceLine(
        id = index + 1L,
        productCode = ProductCode.of("ABRAA${index + 1}B"),
        invoicedQuantity = Quantity.of(100),
        unitsPerBox = UnitsPerBox.of(50),
        status = status,
    )
}
