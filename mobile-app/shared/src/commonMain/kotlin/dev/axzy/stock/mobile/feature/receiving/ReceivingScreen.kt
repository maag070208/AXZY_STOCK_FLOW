package dev.axzy.stock.mobile.feature.receiving

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.axzy.stock.mobile.core.ui.AppButton
import dev.axzy.stock.mobile.core.ui.AppTextField

/** Counts one invoice line. The operator types the units and the boxes; the domain decides the outcome. */
@Composable
fun ReceivingScreen(viewModel: ReceivingViewModel) {
    val state by viewModel.uiState.collectAsState()
    val line = state.line

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = line.productCode.value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Invoiced: ${line.invoicedQuantity.units} units, boxes of ${line.unitsPerBox.value}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Status: ${line.status.name}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AppTextField(
            value = state.units,
            onValueChange = viewModel::onUnitsChanged,
            label = "Units counted",
            isError = state.error != null,
        )
        AppTextField(
            value = state.boxes,
            onValueChange = viewModel::onBoxesChanged,
            label = "Boxes counted",
            isError = state.error != null,
        )

        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        AppButton(text = "Capture count", onClick = viewModel::submit, modifier = Modifier.fillMaxWidth())
    }
}
