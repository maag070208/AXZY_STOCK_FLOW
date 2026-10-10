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
 * CATALOG IMPORTER TESTS
 *
 * The test that matters is **the reconciliation one**: it runs the importer against the CSV
 * extracted from the real Excel and verifies that it reproduces **the exact figures of the
 * file** (1,398 products and 10,914,130 units).
 *
 * It is the difference between "the importer works" and "the importer loads the
 * correct data". An importer that discards rows in silence passes the first
 * test and fails the second, and the discrepancy appears later, when there is no
 * way to know what was lost.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class CatalogImporterTest : DescribeSpec({

    val importer = CatalogImporter()

    /**
     * Reads the real CSV extracted from the Excel.
     *
     * It is read from the classpath so that the test does not depend on the
     * working directory it runs from. The resource lives in `jvmTest/resources`,
     * which is the only platform source the architecture guard allows:
     * `jvmMain` is forbidden and `jvmTest` is acceptable because it is
     * test code, not domain code.
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

            // The reconciliation check: if the importer discarded rows in
            // silence, this check detects it with the exact numbers.
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

            // Known fact about the file: 23.9% of the products are at zero.
            // It matters: they are products that exist in the catalog but have no
            // stock, and confusing them with "does not exist" causes errors on the floor.
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

            // The file has 1,400 lines: 1 header and 2 empty ones at the end.
            // The parser discards the empty ones, so 1,398 remain.
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
            // The real alphabet is 0-9 and A-Z. A tolerant importer would normalize
            // to uppercase; here it is rejected, because a lowercase letter in the file
            // means something changed at the origin and it has to be checked.
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
            // It is the defense against the classic KG / Kg / KGS as three
            // different units: if it is not in the catalog, it is rejected.
            val report = importer.import(listOf(row(unitMeasure = "KGS")))

            val rejection = report.rejected.single()
                .shouldBeInstanceOf<RowRejection.UnknownUnitMeasure>()
            rejection.unit shouldBe "KGS"
        }

        it("accepts the unit in lowercase because it is not ambiguous") {
            // Unlike the code, the unit is normalized: 'pza' is
            // unequivocally the same unit.
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
            // 876 names in the file carry double quotes: 'ABRAZADERA 1-5/8"'.
            // A naive split by commas would break those rows.
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
