package dev.axzy.stock.mobile.core.camera

import dev.axzy.stock.domain.scanner.ScanInput
import dev.axzy.stock.domain.scanner.ScanRect
import dev.axzy.stock.domain.scanner.ScanState
import dev.axzy.stock.domain.scanner.ScanWindow
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Tests de [ScanSession]: el pegamento entre la cámara y la máquina de estados del
 * dominio.
 *
 * LO QUE SE PRUEBA AQUÍ ES EL COMPORTAMIENTO CON LA CÁMARA, SIN CÁMARA:
 * el filtro de repetición de fotogramas (ML Kit y AVFoundation entregan el mismo
 * código en cada frame mientras siga enfocado; sin filtrar, un segundo de enfoque
 * son 30 «escaneos») y que el flujo avanza con `ScanState` del dominio.
 *
 * El reloj se inyecta: un test que depende del reloj real es un test que falla un
 * martes por la tarde.
 */
class ScanSessionTest : StringSpec({

    val producto = ProductCode.of("ABRAA1B")
    val ubicacion = LocationCode.of("04BCA")

    fun centro(window: ScanWindow = ScanWindow.DEFAULT) = ScanRect(
        left = window.window.centerX - 0.002,
        top = window.window.centerY - 0.002,
        width = 0.004,
        height = 0.004,
    )

    fun nuevaSesion(reloj: () -> Long = { 0L }) = ScanSession(
        window = ScanWindow.DEFAULT,
        resolveProduct = { raw -> if (raw == "ABRAA1B") producto else null },
        resolveLocation = { raw -> if (raw == "04BCA") ubicacion else null },
        nowMs = reloj,
    )

    "un código repetido en el mismo segundo no avanza el flujo dos veces" {
        // Sin este filtro, apuntar al mismo código durante un segundo ejecutaría
        // ~30 transiciones, y el flujo de dos pasos se rompería solo.
        var ahora = 1_000L
        val sesion = nuevaSesion { ahora }

        sesion.onCameraScan(ScanInput("ABRAA1B", centro()))
        sesion.state.value.shouldBeInstanceOf<ScanState.ProductScanned>()

        // Mismo código, 100 ms después (el fotograma siguiente).
        ahora += 100
        sesion.onCameraScan(ScanInput("ABRAA1B", centro()))
        // Sigue en el mismo estado: no ha retrocedido ni avanzado de más.
        sesion.state.value.shouldBeInstanceOf<ScanState.ProductScanned>()
    }

    "el mismo código SÍ se acepta pasado el intervalo: puede haber dos líneas iguales" {
        // Se acepta a propósito: el operador puede necesitar escanear el MISMO
        // producto dos veces seguidas (dos líneas del mismo artículo). Bloquearlo
        // para siempre sería cambiar un defecto por otro.
        var ahora = 5_000L
        val sesion = nuevaSesion { ahora }

        // Primer escaneo del producto, con sus unidades.
        sesion.onCameraScan(ScanInput("ABRAA1B", centro()), unitsEntered = 10)
        sesion.state.value.shouldBeInstanceOf<ScanState.ProductScanned>()

        // La ubicación completa el primer movimiento.
        ahora += 500
        sesion.onCameraScan(ScanInput("04BCA", centro()), unitsEntered = 10)
        sesion.state.value.shouldBeInstanceOf<ScanState.ReadyToConfirm>()

        // Se cancela y se vuelve a empezar con el MISMO producto: el filtro de
        // repetición no lo bloquea.
        sesion.reset()
        ahora += 100
        sesion.onCameraScan(ScanInput("ABRAA1B", centro()), unitsEntered = 10)

        sesion.state.value.shouldBeInstanceOf<ScanState.ProductScanned>()
        sesion.lastRejection.value shouldBe null
    }

    "las unidades se pasan al dominio, no se guardan en la sesión" {
        var ahora = 10_000L
        val sesion = nuevaSesion { ahora }

        sesion.onCameraScan(ScanInput("ABRAA1B", centro()), unitsEntered = 80)
        val estado = sesion.state.value.shouldBeInstanceOf<ScanState.ProductScanned>()
        estado.units shouldBe 80L

        ahora += 1_000
        sesion.onCameraScan(ScanInput("04BCA", centro()), unitsEntered = 80)
        val listo = sesion.state.value.shouldBeInstanceOf<ScanState.ReadyToConfirm>()
        listo.units shouldBe 80L
        listo.to shouldBe ubicacion
    }

    "un escaneo fuera de ventana se reporta y NO avanza el estado" {
        val sesion = nuevaSesion()
        val fuera = ScanRect(left = 0.45, top = 0.01, width = 0.004, height = 0.004)

        sesion.onCameraScan(ScanInput("ABRAA1B", fuera))

        sesion.state.value.shouldBeInstanceOf<ScanState.Idle>()
        sesion.positionValid.value shouldBe false
        (sesion.lastRejection.value ?: "").startsWith("El código quedó fuera") shouldBe true
    }

    "la marca de posición usa la MISMA ventana que la validación" {
        // El bug M4 era tener dos ventanas. Si esta comprobación usara otra fórmula,
        // el marco de la máscara podría estar verde con un escaneo rechazado, o al
        // revés — que es la peor versión del defecto, porque engaña al operador.
        val sesion = nuevaSesion()
        val window = ScanWindow.DEFAULT

        // Un punto en el borde exacto de la ventana visual.
        val enElBorde = ScanRect(
            left = window.window.centerX - 0.001,
            top = window.window.top,
            width = 0.002,
            height = 0.002,
        )
        sesion.onCameraScan(ScanInput("ABRAA1B", enElBorde))

        sesion.positionValid.value shouldBe true
        sesion.state.value.shouldBeInstanceOf<ScanState.ProductScanned>()
        sesion.lastRejection.value shouldBe null
    }

    "dismissRejection limpia el aviso sin perder el avance del flujo" {
        val sesion = nuevaSesion()
        sesion.onCameraScan(ScanInput("ABRAA1B", centro()))
        sesion.onCameraScan(
            ScanInput("04BCA", centro()),
            // Sin unidades: produce rechazo.
        )
        (sesion.lastRejection.value ?: "").startsWith("Escribe las unidades") shouldBe true

        sesion.dismissRejection()

        sesion.lastRejection.value shouldBe null
        sesion.state.value.shouldBeInstanceOf<ScanState.ProductScanned>()
    }

    "reset vuelve al inicio y libera el filtro de repetición" {
        var ahora = 20_000L
        val sesion = nuevaSesion { ahora }
        sesion.onCameraScan(ScanInput("ABRAA1B", centro()))

        sesion.reset()

        sesion.state.value.shouldBeInstanceOf<ScanState.Idle>()
        sesion.lastRejection.value shouldBe null
        sesion.positionValid.value shouldBe null

        // El mismo código inmediatamente después del reset debe volver a entrar.
        ahora += 10
        sesion.onCameraScan(ScanInput("ABRAA1B", centro()))
        sesion.state.value.shouldBeInstanceOf<ScanState.ProductScanned>()
    }
})

/** Tests del filtro de repetición, aislados del escáner. */
class ScanThrottleTest : StringSpec({

    "el primer código siempre pasa" {
        ScanThrottle().shouldEmit("ABRAA1B", 0L) shouldBe true
    }

    "el mismo código dentro del intervalo se descarta" {
        val throttle = ScanThrottle(sameCodeIntervalMs = 1_000L)
        throttle.shouldEmit("ABRAA1B", 0L) shouldBe true
        throttle.shouldEmit("ABRAA1B", 500L) shouldBe false
    }

    "un código distinto pasa aunque sea inmediato" {
        // Escanear producto y ubicación seguidos es el flujo NORMAL: si el filtro
        // fuera por «mismo instante» en lugar de por «mismo código», el segundo paso
        // sería imposible y el bug M3 reaparecería por otra vía.
        val throttle = ScanThrottle(sameCodeIntervalMs = 1_000L)
        throttle.shouldEmit("ABRAA1B", 0L) shouldBe true
        throttle.shouldEmit("04BCA", 10L) shouldBe true
    }

    "el mismo código pasado el intervalo vuelve a pasar" {
        val throttle = ScanThrottle(sameCodeIntervalMs = 1_000L)
        throttle.shouldEmit("ABRAA1B", 0L) shouldBe true
        throttle.shouldEmit("ABRAA1B", 1_500L) shouldBe true
    }

    "reset olvida el último código" {
        val throttle = ScanThrottle(sameCodeIntervalMs = 10_000L)
        throttle.shouldEmit("ABRAA1B", 0L) shouldBe true
        throttle.shouldEmit("ABRAA1B", 1L) shouldBe false
        throttle.reset()
        throttle.shouldEmit("ABRAA1B", 2L) shouldBe true
    }
})
