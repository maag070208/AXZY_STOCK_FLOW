package dev.axzy.stock.mobile.feature.receiving

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.mobile.core.ui.AppCard
import dev.axzy.stock.mobile.core.ui.StatusChip

/** One invoice line in a list: product, invoiced quantity and status. */
@Composable
fun InvoiceLineRow(line: InvoiceLine, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = line.productCode.value,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${line.invoicedQuantity.units} units invoiced",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(label = line.status.name, color = line.status.chipColor())
        }
    }
}
