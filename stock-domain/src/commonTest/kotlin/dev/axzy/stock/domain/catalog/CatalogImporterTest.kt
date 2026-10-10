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

    val importer = CatalogImporter()

    /**
     * Lee el CSV real extraído del Excel.
     *
     * Se lee del classpath para que el test no dependa del directorio de
     * trabajo desde el que se ejecute. El recurso vive en `jvmTest/resources`,
     * que es la única fuente de plataforma que la guardia de arquitectura
     * permite: `jvmMain` está prohibido y `jvmTest` es aceptable porque es
     * código de prueba, no de dominio.
     */
    fun readRealCatalog(): String {
        val stream = CatalogImporterTest::class.java.classLoader
            .getResourceAsStream("catalogo_real.csv")
            ?: error(
                "No se encontró el recurso 'catalogo_real.csv'. Es el extracto del " +
                    "Excel real de inventario y el insumo del test de cuadre."
            )
        return stream.bufferedReader().readText()
    }

    fun row(
        rowNumber: Int = 2,
        externalId: String = "1",
        productCode: String = "ABRAA1B",
        name: String = "ABRAZADERA DE ARRANQUE 1-5/8\"",
        unitMeasure: String = "PZA",
        stock: String = "100",
    ) = CatalogRow(rowNumber, externalId, productCode, name, unitMeasure, stock)

    // ═════════════════════════════════════════════════════════════════════════
    describe("RECONCILIATION AGAINST THE REAL FILE — the test that matters") {

        it("reproduces the exact figures of the Excel: 1,398 products and 10,914,130 units") {
            val content = readRealCatalog()
            val rows = importer.parseCsv(content)

            withClue("El CSV extraído del Excel debe tener 1.398 filas de producto") {
                rows.size shouldBe CatalogImporter.EXPECTED_PRODUCTS
            }

            val report = importer.import(rows, dryRun = true)

            withClue("No debe rechazarse ninguna fila: el archivo tiene calidad excelente") {
                report.rejectedCount shouldBe 0
            }

            // La verificación de cuadre: si el importador descartara filas en
            // silencio, esta comprobación lo detecta con los números exactos.
            val discrepancies = report.matchesExpectations(
                expectedProducts = CatalogImporter.EXPECTED_PRODUCTS,
                expectedTotalStock = CatalogImporter.EXPECTED_TOTAL_STOCK,
                expectedRejections = 0,
            )

            withClue("El import debe cuadrar con el archivo. Discrepancias: $discrepancies") {
                discrepancies shouldBe emptyList()
            }

            withClue("La suma de existencias debe ser exactamente la del Excel") {
                report.totalStock shouldBe Quantity.of(10_914_130)
            }
        }

        it("the report identifies the 334 products with zero stock") {
            val report = importer.import(importer.parseCsv(readRealCatalog()))

            // Dato conocido del archivo: el 23,9% de los productos está en cero.
            // Saberlo importa: son productos que existen en el catálogo pero no
            // tienen stock, y confundirlos con "no existe" genera errores en piso.
            withClue("Deben ser exactamente 334 productos en cero") {
                report.zeroStockCount shouldBe CatalogImporter.EXPECTED_ZERO_STOCK_PRODUCTS
            }
        }

        it("all rows use the unit of measure PZA") {
            val report = importer.import(importer.parseCsv(readRealCatalog()))

            withClue("El archivo real usa una sola unidad de medida") {
                report.unitMeasures shouldBe listOf("PZA")
            }
        }

        it("the number of rows read matches the 1,398 useful ones in the file") {
            val rows = importer.parseCsv(readRealCatalog())

            // El archivo tiene 1.400 líneas: 1 de cabecera y 2 vacías al final.
            // El parser descarta las vacías, así que quedan 1.398.
            withClue("El parser debe descartar las líneas vacías") {
                rows.size shouldBe 1398
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("dry-run: the first run over real data is a simulation") {

        it("the report states whether it is a simulation and says so when rendered") {
            val report = importer.import(listOf(row()), dryRun = true)

            withClue("El informe debe marcar que es simulación") {
                report.isDryRun shouldBe true
            }
            withClue("Y el texto debe advertirlo explícitamente, sin ambigüedad") {
                report.render() shouldContain "DRY RUN"
                report.render() shouldContain "Nothing was written"
            }
        }

        it("a real import is not marked as a simulation") {
            val report = importer.import(listOf(row()), dryRun = false)

            report.isDryRun shouldBe false
            (report.render().contains("DRY RUN")) shouldBe false
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("strict rules: the row is rejected, not guessed") {

        it("rejects a lowercase code, stating the row number") {
            // El alfabeto real es 0-9 y A-Z. Un importador tolerante normalizaría
            // a mayúsculas; aquí se rechaza, porque una minúscula en el archivo
            // significa que algo cambió en el origen y hay que mirarlo.
            val report = importer.import(
                listOf(row(rowNumber = 42, productCode = "abraa1b")),
            )

            report.acceptedCount shouldBe 0
            val rejection = report.rejected.single()
                .shouldBeInstanceOf<RowRejection.InvalidProductCode>()
            withClue("El rechazo debe señalar la fila exacta para poder corregirla") {
                rejection.rowNumber shouldBe 42
                rejection.code shouldBe "abraa1b"
            }
        }

        it("rejects a code with hyphens") {
            val report = importer.import(listOf(row(productCode = "ABRA-A1B")))

            report.rejected.single()
                .shouldBeInstanceOf<RowRejection.InvalidProductCode>()
        }

        it("rejects a completely empty row") {
            val report = importer.import(
                listOf(CatalogRow(rowNumber = 1399, "", "", "", "", "")),
            )

            report.rejected.single().shouldBeInstanceOf<RowRejection.BlankRow>()
        }

        it("rejects an unknown unit of measure") {
            // Es la defensa contra el clásico KG / Kg / KGS como tres unidades
            // distintas: si no está en el catálogo, se rechaza.
            val report = importer.import(listOf(row(unitMeasure = "KGS")))

            val rejection = report.rejected.single()
                .shouldBeInstanceOf<RowRejection.UnknownUnitMeasure>()
            rejection.unit shouldBe "KGS"
        }

        it("accepts the unit in lowercase because it is not ambiguous") {
            // A diferencia del código, la unidad se normaliza: 'pza' es
            // inequívocamente la misma unidad.
            val report = importer.import(listOf(row(unitMeasure = "pza")))

            report.acceptedCount shouldBe 1
            report.accepted.single().unitMeasure shouldBe "PZA"
        }

        it("rejects non-numeric stock") {
            val report = importer.import(listOf(row(stock = "N/A")))

            report.rejected.single().shouldBeInstanceOf<RowRejection.InvalidStock>()
        }

        it("rejects negative stock: it is impossible in a physical inventory") {
            val report = importer.import(listOf(row(stock = "-50")))

            val rejection = report.rejected.single().shouldBeInstanceOf<RowRejection.InvalidStock>()
            withClue("El motivo debe explicar por qué, no solo que falló") {
                (rejection.detail.contains("negative")) shouldBe true
            }
        }

        it("rejects a product without a name") {
            val report = importer.import(listOf(row(name = "   ")))

            report.rejected.single().shouldBeInstanceOf<RowRejection.BlankName>()
        }

        it("rejects a duplicate code, stating where it first appeared") {
            val report = importer.import(
                listOf(
                    row(rowNumber = 2, productCode = "ABRAA1B"),
                    row(rowNumber = 50, productCode = "ABRAA1B"),
                ),
            )

            withClue("Se acepta la primera aparición") { report.acceptedCount shouldBe 1 }

            val rejection = report.rejected.single()
                .shouldBeInstanceOf<RowRejection.DuplicateCode>()
            withClue("El rechazo debe decir en qué fila se vio primero, para poder compararlas") {
                rejection.firstSeenAtRow shouldBe 2
                rejection.rowNumber shouldBe 50
            }
        }

        it("a rejected row does not stop the others from being processed") {
            val report = importer.import(
                listOf(
                    row(rowNumber = 2, productCode = "ABRAA1B"),
                    row(rowNumber = 3, productCode = "mal"),
                    row(rowNumber = 4, productCode = "ABRAA1E", stock = "-1"),
                    row(rowNumber = 5, productCode = "ABRAAL1B"),
                ),
            )

            withClue("Dos filas válidas deben aceptarse pese a las dos inválidas") {
                report.acceptedCount shouldBe 2
                report.rejectedCount shouldBe 2
                report.totalRows shouldBe 4
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("CSV parser: the real file has quotes and commas inside fields") {

        it("handles the escaped double quotes of the real file") {
            // 876 nombres del archivo llevan comillas dobles: 'ABRAZADERA 1-5/8"'.
            // Un split ingenuo por comas rompería esas filas.
            val content = listOf(
                "\$product_id,product_code,name,unit_measure,stock",
                "1,ABRAA1B,\"ABRAZADERA DE ARRANQUE 1-5/8\"\"\",PZA,5193"
            ).joinToString("\n")

            val rows = importer.parseCsv(content)

            rows.size shouldBe 1
            withClue("La comilla doble debe conservarse en el nombre") {
                rows.single().name shouldBe "ABRAZADERA DE ARRANQUE 1-5/8\""
            }
            rows.single().stock shouldBe "5193"
        }

        it("handles commas inside a quoted field") {
            val content = listOf(
                "\$product_id,product_code,name,unit_measure,stock",
                "9,ADORNR405N,\"ADORNO COLONIAL R405, ARQUITO (#7050)\",PZA,932"
            ).joinToString("\n")

            val rows = importer.parseCsv(content)

            withClue("La coma dentro de comillas NO debe partir el campo") {
                rows.single().name shouldBe "ADORNO COLONIAL R405, ARQUITO (#7050)"
            }
            rows.single().stock shouldBe "932"
        }

        it("the row numbering starts at 2 because 1 is the header") {
            val content = listOf(
                "\$product_id,product_code,name,unit_measure,stock",
                "1,ABRAA1B,PRIMERO,PZA,1",
                "2,ABRAA1E,SEGUNDO,PZA,2"
            ).joinToString("\n")

            val rows = importer.parseCsv(content)

            withClue("Los números de fila deben corresponder al archivo, para poder localizar errores") {
                rows[0].rowNumber shouldBe 2
                rows[1].rowNumber shouldBe 3
            }
        }

        it("discards the empty lines at the end of the file") {
            val content = listOf(
                "\$product_id,product_code,name,unit_measure,stock",
                "1,ABRAA1B,PRODUCTO,PZA,10"
            ).joinToString("\n")

            importer.parseCsv(content).size shouldBe 1
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the reconciliation report detects what a silent import would hide") {

        it("detects that products were lost") {
            val report = importer.import(listOf(row()))

            val discrepancies = report.matchesExpectations(
                expectedProducts = 1398,
                expectedTotalStock = Quantity.of(10_914_130),
            )

            withClue("Debe reportar la diferencia de productos") {
                (discrepancies.any { it.contains("1398") }) shouldBe true
            }
            withClue("Y también la de unidades") {
                (discrepancies.any { it.contains("units") }) shouldBe true
            }
        }

        it("the rendered report includes the reconciliation and the rejected rows") {
            val report = importer.import(
                listOf(
                    row(productCode = "ABRAA1B", stock = "100"),
                    row(rowNumber = 3, productCode = "mal"),
                ),
            )

            val text = report.render()

            withClue("El informe debe ser legible para alguien de negocio") {
                text shouldContain "Catalog import report"
                text shouldContain "Accepted products"
                text shouldContain "Total stock"
                text shouldContain "Rejected rows"
            }
            withClue("Y debe detallar qué fila falló y por qué") {
                text shouldContain "fila 3"
            }
        }

        it("the persisted data is the file's, untransformed") {
            val report = importer.import(
                listOf(
                    row(
                        rowNumber = 2, externalId = "1", productCode = "ABRAA1B",
                        name = "ABRAZADERA DE ARRANQUE 1-5/8\"", unitMeasure = "PZA", stock = "5193",
                    ),
                ),
            )

            val product = report.accepted.single()
            withClue("El código se valida pero no se altera") {
                product.code.value shouldBe "ABRAA1B"
            }
            withClue("El identificador externo del ERP se conserva") {
                product.externalId shouldBe "1"
            }
            withClue("Las existencias se convierten exactamente") {
                product.stock shouldBe Quantity.of(5193)
            }
        }

        it("a blank externalId is stored as null, not as an empty string") {
            val report = importer.import(listOf(row(externalId = "   ")))

            report.accepted.single().externalId shouldBe null
        }
    }
})
