package dev.axzy.stock.mobile.feature.receiving

import androidx.compose.ui.graphics.Color
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.mobile.core.theme.AppColors
import dev.axzy.stock.mobile.core.theme.Brand

/**
 * The color of each invoice line status. It only paints the state: what each status allows is
 * decided by the domain.
 */
fun InvoiceLineStatus.chipColor(): Color = when (this) {
    InvoiceLineStatus.PENDING -> AppColors.Warning
    InvoiceLineStatus.ASSIGNED -> AppColors.Info
    InvoiceLineStatus.CAPTURED -> Brand.Primary
    InvoiceLineStatus.DISCREPANCY -> AppColors.Danger
    InvoiceLineStatus.LOCATED,
    InvoiceLineStatus.APPROVED -> AppColors.Success
    InvoiceLineStatus.CANCELLED -> Brand.TextMuted
}
