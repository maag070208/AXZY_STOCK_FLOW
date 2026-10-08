package dev.axzy.stock.mobile.feature.inventory.move

import app.cash.turbine.test
import dev.axzy.stock.domain.scanner.ScanInput
import dev.axzy.stock.domain.scanner.ScanRect
import dev.axzy.stock.domain.scanner.ScanState
import dev.axzy.stock.domain.scanner.ScanWindow
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.mobile.core.data.CatalogRepository
import dev.axzy.stock.mobile.core.data.LocationView
import dev.axzy.stock.mobile.core.data.ProductView
import dev.axzy.stock.mobile.core.data.StockLevelView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Tests del ViewModel MVI.
 *
 * SE PRUEBA EL VIEWMODEL Y NO LA PANTALLA, y es lo que pide la Fase 6: probar la
 * máquina de estados cuesta milisegundos en JVM, mientras que un test de UI
 * necesitaría emulador y tardaría minutos. Por eso las máquinas de estado están en
 * `stock-domain` y el reducer es puro.
 *
 * `Dispatchers.setMain` es obligatorio: `viewModelScope` usa el dispatcher principal
 * y sin sustituirlo el test no corre de forma determinista.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveStockViewModelTest : StringSpec({

    val abrazadera = ProductCode.of("ABRAA1B")
    val producto = ProductView(
        id = 1,
        code = abrazadera,
        name = "ABRAZADERA DE ARRANQUE 1-5/8\"",
        description = null,
        unitMeasure = "PZA",
        totalStock = 5193.0,
    )

    /** Repositorio de mentira: el test no debe depender de la demo ni de la red. */
    val catalogo = object : CatalogRepository {
        override suspend fun searchProducts(query: String) = listOf(producto)
        override suspend fun productByCode(code: ProductCode) = if (code == abrazadera) producto else null
        override suspend fun productById(id: Long) = if (id == 1L) producto else null
        override suspend fun locations(): List<LocationView> = emptyList()
        override suspend fun locationByCode(code: dev.axzy.stock.domain.value.LocationCode) = null
        override suspend fun locationContent(locationId: Long): List<StockLevelView> = emptyList()
    }

    fun centro(window: ScanWindow = ScanWindow.DEFAULT) = ScanRect(
        left = window.window.centerX - 0.002,
        top = window.window.centerY - 0.002,
        width = 0.004,
        height = 0.004,
    )

    "el estado inicial está en Idle y no se puede confirmar" {
        val vm = MoveStockViewModel(catalogo, listOf(producto))
        vm.state.value.scan.shouldBeInstanceOf<ScanState.Idle>()
        vm.state.value.canConfirm shouldBe false
        vm.state.value.mode shouldBe MoveMode.LOCATE
    }

    "el flujo completo deja el estado listo para confirmar" {
        val vm = MoveStockViewModel(catalogo, listOf(producto))

        vm.onIntent(MoveStockIntent.Scanned(ScanInput("ABRAA1B", centro())))
        vm.onIntent(MoveStockIntent.UnitsTyped("80"))
        vm.onIntent(MoveStockIntent.Scanned(ScanInput("04BCA", centro())))

        val listo = vm.state.value.scan.shouldBeInstanceOf<ScanState.ReadyToConfirm>()
        listo.units shouldBe 80L
        vm.state.value.canConfirm shouldBe true
    }

    "confirmar emite el aviso de un solo uso y no duplica el envío" {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val vm = MoveStockViewModel(catalogo, listOf(producto))

                vm.onIntent(MoveStockIntent.Scanned(ScanInput("ABRAA1B", centro())))
                vm.onIntent(MoveStockIntent.UnitsTyped("80"))
                vm.onIntent(MoveStockIntent.Scanned(ScanInput("04BCA", centro())))

                vm.events.test {
                    vm.onIntent(MoveStockIntent.Confirmed)
                    // El reducer marca `submitting` de inmediato, ANTES del efecto:
                    // eso es lo que deshabilita el botón y evita la doble pulsación.
                    vm.state.value.submitting shouldBe true

                    val aviso = awaitItem()
                    aviso shouldContain "80 u."
                    aviso shouldContain "ABRAA1B"
                    aviso shouldContain "04BCA"

                    cancelAndIgnoreRemainingEvents()
                }

                // El estado no se queda colgado en `submitting`: si se quedara, el
                // botón no volvería a habilitarse nunca.
                vm.state.value.submitting shouldBe false
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    "un código desconocido no avanza el flujo y explica por qué" {
        // `NOEXISTE` cumple el alfabeto del catálogo (A-Z), así que un resolutor
        // permisivo lo aceptaría y el operador capturaría un movimiento de un
        // producto que no existe. Aquí se comprueba que se rechaza.
        val vm = MoveStockViewModel(catalogo, listOf(producto))

        vm.onIntent(MoveStockIntent.Scanned(ScanInput("NOEXISTE", centro())))

        vm.state.value.scan.shouldBeInstanceOf<ScanState.Idle>()
        vm.state.value.rejection shouldBe "'NOEXISTE' no es un código de producto conocido"
    }

    "confirmar sin flujo completo no hace nada" {
        val vm = MoveStockViewModel(catalogo, listOf(producto))
        vm.onIntent(MoveStockIntent.Confirmed)
        vm.state.value.submitting shouldBe false
    }

    "el modo viaja en el estado para que la misma pantalla sirva a los dos flujos" {
        val vm = MoveStockViewModel(catalogo, listOf(producto), mode = MoveMode.BETWEEN_SHELVES)
        vm.state.value.mode shouldBe MoveMode.BETWEEN_SHELVES
        vm.state.value.scan.shouldBeInstanceOf<ScanState.Idle>()
    }
})
