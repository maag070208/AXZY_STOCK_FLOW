package dev.axzy.stock.mobile.core.receiving

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.axzy.stock.mobile.core.theme.ITSpacing
import dev.axzy.stock.mobile.core.ui.ITCard
import dev.axzy.stock.mobile.core.ui.ITChip
import dev.axzy.stock.mobile.core.ui.ITProgress
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.invoice_progress
import org.jetbrains.compose.resources.stringResource

/**
 * One invoice in a list: its number, the day it was received, its status, and how many of its lines are resolved.
 * The whole card opens its lines.
 */
@Composable
fun InvoiceRow(invoice: InvoiceHeader, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    ITCard(modifier = modifier, onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(ITSpacing.M)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = invoice.number, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    Text(
                        text = invoice.receiptDate,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                ITChip(label = stringResource(invoice.status.labelRes()), color = invoice.status.chipColor())
            }
            ITProgress(progress = invoice.progress, modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(Res.string.invoice_progress, invoice.resolvedLines, invoice.totalLines),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
