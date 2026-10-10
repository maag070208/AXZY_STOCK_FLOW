package dev.axzy.stock.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.axzy.stock.mobile.core.theme.StockFlowTheme

@Composable
fun App() {
    StockFlowTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Edge-to-edge on Android: the padding keeps the catalog clear of the status and navigation bars.
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                DesignCatalog()
            }
        }
    }
}
