package dev.axzy.stock.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.axzy.stock.mobile.core.nav.ITDrawer
import dev.axzy.stock.mobile.core.nav.ITTabBar
import dev.axzy.stock.mobile.core.nav.Navigator
import dev.axzy.stock.mobile.core.nav.PlatformBackHandler
import dev.axzy.stock.mobile.core.nav.Screen
import dev.axzy.stock.mobile.core.nav.ScreenViewModels
import dev.axzy.stock.mobile.core.network.ApiClient
import dev.axzy.stock.mobile.core.receiving.ReceivingRepository
import dev.axzy.stock.mobile.core.session.Session
import dev.axzy.stock.mobile.core.session.SessionUser
import dev.axzy.stock.mobile.core.ui.ITEmptyState
import dev.axzy.stock.mobile.core.ui.ITPage
import dev.axzy.stock.mobile.feature.auth.ChangePasswordScreen
import dev.axzy.stock.mobile.feature.auth.ChangePasswordViewModel
import dev.axzy.stock.mobile.feature.catalog.ComponentCatalogScreen
import dev.axzy.stock.mobile.feature.home.HomeScreen
import dev.axzy.stock.mobile.feature.home.HomeViewModel
import dev.axzy.stock.mobile.feature.receiving.InvoiceListScreen
import dev.axzy.stock.mobile.feature.receiving.InvoiceListViewModel
import dev.axzy.stock.mobile.feature.receiving.ReceivingCountScreen
import dev.axzy.stock.mobile.feature.receiving.ReceivingListScreen
import dev.axzy.stock.mobile.feature.receiving.ReceivingListViewModel
import dev.axzy.stock.mobile.feature.receiving.ReceivingViewModel
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.receiving_count_title
import dev.axzy.stock.mobile.generated.resources.receiving_line_missing
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The app behind the sign-in: the drawer, the tab bar and the route of every screen. It lives as long as the
 * session: what it holds (the back stack, what the repository loaded, every ViewModel) ends with it.
 */
@Composable
fun AppShell(user: SessionUser, api: ApiClient, session: Session, onSignOut: () -> Unit) {
    val navigator = remember { Navigator(Screen.Home) }
    val receiving = remember { ReceivingRepository(api) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun openDrawer() {
        scope.launch { drawerState.open() }
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    // The tab bar of the top-level screens. A screen opened from another (an invoice, a count) does not show it.
    val tabs: @Composable () -> Unit = {
        ITTabBar(selected = navigator.currentRoot, onSelect = navigator::openRoot)
    }

    // Back closes an open drawer first, then goes back one screen; it never leaves the app from a screen it opened.
    PlatformBackHandler(enabled = drawerState.isOpen || navigator.canGoBack) {
        if (drawerState.isOpen) closeDrawer() else navigator.back()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ITDrawer(
                selected = navigator.currentRoot,
                onSelect = { screen ->
                    navigator.openRoot(screen)
                    closeDrawer()
                },
                userName = user.name,
                onChangePassword = {
                    if (navigator.current != Screen.ChangePassword) navigator.open(Screen.ChangePassword)
                    closeDrawer()
                },
                onSignOut = onSignOut,
            )
        },
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ScreenViewModels(navigator) {
                when (val screen = navigator.current) {
                    Screen.Home -> HomeScreen(
                        viewModel = viewModel { HomeViewModel(receiving, user.name) },
                        onOpenReceiving = { navigator.openRoot(Screen.Invoices) },
                        onOpenInvoice = { invoice ->
                            // Through the section, so that back from the invoice lands on the list of invoices.
                            navigator.openRoot(Screen.Invoices)
                            navigator.open(Screen.InvoiceLines(invoice.id))
                        },
                        onMenu = ::openDrawer,
                        bottomBar = tabs,
                    )

                    Screen.Invoices -> InvoiceListScreen(
                        viewModel = viewModel { InvoiceListViewModel(receiving) },
                        onOpenInvoice = { invoice -> navigator.open(Screen.InvoiceLines(invoice.id)) },
                        onMenu = ::openDrawer,
                        bottomBar = tabs,
                    )

                    is Screen.InvoiceLines -> ReceivingListScreen(
                        viewModel = viewModel { ReceivingListViewModel(receiving, screen.invoiceId) },
                        onOpenLine = { line -> navigator.open(Screen.ReceivingCount(line.id)) },
                        onBack = navigator::back,
                    )

                    is Screen.ReceivingCount -> {
                        val line = receiving.line(screen.lineId)
                        if (line == null) {
                            ITPage(
                                title = stringResource(Res.string.receiving_count_title),
                                onBack = navigator::back,
                            ) { padding ->
                                ITEmptyState(
                                    title = stringResource(Res.string.receiving_line_missing),
                                    modifier = Modifier.fillMaxSize().padding(padding),
                                )
                            }
                        } else {
                            val viewModel = viewModel {
                                ReceivingViewModel(
                                    line = line,
                                    productName = receiving.invoice.value.value?.productNames?.get(line.id),
                                    capturedBy = user.id,
                                    capture = receiving::capture,
                                )
                            }
                            ReceivingCountScreen(viewModel = viewModel, onBack = navigator::back)
                        }
                    }

                    Screen.ComponentCatalog -> ComponentCatalogScreen(onMenu = ::openDrawer, bottomBar = tabs)

                    Screen.ChangePassword -> ChangePasswordScreen(
                        viewModel = viewModel { ChangePasswordViewModel(session, user.username) },
                        required = false,
                        onBack = navigator::back,
                        onSignOut = onSignOut,
                    )
                }
            }
        }
    }
}
