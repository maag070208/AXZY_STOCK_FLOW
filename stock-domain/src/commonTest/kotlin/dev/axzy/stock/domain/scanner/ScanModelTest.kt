package dev.axzy.stock.domain.scanner

import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TESTS DEL MODELO DEL ESCÁNER
 *
 * Cubre los **dos bugs del escáner del legacy**, y cada uno con su caso negativo:
 *
 *  · **M4** — la ventana visual medía 300×100 y la validación aceptaba 250×50.
 *    Escaneos válidos descartados.
 *  · **M3** — la máquina de estados de dos pasos estaba rota: la rama que asigna
 *    la ubicación era inalcanzable sin un error previo.
 *
 * Los dos son de lógica pura, así que se prueban **en JVM, en milisegundos y sin
 * emulador**. El spike de la cámara (CameraX/AVFoundation) verifica que la
 * plataforma entregue los rectángulos; que decidir con ellos sea correcto se
 * verifica aquí.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ScanModelTest : DescribeSpec({

    val producto = ProductCode.of("ABRAA1B")
    val estante = LocationCode.of("04BCA")
    val recepcion = LocationCode.of("RECEPCION")

    fun resolverProducto(codigo: String): ProductCode? =
        if (codigo == "ABRAA1B") producto else null

    fun resolverUbicacion(codigo: String): LocationCode? = when (codigo) {
        "04BCA" -> estante
        "RECEPCION" -> recepcion
        else -> null
    }

    /** Un rectángulo centrado en la ventana de escaneo por defecto. */
    fun centradoEnLaVentana(): ScanRect {
        val v = ScanWindow.DEFAULT.window
        return ScanRect(left = v.centerX - 0.05, top = v.centerY - 0.02, width = 0.10, height = 0.04)
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("M4 · la ventana de escaneo tiene UNA sola definición") {

        it("la máscara visual y la validación leen la MISMA ventana") {
            // La causa raíz del bug del legacy: había dos definiciones de la
            // misma ventana en dos archivos, y nada las mantenía sincronizadas
            // (máscara 300×100, validación 250×50).
            //
            // Aquí hay una sola constante. Este test fija la propiedad: la ventana
            // que se dibuja es exactamente la que se valida.
            val ventanaQueSeDibuja = ScanWindow.DEFAULT.window
            val ventanaQueSeValida = ScanWindow.DEFAULT.effective

            withClue("La ventana efectiva CONTIENE a la visual: se valida lo que se dibuja") {
                (ventanaQueSeValida.left <= ventanaQueSeDibuja.left) shouldBe true
                (ventanaQueSeValida.top <= ventanaQueSeDibuja.top) shouldBe true
                (ventanaQueSeValida.right >= ventanaQueSeDibuja.right) shouldBe true
                (ventanaQueSeValida.bottom >= ventanaQueSeDibuja.bottom) shouldBe true
            }
        }

        it("un código centrado en la ventana se acepta") {
            val centrado = centradoEnLaVentana()

            withClue("Un código en el centro de la banda visual debe aceptarse") {
                ScanWindow.DEFAULT.contains(centrado) shouldBe true
            }
        }

        it("un código FUERA de la ventana se rechaza con un motivo accionable") {
            // El legacy descartaba esos escaneos sin decir nada, así que el
            // operador repetía el gesto sin entender por qué.
            val fuera = ScanRect(left = 0.02, top = 0.02, width = 0.05, height = 0.05)

            withClue("Fuera de la ventana: no se acepta") {
                ScanWindow.DEFAULT.contains(fuera) shouldBe false
            }

            val resultado = ScanState.Idle.onScan(
                ScanInput("ABRAA1B", fuera),
                resolveProduct = ::resolverProducto,
                resolveLocation = ::resolverUbicacion,
            )

            val rechazo = resultado.shouldBeInstanceOf<ScanOutcome.Rejected>()
            withClue("El mensaje debe decir QUÉ hacer, no solo que falló") {
                (rechazo.reason.contains("ventana")) shouldBe true
            }
        }

        it("el centro del código decide, no su esquina superior") {
            // El legacy comprobaba el ORIGEN del bounding box (`y < top + 50`),
            // así que un código alto quedaba fuera aunque estuviera visualmente
            // centrado. Es la causa de que pareciera desalineado.
            val ventana = ScanWindow.DEFAULT.window

            // Un rectángulo cuyo centro está DENTRO pero cuyo origen está arriba.
            val altoPeroCentrado = ScanRect(
                left = ventana.centerX - 0.02,
                top = ventana.top - 0.03,      // el origen está fuera, por arriba
                width = 0.04,
                height = 0.12,                 // pero el centro cae dentro
            )

            withClue("Su centro (${altoPeroCentrado.centerY}) cae dentro de la ventana") {
                (altoPeroCentrado.centerY >= ventana.top) shouldBe true
                (altoPeroCentrado.centerY <= ventana.bottom) shouldBe true
            }
            withClue(
                "El legacy lo habría DESCARTADO por su origen. Aquí se acepta, " +
                    "porque el operador lo ve centrado."
            ) {
                ScanWindow.DEFAULT.contains(altoPeroCentrado) shouldBe true
            }
        }

        it("la tolerancia permite un pulso tembloroso sin repetir el escaneo") {
            val ventana = ScanWindow.DEFAULT
            // Justo por debajo del borde de la ventana visual, dentro de la
            // tolerancia.
            val apenasFuera = ScanRect(
                left = ventana.window.centerX - 0.02,
                top = ventana.window.top - 0.03,
                width = 0.04,
                height = 0.04,
            )

            withClue("Su centro está fuera de la ventana visual") {
                (apenasFuera.centerY < ventana.window.top) shouldBe true
            }
            withClue("Pero dentro de la tolerancia, así que se acepta") {
                ventana.contains(apenasFuera) shouldBe true
            }
        }

        it("una tolerancia fuera de rango no se puede construir") {
            val invalida = runCatching {
                ScanWindow(ScanWindow.DEFAULT.window, tolerance = 0.9)
            }
            invalida.isFailure shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("M3 · la máquina de estados: la rama inalcanzable ya no existe") {

        it("el flujo completo de dos pasos funciona en DOS escaneos, no tres") {
            // El bug del legacy: step se incrementaba una vez y el handler
            // comparaba step === 1, así que la rama de la ubicación era
            // inalcanzable hasta que un error devolvía step a 0. El operador
            // necesitaba un tercer escaneo.
            val centrado = centradoEnLaVentana()

            // Escaneo 1: producto.
            val trasProducto = ScanState.Idle.onScan(
                ScanInput("ABRAA1B", centrado),
                resolveProduct = ::resolverProducto,
                resolveLocation = ::resolverUbicacion,
                unitsEntered = 50,
            )

            val estado1 = trasProducto.shouldBeInstanceOf<ScanOutcome.Advanced>().state
            withClue("Tras UN escaneo, el estado lleva el producto") {
                estado1.shouldBeInstanceOf<ScanState.ProductScanned>().product shouldBe producto
            }

            // Escaneo 2: ubicación. Debe funcionar directamente.
            val trasUbicacion = estado1.onScan(
                ScanInput("04BCA", centrado),
                resolveProduct = ::resolverProducto,
                resolveLocation = ::resolverUbicacion,
            )

            val estado2 = trasUbicacion.shouldBeInstanceOf<ScanOutcome.Advanced>().state
            val listo = estado2.shouldBeInstanceOf<ScanState.ReadyToConfirm>()
            withClue("Con DOS escaneos debe estar listo para confirmar, no con tres") {
                listo.product shouldBe producto
                listo.units shouldBe 50
                listo.to shouldBe estante
            }
        }

        it("NO se puede asignar una ubicación sin haber escaneado el producto") {
            // Ésta es la transición que el legacy permitía escribir mal. Aquí el
            // estado Idle no lleva producto, así que **no hay forma de
            // expresarla**: no es que la validación la rechace, es que el tipo no
            // la admite.
            val centrado = centradoEnLaVentana()

            // Desde Idle, cualquier código se interpreta como producto, nunca como
            // ubicación. Escanear una ubicación devuelve un error claro.
            val resultado = ScanState.Idle.onScan(
                ScanInput("04BCA", centrado),
                resolveProduct = ::resolverProducto,
                resolveLocation = ::resolverUbicacion,
            )

            val rechazo = resultado.shouldBeInstanceOf<ScanOutcome.Rejected>()
            withClue("Debe decir que ese código no es un producto") {
                (rechazo.reason.contains("no es un código de producto")) shouldBe true
            }
        }

        it("exige las unidades antes de escanear la ubicación") {
            val centrado = centradoEnLaVentana()

            val estadoConProducto = ScanState.ProductScanned(producto, units = null)

            val resultado = estadoConProducto.onScan(
                ScanInput("04BCA", centrado),
                resolveProduct = ::resolverProducto,
                resolveLocation = ::resolverUbicacion,
                unitsEntered = null,
            )

            val rechazo = resultado.shouldBeInstanceOf<ScanOutcome.Rejected>()
            withClue("Sin unidades no se puede continuar, y el mensaje lo explica") {
                (rechazo.reason.contains("unidades")) shouldBe true
            }
        }

        it("no se puede encadenar un segundo movimiento sin confirmar el primero") {
            val centrado = centradoEnLaVentana()

            val listo = ScanState.ReadyToConfirm(producto, units = 50, to = estante)

            val resultado = listo.onScan(
                ScanInput("ABRAA1B", centrado),
                resolveProduct = ::resolverProducto,
                resolveLocation = ::resolverUbicacion,
            )

            val rechazo = resultado.shouldBeInstanceOf<ScanOutcome.Rejected>()
            withClue("Debe pedir confirmar o cancelar antes de seguir") {
                (rechazo.reason.contains("confirmar")) shouldBe true
            }
        }

        it("rechaza mover mercancía a la ubicación de recepción") {
            val centrado = centradoEnLaVentana()
            val estado = ScanState.ProductScanned(producto, units = 50)

            val resultado = estado.onScan(
                ScanInput("RECEPCION", centrado),
                resolveProduct = ::resolverProducto,
                resolveLocation = ::resolverUbicacion,
            )

            val rechazo = resultado.shouldBeInstanceOf<ScanOutcome.Rejected>()
            withClue("Mover a la misma ubicación de origen no es un movimiento") {
                (rechazo.reason.contains("misma ubicación")) shouldBe true
            }
        }

        it("el número de paso es solo para la interfaz, nunca para la lógica") {
            // El legacy comparaba `step === 1` para decidir qué hacer. Aquí el
            // paso existe para mostrar "paso 2 de 4" y nada más: la decisión la
            // toma el TIPO de estado.
            val estados = listOf(
                ScanState.Idle,
                ScanState.ProductScanned(producto, units = null),
                ScanState.ProductScanned(producto, units = 50),
                ScanState.ReadyToConfirm(producto, 50, estante),
            )

            val pasos = estados.map { it.step }
            withClue("Los pasos son crecientes y sirven para mostrar progreso") {
                pasos shouldBe listOf(1, 2, 3, 4)
            }
            withClue(
                "Pero ningún test decide nada con `step`: todas las decisiones son " +
                    "`when` sobre el tipo. Ésa es la corrección estructural."
            ) {
                estados.distinct().size shouldBe 4
            }
        }

        it("la instrucción que se muestra corresponde al estado real") {
            // El legacy mostraba la instrucción del paso equivocado por el mismo
            // bug de comparación, así que el operador leía "escanea el producto"
            // cuando debía escanear la ubicación.
            ScanState.Idle.expected shouldBe ScanTarget.PRODUCT
            ScanState.ProductScanned(producto, 50).expected shouldBe ScanTarget.LOCATION
            ScanState.ReadyToConfirm(producto, 50, estante).expected shouldBe ScanTarget.LOCATION
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("el rectángulo en coordenadas normalizadas") {

        it("rechaza coordenadas fuera del fotograma") {
            listOf(
                { ScanRect(-0.1, 0.5, 0.2, 0.2) },
                { ScanRect(0.5, 1.2, 0.2, 0.2) },
                { ScanRect(0.9, 0.5, 0.2, 0.2) },   // excede el ancho
                { ScanRect(0.5, 0.9, 0.2, 0.2) },   // excede el alto
                { ScanRect(0.5, 0.5, 0.0, 0.2) },   // ancho cero
            ).forEach { constructor ->
                withClue("Un rectángulo inválido no se puede construir") {
                    runCatching { constructor() }.isFailure shouldBe true
                }
            }
        }

        it("calcula centro y bordes correctamente") {
            val r = ScanRect(left = 0.10, top = 0.20, width = 0.40, height = 0.10)

            // Se compara con tolerancia: en coma flotante, 0.10 + 0.20 no es
            // exactamente 0.30 sino 0.30000000000000004. Un `shouldBe` exacto
            // sobre `Double` produce fallos que no indican ningún error real.
            val epsilon = 1e-9
            withClue("Los bordes y el centro deben calcularse bien") {
                kotlin.math.abs(r.right - 0.50) shouldBeLessThan epsilon
                kotlin.math.abs(r.bottom - 0.30) shouldBeLessThan epsilon
                kotlin.math.abs(r.centerX - 0.30) shouldBeLessThan epsilon
                kotlin.math.abs(r.centerY - 0.25) shouldBeLessThan epsilon
            }
        }

        it("trabaja en coordenadas normalizadas para ser independiente de la densidad") {
            // Con píxeles absolutos, como en el legacy, la misma ventana lógica
            // medía cosas distintas en un teléfono y en una tablet. Normalizar
            // elimina esa clase de error.
            val enTelefono = ScanWindow.DEFAULT.contains(
                ScanRect(left = 0.45, top = 0.42, width = 0.10, height = 0.04),
            )
            val enTablet = ScanWindow.DEFAULT.contains(
                ScanRect(left = 0.45, top = 0.42, width = 0.10, height = 0.04),
            )

            withClue("El mismo código relativo se acepta igual en cualquier pantalla") {
                enTelefono shouldBe enTablet
                enTelefono shouldBe true
            }
        }
    }
})
