package dev.axzy.stock.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.mobile.core.nav.Navigator
import dev.axzy.stock.mobile.core.nav.PlatformBackHandler
import dev.axzy.stock.mobile.core.nav.Screen
import dev.axzy.stock.mobile.core.theme.StockFlowTheme
import dev.axzy.stock.mobile.core.ui.AppScaffold
import dev.axzy.stock.mobile.core.ui.EmptyState
import dev.axzy.stock.mobile.feature.catalog.ComponentCatalogScreen
import dev.axzy.stock.mobile.feature.home.HomeScreen
import dev.axzy.stock.mobile.feature.receiving.ReceivingCountScreen
import dev.axzy.stock.mobile.feature.receiving.ReceivingLines
import dev.axzy.stock.mobile.feature.receiving.ReceivingListScreen
import dev.axzy.stock.mobile.feature.receiving.ReceivingListViewModel
import dev.axzy.stock.mobile.feature.receiving.ReceivingViewModel
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.receiving_count_title
import dev.axzy.stock.mobile.generated.resources.receiving_line_missing
import org.jetbrains.compose.resources.stringResource

/** Until sign-in exists, every capture is attributed to this operator. */
private const val LOCAL_OPERATOR_ID = 1L

@Composable
fun App() {
    StockFlowTheme {
        val navigator = remember { Navigator(Screen.Home) }
        val receivingLines = remember { ReceivingLines() }

        // The system back gesture pops one screen, and never leaves the app from a screen the app opened.
        PlatformBackHandler(enabled = navigator.canGoBack) { navigator.back() }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val screen = navigator.current) {
                Screen.Home -> HomeScreen(
                    onOpenReceiving = { navigator.open(Screen.ReceivingList) },
                    onOpenComponentCatalog = { navigator.open(Screen.ComponentCatalog) },
                )

                Screen.ReceivingList -> {
                    val viewModel = viewModel { ReceivingListViewModel(receivingLines) }
                    ReceivingListScreen(
                        viewModel = viewModel,
                        onOpenLine = { line: InvoiceLine -> navigator.open(Screen.ReceivingCount(line.id)) },
                        onBack = navigator::back,
                    )
                }

                is Screen.ReceivingCount -> {
                    val line = receivingLines.line(screen.lineId)
                    if (line == null) {
                        AppScaffold(
                            title = stringResource(Res.string.receiving_count_title),
                            onBack = navigator::back,
                        ) { padding ->
                            EmptyState(
                                title = stringResource(Res.string.receiving_line_missing),
                                modifier = Modifier.fillMaxSize().padding(padding),
                            )
                        }
                    } else {
                        val viewModel = viewModel(key = "count-${line.id}") {
                            ReceivingViewModel(
                                line = line,
                                capturedBy = LOCAL_OPERATOR_ID,
                                onCaptured = receivingLines::replace,
                            )
                        }
                        ReceivingCountScreen(viewModel = viewModel, onBack = navigator::back)
                    }
                }

                Screen.ComponentCatalog -> ComponentCatalogScreen(onBack = navigator::back)
            }
        }
    }
}
