package dev.axzy.stock.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.axzy.stock.mobile.core.data.AppRepositories
import dev.axzy.stock.mobile.core.data.HomeCategory
import dev.axzy.stock.mobile.core.data.demo.DemoData
import dev.axzy.stock.mobile.core.nav.Navigator
import dev.axzy.stock.mobile.core.nav.Screen
import dev.axzy.stock.mobile.core.theme.AlmacenTheme
import dev.axzy.stock.mobile.core.theme.AppColors
import dev.axzy.stock.mobile.feature.auth.LoginScreen
import dev.axzy.stock.mobile.feature.home.HomeScreen
import dev.axzy.stock.mobile.feature.inventory.InventorySearchScreen
import dev.axzy.stock.mobile.feature.inventory.move.MoveStockScreen
import dev.axzy.stock.mobile.feature.invoices.InvoiceCaptureScreen
import dev.axzy.stock.mobile.feature.invoices.InvoiceDetailScreen
import dev.axzy.stock.mobile.feature.invoices.InvoicesScreen
import dev.axzy.stock.mobile.feature.labels.LabelsScreen
import dev.axzy.stock.mobile.feature.labels.PrintJobsScreen
import dev.axzy.stock.mobile.feature.locations.LocationContentScreen
import dev.axzy.stock.mobile.feature.locations.LocationsScreen
import dev.axzy.stock.mobile.feature.orders.OrderDetailScreen
import dev.axzy.stock.mobile.feature.orders.OrdersScreen

/**
 * Raíz de la app: navegación y montaje de pantallas.
 *
 * Es el ÚNICO sitio que conoce la lista de destinos. Las pantallas NO navegan:
 * reciben callbacks. Eso es lo que hace que un feature se pueda borrar entero sin
 * dejar huérfanos, que es la razón del vertical slice de §3.4.
 *
 * LOS DATOS SON DE EJEMPLO a propósito: `stock-network` lo construye otro agente en
 * paralelo y estos puertos son la frontera. Cambiar `DemoData.repositories` por las
 * implementaciones de red es el único cambio que hará falta aquí.
 */
@Composable
fun App(repositories: AppRepositories = DemoData.repositories) {
    val navigator = remember { Navigator(Screen.Login) }
    val screen by navigator.current.collectAsStateWithLifecycle()
    var usuario by remember { mutableStateOf<String?>(null) }

    AlmacenTheme {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            when (val actual = screen) {
                is Screen.Login -> LoginScreen(
                    onLoggedIn = { user ->
                        usuario = user
                        // `replace` y no `goTo`: volver al login con el botón atrás
                        // después de entrar hace que el operador crea que se le cerró
                        // la sesión.
                        navigator.replace(Screen.Home)
                    },
                )

                is Screen.Home -> HomeScreen(
                    repositories = repositories,
                    user = usuario,
                    onOpen = { categoria -> navigator.goTo(destinoDe(categoria)) },
                    onLogout = {
                        usuario = null
                        navigator.resetTo(Screen.Login)
                    },
                )

                is Screen.Invoices -> InvoicesScreen(
                    repositories = repositories,
                    onBack = navigator::back,
                    onOpenInvoice = { navigator.goTo(Screen.InvoiceDetail(it)) },
                )

                is Screen.InvoiceDetail -> InvoiceDetailScreen(
                    repositories = repositories,
                    invoiceId = actual.invoiceId,
                    onBack = navigator::back,
                    onCapture = { lineId -> navigator.goTo(Screen.InvoiceCapture(actual.invoiceId, lineId)) },
                )

                is Screen.InvoiceCapture -> InvoiceCaptureScreen(
                    repositories = repositories,
                    invoiceId = actual.invoiceId,
                    lineId = actual.lineId,
                    onBack = navigator::back,
                )

                is Screen.Orders -> OrdersScreen(
                    repositories = repositories,
                    onBack = navigator::back,
                    onOpenOrder = { navigator.goTo(Screen.OrderDetail(it)) },
                )

                is Screen.OrderDetail -> OrderDetailScreen(
                    repositories = repositories,
                    orderId = actual.orderId,
                    onBack = navigator::back,
                )

                is Screen.Locations -> LocationsScreen(
                    repositories = repositories,
                    onBack = navigator::back,
                    onOpenLocation = { navigator.goTo(Screen.LocationContent(it)) },
                )

                is Screen.LocationContent -> LocationContentScreen(
                    repositories = repositories,
                    locationId = actual.locationId,
                    onBack = navigator::back,
                )

                is Screen.InventoryMove -> MoveStockScreen(
                    repositories = repositories,
                    mode = actual.mode,
                    onBack = navigator::back,
                )

                is Screen.InventorySearch -> InventorySearchScreen(
                    repositories = repositories,
                    onBack = navigator::back,
                    onMove = { navigator.goTo(Screen.InventoryMove(it)) },
                )

                is Screen.Labels -> LabelsScreen(
                    repositories = repositories,
                    onBack = navigator::back,
                    onOpenJobs = { navigator.goTo(Screen.PrintJobs) },
                )

                is Screen.PrintJobs -> PrintJobsScreen(
                    repositories = repositories,
                    onBack = navigator::back,
                )
            }
        }
    }
}

/**
 * Traduce la categoría del menú al destino, en una sola tabla.
 *
 * Si esto viviera repartido por las pantallas, añadir una categoría obligaría a
 * tocar tres sitios y el olvido sería silencioso (la tarjeta no haría nada).
 */
private fun destinoDe(category: HomeCategory): Screen = when (category.id) {
    "invoices" -> Screen.Invoices
    "orders" -> Screen.Orders
    "locations" -> Screen.Locations
    "inventory" -> Screen.InventorySearch
    "labels" -> Screen.Labels
    else -> Screen.Home
}

/**
 * Barra superior y contenedor estándar de una pantalla.
 *
 * Existe para que ninguna pantalla monte su propio `Scaffold` con su propio
 * `TopAppBar`: con 16 pantallas serían 16 colores de barra y 16 alturas distintas.
 * El botón de volver solo aparece si hay a dónde volver.
 */
@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Volver")
                        }
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppColors.Surface,
                    titleContentColor = AppColors.TextPrimary,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        content(Modifier.fillMaxSize().padding(padding))
    }
}
