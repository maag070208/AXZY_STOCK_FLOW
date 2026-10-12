package dev.axzy.stock.mobile.core.receiving

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.mobile.core.ui.ITCard
import dev.axzy.stock.mobile.core.ui.ITChip
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.receiving_units_invoiced
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One invoice line in a list. A bar on its left carries the status color, so the state reads before the words.
 * The whole card opens the count. [productName] is the name the API gives the product, when there is one.
 */
@Composable
fun InvoiceLineRow(
    line: InvoiceLine,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    productName: String? = null,
) {
    val units = line.invoicedQuantity.units
    ITCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(0.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(line.status.chipColor()))
            Row(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = line.productCode.value,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (productName != null) {
                        Text(
                            text = productName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = pluralStringResource(Res.plurals.receiving_units_invoiced, units.toInt(), units),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ITChip(label = stringResource(line.status.labelRes()), color = line.status.chipColor())
            }
        }
    }
}
