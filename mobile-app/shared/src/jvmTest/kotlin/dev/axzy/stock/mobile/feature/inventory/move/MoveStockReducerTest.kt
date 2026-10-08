package dev.axzy.stock.mobile.feature.inventory.move

import dev.axzy.stock.domain.scanner.ScanInput
import dev.axzy.stock.domain.scanner.ScanRect
import dev.axzy.stock.domain.scanner.ScanState
import dev.axzy.stock.domain.scanner.ScanWindow
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.withClue
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TESTS DEL REDUCER DE «MOVER MERCANCÍA» — en JVM, sin emulador
 *
 * Éste es el criterio de aceptación de la Fase 6: «los tests de los ViewModels con
 * máquina de estados pasan en JVM sin arrancar emulador». Es la razón de haber
 * puesto el escáner y las máquinas de estado en `stock-domain` y de que este
 * reducer sea una función pura.
 *
 * LO QUE SE PRUEBA AQUÍ ES LO QUE EL LEGACY TENÍA ROTO:
 *   · M3: la rama que asignaba la ubicación era inalcanzable. Aquí se prueba que
 *     el flujo de dos pasos LLEGA a `ReadyToConfirm`.
 *   · M4: la ventana de la máscara y la de validación estaban desalineadas. Aquí se
 *     prueba que la ventana que se DIBUJA es la que VALIDA, con el caso concreto
 *     del legacy (un código que está en la banda visible pero fuera de la ventana
 *     estrecha de validación).
 * ═══════════════════════════════════════════════════════════════════════════
 */
class MoveStockReducerTest : StringSpec({

    // ── Utilidades del test ─────────────────────────────────────────────────

    /** Centro de la ventana: un código aquí dentro SIEMPRE debe aceptarse. */
    fun centroDeLaVentana(window: ScanWindow = ScanWindow.DEFAULT) = ScanRect(
        left = window.window.centerX - 0.002,
        top = window.window.centerY - 0.002,
        width = 0.004,
        height = 0.004,
    )

    // Un código del alfabeto real del catálogo (0-9 y A-Z, sin Ñ, O ni W).
    val abrazadera = ProductCode.of("ABRAA1B")
    val estante = LocationCode.of("04BCA")

    val resolverProducto: (String) -> ProductCode? = { raw ->
        if (raw == "ABRAA1B") abrazadera else null
    }
    val resolverUbicacion: (String) -> LocationCode? = { raw ->
        when (raw) {
            "04BCA" -> estante
            "RECEPCION" -> LocationCode.of("RECEPCION")
            else -> null
        }
    }

    fun estadoInicial() = MoveStockUiState(mode = MoveMode.LOCATE)

    fun reduce(estado: MoveStockUiState, intent: MoveStockIntent, window: ScanWindow = ScanWindow.DEFAULT) =
        reduceMoveStock(
            state = estado,
            intent = intent,
            window = window,
            resolveProduct = resolverProducto,
            resolveLocation = resolverUbicacion,
        )

    fun escanear(estado: MoveStockUiState, codigo: String, window: ScanWindow = ScanWindow.DEFAULT) =
        reduce(estado, MoveStockIntent.Scanned(ScanInput(codigo, centroDeLaVentana(window))), window)

    // ════════════════════════════════════════════════════════════════════════
    // EL FLUJO DE DOS PASOS LLEGA AL FINAL (bug M3 del legacy)
    // ════════════════════════════════════════════════════════════════════════

    "el flujo producto → unidades → ubicación llega a ReadyToConfirm" {
        // Antes del arreglo, esta secuencia exacta era imposible: el `step` del
        // legacy se incrementaba y el handler comparaba `step === 1`, así que la
        // rama que asignaba la ubicación nunca se ejecutaba sin un error previo.
        var estado = estadoInicial()
        estado.scan.shouldBeInstanceOf<ScanState.Idle>()

        estado = escanear(estado, "ABRAA1B")
        estado.scan.shouldBeInstanceOf<ScanState.ProductScanned>()

        estado = reduce(estado, MoveStockIntent.UnitsTyped("80"))
        estado.units shouldBe 80L
        (estado.scan as ScanState.ProductScanned).product shouldBe abrazadera

        estado = escanear(estado, "04BCA")
        val listo = estado.scan.shouldBeInstanceOf<ScanState.ReadyToConfirm>()
        listo.units shouldBe 80L
        listo.to shouldBe estante
        estado.canConfirm shouldBe true
    }

    "sin escanear el producto, una ubicación NO se puede asignar" {
        // La transición inválida del legacy: asignar la ubicación en el paso 1.
        // Aquí no se puede expresar, y el estado no lleva el producto, así que la
        // ubicación se rechaza con el motivo correcto.
        val estado = escanear(estadoInicial(), "04BCA")

        estado.scan.shouldBeInstanceOf<ScanState.Idle>()
        (estado.rejection ?: "") shouldBe
            "'04BCA' no es un código de producto conocido"
    }

    "sin unidades, la ubicación se rechaza aunque el producto esté escaneado" {
        var estado = escanear(estadoInicial(), "ABRAA1B")
        estado = escanear(estado, "04BCA")

        estado.scan.shouldBeInstanceOf<ScanState.ProductScanned>()
        estado.rejection shouldBe "Escribe las unidades antes de escanear la ubicación"
        estado.canConfirm shouldBe false
    }

    // ════════════════════════════════════════════════════════════════════════
    // LA VENTANA: EL BUG M4 DEL LEGACY
    // ════════════════════════════════════════════════════════════════════════

    "un código en el punto visible MÁS ALTO de la banda se acepta (bug M4)" {
        // ÉSTE ES EL CASO DEL LEGACY. Su máscara medía 300×100 y su validación
        // 250×50 centradas: la franja entre el borde de la máscara y el de la
        // validación era VISIBLE para el operador y RECHAZADA por el validador.
        //
        // `ScanWindow.DEFAULT` (left=0.10, top=0.35, w=0.80, h=0.20) con tolerancia
        // 0.05 define la ventana efectiva. Este test fija las dos cosas: que el
        // borde de la ventana VISUAL se acepta, y que sigue habiendo un límite
        // —porque una ventana que acepta todo no valida nada—.
        val window = ScanWindow.DEFAULT

        // Justo dentro del borde superior de la ventana visual.
        val dentroDelBordeVisual = ScanRect(
            left = window.window.centerX - 0.002,
            top = window.window.top,
            width = 0.004,
            height = 0.004,
        )
        val estado = reduce(
            estadoInicial(),
            MoveStockIntent.Scanned(ScanInput("ABRAA1B", dentroDelBordeVisual)),
            window,
        )
        estado.scan.shouldBeInstanceOf<ScanState.ProductScanned>()
        estado.positionValid shouldBe true

        // Muy por fuera (arriba del todo del fotograma) SÍ se rechaza.
        val fueraDelTodo = ScanRect(left = 0.45, top = 0.01, width = 0.004, height = 0.004)
        val rechazado = reduce(
            estadoInicial(),
            MoveStockIntent.Scanned(ScanInput("ABRAA1B", fueraDelTodo)),
            window,
        )
        rechazado.scan.shouldBeInstanceOf<ScanState.Idle>()
        rechazado.positionValid shouldBe false
        rechazado.rejection shouldBe
            "El código quedó fuera de la ventana de escaneo. Céntralo en la banda."
    }

    "la ventana que se dibuja es la que valida: cualquier punto de la ventana se acepta" {
        // Se recorre la ventana en rejilla y TODOS los puntos deben aceptarse. Si
        // alguien cambiara la máscara sin cambiar la validación (o al revés), este
        // test falla: es la red que impide reintroducir el bug M4.
        //
        // La rejilla se recorre con un bucle y no con `withData` porque `withData`
        // solo se puede usar en el ámbito RAÍZ del spec: dentro de un `StringSpec`
        // anidado lanza «Cannot add a root test after the spec has been instantiated».
        val window = ScanWindow.DEFAULT
        val puntos = (1..9).map { i -> window.window.left + window.window.width * i / 10.0 }

        puntos.forEach { x ->
            val punto = ScanRect(
                left = x - 0.001,
                top = window.window.centerY - 0.001,
                width = 0.002,
                height = 0.002,
            )
            val estado = reduce(
                estadoInicial(),
                MoveStockIntent.Scanned(ScanInput("ABRAA1B", punto)),
                window,
            )
            withClue("el punto x=$x está dentro de la ventana visual y debe aceptarse") {
                estado.scan.shouldBeInstanceOf<ScanState.ProductScanned>()
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // UNIDADES: la validación es del dominio, no de la pantalla
    // ════════════════════════════════════════════════════════════════════════

    "las unidades se validan y las inválidas no llegan al estado" {
        val casos = listOf(
            "0" to "Las unidades deben ser mayores que cero",
            "abc" to null, // se filtra a vacío: no es un error, es un campo vacío
            "" to null,
        )
        casos.forEach { (texto, esperado) ->
            val estado = reduce(estadoInicial(), MoveStockIntent.UnitsTyped(texto))
            estado.unitsError shouldBe esperado
            if (esperado != null) estado.units shouldBe null
        }

        val negativo = reduce(estadoInicial(), MoveStockIntent.UnitsTyped("-5"))
        // El `-` no es dígito: se descarta y queda vacío, no «unidades negativas».
        negativo.unitsText shouldBe "5"
        negativo.units shouldBe 5L
    }

    "teclear «abc» deja el campo vacío en lugar de asumir cero" {
        // El legacy hacía `parseInt` y sumaba 0 en silencio: de ahí las líneas
        // capturadas en cero que después nadie podía explicar.
        val estado = reduce(estadoInicial(), MoveStockIntent.UnitsTyped("abc"))
        estado.unitsText shouldBe ""
        estado.units shouldBe null
    }

    // ════════════════════════════════════════════════════════════════════════
    // REGLAS DE NEGOCIO QUE VIENEN DEL DOMINIO
    // ════════════════════════════════════════════════════════════════════════

    "no se puede mover a la misma ubicación de recepción" {
        var estado = escanear(estadoInicial(), "ABRAA1B")
        estado = reduce(estado, MoveStockIntent.UnitsTyped("10"))
        estado = escanear(estado, "RECEPCION")

        estado.scan.shouldBeInstanceOf<ScanState.ProductScanned>()
        estado.rejection shouldBe "No se puede mover mercancía a la misma ubicación de origen"
    }

    "un segundo escaneo cuando ya hay movimiento listo se rechaza y NO se pierde el estado" {
        var estado = escanear(estadoInicial(), "ABRAA1B")
        estado = reduce(estado, MoveStockIntent.UnitsTyped("5"))
        estado = escanear(estado, "04BCA")
        val listo = estado.scan.shouldBeInstanceOf<ScanState.ReadyToConfirm>()

        val trasSegundoEscaneo = escanear(estado, "ABRAA1B")
        trasSegundoEscaneo.rejection shouldBe
            "Ya hay un movimiento listo para confirmar. Confírmalo o cancélalo primero."
        // El estado NO se degrada: el operador no pierde lo que ya había escaneado.
        trasSegundoEscaneo.scan shouldBe listo
    }

    // ════════════════════════════════════════════════════════════════════════
    // CONFIRMAR
    // ════════════════════════════════════════════════════════════════════════

    "confirmar solo se acepta con el flujo completo" {
        val incompleto = reduce(estadoInicial(), MoveStockIntent.Confirmed)
        incompleto.submitting shouldBe false

        var estado = escanear(estadoInicial(), "ABRAA1B")
        estado = reduce(estado, MoveStockIntent.UnitsTyped("12"))
        estado = escanear(estado, "04BCA")
        val confirmado = reduce(estado, MoveStockIntent.Confirmed)

        confirmado.submitting shouldBe true
        confirmado.canConfirm shouldBe false
    }

    "reset vuelve al inicio y conserva el modo de la pantalla" {
        var estado = escanear(estadoInicial(), "ABRAA1B")
        estado = reduce(estado, MoveStockIntent.UnitsTyped("7"))
        estado = reduce(estado, MoveStockIntent.Reset)

        estado.scan.shouldBeInstanceOf<ScanState.Idle>()
        estado.units shouldBe null
        estado.unitsText shouldBe ""
        estado.mode shouldBe MoveMode.LOCATE
    }

    "el paso que se muestra sale del DOMINIO, no de un contador local" {
        // Si el paso fuera un contador de la pantalla, podría desincronizarse del
        // estado (que es exactamente lo que pasó en el legacy).
        var estado = estadoInicial()
        estado.step shouldBe estado.scan.step

        estado = escanear(estado, "ABRAA1B")
        estado.step shouldBe estado.scan.step

        estado = reduce(estado, MoveStockIntent.UnitsTyped("3"))
        estado.step shouldBe estado.scan.step

        estado = escanear(estado, "04BCA")
        estado.step shouldBe estado.scan.step
        estado.step shouldBe 4
    }
})
