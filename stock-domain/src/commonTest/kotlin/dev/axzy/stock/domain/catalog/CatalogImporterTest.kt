package dev.axzy.stock.domain.catalog

import dev.axzy.stock.domain.value.Quantity
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TESTS DEL IMPORTADOR DEL CATÁLOGO
 *
 * El test que importa es **el de cuadre**: corre el importador contra el CSV
 * extraído del Excel real y verifica que reproduzca **las cifras exactas del
 * archivo** (1.398 productos y 10.914.130 unidades).
 *
 * Es la diferencia entre "el importador funciona" y "el importador carga los
 * datos correctos". Un importador que descarta filas en silencio pasa el primer
 * test y falla el segundo, y el descuadre aparece después, cuando ya no hay
 * forma de saber qué se perdió.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class CatalogImporterTest : DescribeSpec({

    val importador = CatalogImporter()

    /**
     * Lee el CSV real extraído del Excel.
     *
     * Se lee del classpath para que el test no dependa del directorio de
     * trabajo desde el que se ejecute. El recurso vive en `jvmTest/resources`,
     * que es la única fuente de plataforma que la guardia de arquitectura
     * permite: `jvmMain` está prohibido y `jvmTest` es aceptable porque es
     * código de prueba, no de dominio.
     */
    fun leerCatalogoReal(): String {
        val stream = CatalogImporterTest::class.java.classLoader
            .getResourceAsStream("catalogo_real.csv")
            ?: error(
                "No se encontró el recurso 'catalogo_real.csv'. Es el extracto del " +
                    "Excel real de inventario y el insumo del test de cuadre."
            )
        return stream.bufferedReader().readText()
    }

    fun fila(
        rowNumber: Int = 2,
        externalId: String = "1",
        productCode: String = "ABRAA1B",
        name: String = "ABRAZADERA DE ARRANQUE 1-5/8\"",
        unitMeasure: String = "PZA",
        stock: String = "100",
    ) = CatalogRow(rowNumber, externalId, productCode, name, unitMeasure, stock)

    // ═════════════════════════════════════════════════════════════════════════
    describe("CUADRE CONTRA EL ARCHIVO REAL — el test que importa") {

        it("reproduce las cifras exactas del Excel: 1.398 productos y 10.914.130 unidades") {
            val contenido = leerCatalogoReal()
            val filas = importador.parseCsv(contenido)

            withClue("El CSV extraído del Excel debe tener 1.398 filas de producto") {
                filas.size shouldBe CatalogImporter.EXPECTED_PRODUCTS
            }

            val informe = importador.import(filas, dryRun = true)

            withClue("No debe rechazarse ninguna fila: el archivo tiene calidad excelente") {
                informe.rejectedCount shouldBe 0
            }

            // La verificación de cuadre: si el importador descartara filas en
            // silencio, esta comprobación lo detecta con los números exactos.
            val discrepancias = informe.matchesExpectations(
                expectedProducts = CatalogImporter.EXPECTED_PRODUCTS,
                expectedTotalStock = CatalogImporter.EXPECTED_TOTAL_STOCK,
                expectedRejections = 0,
            )

            withClue("El import debe cuadrar con el archivo. Discrepancias: $discrepancias") {
                discrepancias shouldBe emptyList()
            }

            withClue("La suma de existencias debe ser exactamente la del Excel") {
                informe.totalStock shouldBe Quantity.of(10_914_130)
            }
        }

        it("el informe identifica los 334 productos con existencia cero") {
            val informe = importador.import(importador.parseCsv(leerCatalogoReal()))

            // Dato conocido del archivo: el 23,9% de los productos está en cero.
            // Saberlo importa: son productos que existen en el catálogo pero no
            // tienen stock, y confundirlos con "no existe" genera errores en piso.
            withClue("Deben ser exactamente 334 productos en cero") {
                informe.zeroStockCount shouldBe CatalogImporter.EXPECTED_ZERO_STOCK_PRODUCTS
            }
        }

        it("todas las filas usan la unidad de medida PZA") {
            val informe = importador.import(importador.parseCsv(leerCatalogoReal()))

            withClue("El archivo real usa una sola unidad de medida") {
                informe.unitMeasures shouldBe listOf("PZA")
            }
        }

        it("el número de filas leídas coincide con las 1.398 útiles del archivo") {
            val filas = importador.parseCsv(leerCatalogoReal())

            // El archivo tiene 1.400 líneas: 1 de cabecera y 2 vacías al final.
            // El parser descarta las vacías, así que quedan 1.398.
            withClue("El parser debe descartar las líneas vacías") {
                filas.size shouldBe 1398
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("dry-run: la primera ejecución sobre datos reales es una simulación") {

        it("el informe declara si es una simulación y lo dice al renderizarlo") {
            val informe = importador.import(listOf(fila()), dryRun = true)

            withClue("El informe debe marcar que es simulación") {
                informe.isDryRun shouldBe true
            }
            withClue("Y el texto debe advertirlo explícitamente, sin ambigüedad") {
                informe.render() shouldContain "SIMULACIÓN"
                informe.render() shouldContain "No se escribió nada"
            }
        }

        it("un import real no se marca como simulación") {
            val informe = importador.import(listOf(fila()), dryRun = false)

            informe.isDryRun shouldBe false
            (informe.render().contains("SIMULACIÓN")) shouldBe false
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("reglas estrictas: se rechaza la fila, no se adivina") {

        it("rechaza un código con minúsculas indicando el número de fila") {
            // El alfabeto real es 0-9 y A-Z. Un importador tolerante normalizaría
            // a mayúsculas; aquí se rechaza, porque una minúscula en el archivo
            // significa que algo cambió en el origen y hay que mirarlo.
            val informe = importador.import(
                listOf(fila(rowNumber = 42, productCode = "abraa1b")),
            )

            informe.acceptedCount shouldBe 0
            val rechazo = informe.rejected.single()
                .shouldBeInstanceOf<RowRejection.InvalidProductCode>()
            withClue("El rechazo debe señalar la fila exacta para poder corregirla") {
                rechazo.rowNumber shouldBe 42
                rechazo.code shouldBe "abraa1b"
            }
        }

        it("rechaza un código con guiones") {
            val informe = importador.import(listOf(fila(productCode = "ABRA-A1B")))

            informe.rejected.single()
                .shouldBeInstanceOf<RowRejection.InvalidProductCode>()
        }

        it("rechaza una fila completamente vacía") {
            val informe = importador.import(
                listOf(CatalogRow(rowNumber = 1399, "", "", "", "", "")),
            )

            informe.rejected.single().shouldBeInstanceOf<RowRejection.BlankRow>()
        }

        it("rechaza una unidad de medida desconocida") {
            // Es la defensa contra el clásico KG / Kg / KGS como tres unidades
            // distintas: si no está en el catálogo, se rechaza.
            val informe = importador.import(listOf(fila(unitMeasure = "KGS")))

            val rechazo = informe.rejected.single()
                .shouldBeInstanceOf<RowRejection.UnknownUnitMeasure>()
            rechazo.unit shouldBe "KGS"
        }

        it("acepta la unidad en minúsculas porque no es ambiguo") {
            // A diferencia del código, la unidad se normaliza: 'pza' es
            // inequívocamente la misma unidad.
            val informe = importador.import(listOf(fila(unitMeasure = "pza")))

            informe.acceptedCount shouldBe 1
            informe.accepted.single().unitMeasure shouldBe "PZA"
        }

        it("rechaza existencias no numéricas") {
            val informe = importador.import(listOf(fila(stock = "N/A")))

            informe.rejected.single().shouldBeInstanceOf<RowRejection.InvalidStock>()
        }

        it("rechaza existencias negativas: son imposibles en un inventario físico") {
            val informe = importador.import(listOf(fila(stock = "-50")))

            val rechazo = informe.rejected.single().shouldBeInstanceOf<RowRejection.InvalidStock>()
            withClue("El motivo debe explicar por qué, no solo que falló") {
                (rechazo.detail.contains("negativas")) shouldBe true
            }
        }

        it("rechaza un producto sin nombre") {
            val informe = importador.import(listOf(fila(name = "   ")))

            informe.rejected.single().shouldBeInstanceOf<RowRejection.BlankName>()
        }

        it("rechaza un código duplicado indicando dónde apareció primero") {
            val informe = importador.import(
                listOf(
                    fila(rowNumber = 2, productCode = "ABRAA1B"),
                    fila(rowNumber = 50, productCode = "ABRAA1B"),
                ),
            )

            withClue("Se acepta la primera aparición") { informe.acceptedCount shouldBe 1 }

            val rechazo = informe.rejected.single()
                .shouldBeInstanceOf<RowRejection.DuplicateCode>()
            withClue("El rechazo debe decir en qué fila se vio primero, para poder compararlas") {
                rechazo.firstSeenAtRow shouldBe 2
                rechazo.rowNumber shouldBe 50
            }
        }

        it("una fila rechazada no impide procesar las demás") {
            val informe = importador.import(
                listOf(
                    fila(rowNumber = 2, productCode = "ABRAA1B"),
                    fila(rowNumber = 3, productCode = "mal"),
                    fila(rowNumber = 4, productCode = "ABRAA1E", stock = "-1"),
                    fila(rowNumber = 5, productCode = "ABRAAL1B"),
                ),
            )

            withClue("Dos filas válidas deben aceptarse pese a las dos inválidas") {
                informe.acceptedCount shouldBe 2
                informe.rejectedCount shouldBe 2
                informe.totalRows shouldBe 4
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("parser CSV: el archivo real trae comillas y comas dentro de campos") {

        it("maneja las comillas dobles escapadas del archivo real") {
            // 876 nombres del archivo llevan comillas dobles: 'ABRAZADERA 1-5/8"'.
            // Un split ingenuo por comas rompería esas filas.
            val contenido = listOf(
                "\$product_id,product_code,name,unit_measure,stock",
                "1,ABRAA1B,\"ABRAZADERA DE ARRANQUE 1-5/8\"\"\",PZA,5193"
            ).joinToString("\n")

            val filas = importador.parseCsv(contenido)

            filas.size shouldBe 1
            withClue("La comilla doble debe conservarse en el nombre") {
                filas.single().name shouldBe "ABRAZADERA DE ARRANQUE 1-5/8\""
            }
            filas.single().stock shouldBe "5193"
        }

        it("maneja comas dentro de un campo entrecomillado") {
            val contenido = listOf(
                "\$product_id,product_code,name,unit_measure,stock",
                "9,ADORNR405N,\"ADORNO COLONIAL R405, ARQUITO (#7050)\",PZA,932"
            ).joinToString("\n")

            val filas = importador.parseCsv(contenido)

            withClue("La coma dentro de comillas NO debe partir el campo") {
                filas.single().name shouldBe "ADORNO COLONIAL R405, ARQUITO (#7050)"
            }
            filas.single().stock shouldBe "932"
        }

        it("la numeración de fila empieza en 2 porque la 1 es la cabecera") {
            val contenido = listOf(
                "\$product_id,product_code,name,unit_measure,stock",
                "1,ABRAA1B,PRIMERO,PZA,1",
                "2,ABRAA1E,SEGUNDO,PZA,2"
            ).joinToString("\n")

            val filas = importador.parseCsv(contenido)

            withClue("Los números de fila deben corresponder al archivo, para poder localizar errores") {
                filas[0].rowNumber shouldBe 2
                filas[1].rowNumber shouldBe 3
            }
        }

        it("descarta las líneas vacías del final del archivo") {
            val contenido = listOf(
                "\$product_id,product_code,name,unit_measure,stock",
                "1,ABRAA1B,PRODUCTO,PZA,10"
            ).joinToString("\n")

            importador.parseCsv(contenido).size shouldBe 1
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("el informe de cuadre detecta lo que un import silencioso escondería") {

        it("detecta que se perdieron productos") {
            val informe = importador.import(listOf(fila()))

            val discrepancias = informe.matchesExpectations(
                expectedProducts = 1398,
                expectedTotalStock = Quantity.of(10_914_130),
            )

            withClue("Debe reportar la diferencia de productos") {
                (discrepancias.any { it.contains("1398") }) shouldBe true
            }
            withClue("Y también la de unidades") {
                (discrepancias.any { it.contains("unidades") }) shouldBe true
            }
        }

        it("el informe renderizado incluye el cuadre y las filas rechazadas") {
            val informe = importador.import(
                listOf(
                    fila(productCode = "ABRAA1B", stock = "100"),
                    fila(rowNumber = 3, productCode = "mal"),
                ),
            )

            val texto = informe.render()

            withClue("El informe debe ser legible para alguien de negocio") {
                texto shouldContain "Informe de importación"
                texto shouldContain "Productos aceptados"
                texto shouldContain "Existencias totales"
                texto shouldContain "Filas rechazadas"
            }
            withClue("Y debe detallar qué fila falló y por qué") {
                texto shouldContain "fila 3"
            }
        }

        it("los datos que se persisten son los del archivo, sin transformarlos") {
            val informe = importador.import(
                listOf(
                    fila(
                        rowNumber = 2, externalId = "1", productCode = "ABRAA1B",
                        name = "ABRAZADERA DE ARRANQUE 1-5/8\"", unitMeasure = "PZA", stock = "5193",
                    ),
                ),
            )

            val producto = informe.accepted.single()
            withClue("El código se valida pero no se altera") {
                producto.code.value shouldBe "ABRAA1B"
            }
            withClue("El identificador externo del ERP se conserva") {
                producto.externalId shouldBe "1"
            }
            withClue("Las existencias se convierten exactamente") {
                producto.stock shouldBe Quantity.of(5193)
            }
        }

        it("un externalId en blanco se guarda como nulo, no como cadena vacía") {
            val informe = importador.import(listOf(fila(externalId = "   ")))

            informe.accepted.single().externalId shouldBe null
        }
    }
})
