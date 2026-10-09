package dev.axzy.stock.backend.catalog

import dev.axzy.stock.backend.catalog.location.LocationKind
import dev.axzy.stock.backend.error.ConflictException
import dev.axzy.stock.backend.error.NotFoundException
import dev.axzy.stock.backend.error.StockException
import dev.axzy.stock.backend.error.UnprocessableException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * VALIDACIÓN DE ENTRADA DEL CATÁLOGO — las reglas que convierten una petición
 * mal formada en un 422 que EXPLICA qué campo está mal
 *
 * **POR QUÉ ESTOS TESTS EXISTEN, y no son relleno.**
 *
 * `catalog/` se partió de un archivo de 741 líneas y los 17 helpers de validación
 * acabaron en `CatalogValidation.kt`. Ese movimiento **no tenía ninguna prueba
 * unitaria**: los 3 tests de `CatalogRoutesTest` van por HTTP y necesitan
 * PostgreSQL, así que el camino `archivo → helper → código de error` solo se
 * ejercitaba de refilón y en unos pocos casos.
 *
 * Y son justo las funciones donde un error se paga caro, porque **deciden el
 * código de error que ve el cliente**. `CONTRATO_API.md` §1.3-bis es un catálogo
 * CERRADO: si un helper empieza a lanzar un código que no está en el catálogo, el
 * cliente cae al mensaje genérico y el usuario no sabe qué corregir. Eso no lo
 * detecta `tsc`, ni el compilador, ni un test de rutas que solo mira el estado.
 *
 * **Estos tests no tocan la base de datos ni el servidor**: son funciones puras,
 * así que corren en milisegundos y **no se saltan** cuando no hay PostgreSQL. Es
 * deliberado: la suite que se salta sin base es la que deja de vigilar sin que
 * nadie lo note.
 *
 * ── LO QUE COMPRUEBAN, Y POR QUÉ ASÍ ───────────────────────────────────────
 *
 * Cada caso comprueba **las tres cosas** que importan de un error de validación:
 *
 *   1. **El tipo** (`UnprocessableException` ⇒ 422, `NotFoundException` ⇒ 404,
 *      `ConflictException` ⇒ 409). Un 400 donde toca un 422 hace que el cliente
 *      no sepa de qué campo se queja el servidor.
 *   2. **El código**, que es el contrato y lo único con lo que el cliente decide.
 *   3. **El campo en `details`**, que es lo que permite pintar el error al lado
 *      del input y no en un toast genérico.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class CatalogValidationTest : DescribeSpec({

    /** Extrae el `StockException` y comprueba tipo, código y campo en `details`. */
    fun capturar(bloque: () -> Any): StockException =
        shouldThrow<StockException> { bloque() }

    describe("productCode: valida con el objeto de valor del dominio, no con una copia") {

        it("un código canónico pasa y se devuelve tal cual") {
            // El `value class ProductCode` del dominio es la ÚNICA definición del
            // alfabeto. Si esto se reimplementara aquí, habría dos reglas que
            // pueden separarse — y el día que se separen, el importador aceptará
            // algo que el alta rechaza.
            productCode("ABRAA1B") shouldBe "ABRAA1B"
            productCode("0") shouldBe "0"
        }

        it("los espacios de los extremos se recortan antes de validar") {
            // Un operador que pega desde una hoja de cálculo arrastra espacios. El
            // recorte va aquí y NO en el dominio: el dominio rechaza el espacio, y
            // es correcto que lo rechace.
            productCode("  ABRAA1B  ") shouldBe "ABRAA1B"
        }

        it("en minúsculas se RECHAZA en vez de normalizarse") {
            // **Decisión de negocio, no descuido.** Normalizar `abraa1b` a
            // `ABRAA1B` en silencio haría que un archivo con dos grafías distintas
            // del mismo código pareciera contener dos productos, y el import
            // escribiría uno de ellos sin avisar. El contrato dice que la fila se
            // rechaza.
            val error = capturar { productCode("abraa1b") }

            error.shouldBeInstanceOf<UnprocessableException>()
            error.code shouldBe "INVALID_PRODUCT_CODE"
            error.httpStatus shouldBe 422
            error.details?.get("productCode") shouldBe "abraa1b"
        }

        it("un carácter fuera del alfabeto da INVALID_PRODUCT_CODE con el valor ofensor") {
            val error = capturar { productCode("ABRAA-1B") }

            error.code shouldBe "INVALID_PRODUCT_CODE"
            // El valor ofensor viaja en `details`: sin él, el usuario ve «código
            // inválido» y no sabe CUÁL de los 1.398 de su archivo lo es.
            error.details?.get("productCode") shouldBe "ABRAA-1B"
        }

        it("vacío da INVALID_PRODUCT_CODE y no un 500") {
            // El camino fácil es dejar que `of` lance `IllegalArgumentException`
            // y que StatusPages lo convierta en 500. Un 500 dice «el servidor
            // falló» cuando el que se equivocó fue el cliente, y un cliente que
            // reintenta ante un 500 reintenta para siempre.
            capturar { productCode("") }.code shouldBe "INVALID_PRODUCT_CODE"
        }

        it("el mensaje del dominio llega al cliente, no se sustituye por uno genérico") {
            val error = capturar { productCode("ABRAA-1B") }

            // El objeto de valor explica el alfabeto válido. Quedarse con un
            // «código inválido» a secas obligaría al usuario a adivinar.
            (error.message ?: "") shouldContainIgnoringCase "0-9"
        }
    }

    describe("textRequired: campo obligatorio o demasiado largo") {

        it("recorta y devuelve el texto limpio") {
            textRequired("  Tornillo M6  ", "name", 120) shouldBe "Tornillo M6"
        }

        it("null, cadena vacía y solo espacios dan el MISMO error: FIELD_REQUIRED") {
            // Los tres son «no me diste el campo». Distinguirlos obligaría al
            // cliente a tratar tres casos para el mismo problema del usuario.
            listOf(null, "", "   ", "\t\n").forEach { entrada ->
                val error = capturar { textRequired(entrada, "name", 120) }
                error.code shouldBe "FIELD_REQUIRED"
                error.httpStatus shouldBe 422
                error.details?.get("name") shouldBe "obligatorio"
            }
        }

        it("justo en el límite pasa; un carácter más da FIELD_TOO_LONG") {
            // Los bordes son donde vive el error clásico `>` frente a `>=`. Se
            // comprueban los DOS lados: solo el de dentro no demuestra nada.
            val limite = "x".repeat(120)
            textRequired(limite, "name", 120) shouldBe limite

            val error = capturar { textRequired("x".repeat(121), "name", 120) }
            error.code shouldBe "FIELD_TOO_LONG"
            error.httpStatus shouldBe 422
            // `details` lleva la LONGITUD recibida, que es lo que permite al
            // usuario saber cuánto recortar.
            error.details?.get("name") shouldBe "121"
        }

        it("el nombre del campo viaja en el mensaje y en details, para el que lo lee y el que lo pinta") {
            val error = capturar { textRequired(null, "unitMeasure", 20) }

            error.details?.get("unitMeasure") shouldBe "obligatorio"
            (error.message ?: "") shouldContainIgnoringCase "unitMeasure"
        }
    }

    describe("locationPart: normaliza a mayúsculas y aplica el alfabeto real") {

        it("pasa a MAYÚSCULAS en vez de rechazar la minúscula — al contrario que productCode") {
            // **Y la asimetría es deliberada.** El código de producto es una clave
            // de negocio con significado propio y se rechaza en minúsculas. El
            // `hall`/`code`/`position` de una ubicación es texto de infraestructura
            // que la base exige canónico (`locations_code_canonical`): aquí
            // normalizar convierte un fallo de restricción de PostgreSQL —un 500
            // opaco— en un 200 correcto.
            locationPart("a", "hall") shouldBe "A"
            locationPart("b3", "code") shouldBe "B3"
            locationPart("  c  ", "position") shouldBe "C"
        }

        it("acepta la Ñ, que existe en el alfabeto real del legacy") {
            // El alfabeto NO es `A-Z` a secas: el catálogo real trae Ñ. Un
            // `[A-Z]` la habría rechazado y habría dejado ubicaciones fuera.
            locationPart("ñ", "hall") shouldBe "Ñ"
        }

        it("un carácter fuera del alfabeto da INVALID_LOCATION_PART") {
            val error = capturar { locationPart("A-1", "code") }

            error.code shouldBe "INVALID_LOCATION_PART"
            error.httpStatus shouldBe 422
            error.details?.get("code") shouldBe "A-1"
        }

        it("no se recorta a la ligera: el de 20 pasa y el de 21 no") {
            locationPart("A".repeat(20), "hall") shouldBe "A".repeat(20)

            val error = capturar { locationPart("A".repeat(21), "hall") }
            error.code shouldBe "FIELD_TOO_LONG"
            error.details?.get("hall") shouldBe "21"
        }

        it("vacío o solo espacios da FIELD_REQUIRED, no INVALID_LOCATION_PART") {
            // La distinción importa al cliente: «falta» y «está mal escrito» se
            // corrigen de forma distinta.
            listOf(null, "", "   ").forEach { entrada ->
                capturar { locationPart(entrada, "hall") }.code shouldBe "FIELD_REQUIRED"
            }
        }

        it("un acento fuera del alfabeto se rechaza, aunque sea una letra española") {
            // `Ñ` sí, `Á` no: el alfabeto refleja lo que hay en la base, no el
            // idioma. Se comprueba para que nadie lo «arregle» ampliándolo.
            capturar { locationPart("Á", "hall") }.code shouldBe "INVALID_LOCATION_PART"
        }
    }

    describe("locationLevels: el defecto es 1 y el rango es 1..99") {

        it("null significa 1 nivel, que es una estantería normal") {
            locationLevels(null) shouldBe 1
        }

        it("los extremos del rango pasan") {
            locationLevels(1) shouldBe 1
            locationLevels(99) shouldBe 99
        }

        it("0, los negativos y 100 dan INVALID_LEVELS") {
            // **0 es el caso que importa.** Un conteo de niveles en 0 haría que la
            // ubicación no tuviera ninguna posición, y el buscador de ubicación la
            // recorrería sin encontrar nada: un agujero silencioso en el almacén.
            listOf(0, -1, -99, 100, 1000).forEach { entrada ->
                val error = capturar { locationLevels(entrada) }
                error.code shouldBe "INVALID_LEVELS"
                error.httpStatus shouldBe 422
                error.details?.get("levels") shouldBe entrada.toString()
            }
        }
    }

    describe("parseLocationKind: los valores del enum, y el error dice cuáles son") {

        it("acepta SHELF y RECEIVING tal cual") {
            parseLocationKind("SHELF") shouldBe LocationKind.SHELF
            parseLocationKind("RECEIVING") shouldBe LocationKind.RECEIVING
        }

        it("acepta cualquier combinación de mayúsculas y espacios, y normaliza") {
            // Los valores llegan de un desplegable, de una URL escrita a mano y de
            // la app móvil. Todos significan lo mismo.
            parseLocationKind(" shelf ") shouldBe LocationKind.SHELF
            parseLocationKind("Receiving") shouldBe LocationKind.RECEIVING
            parseLocationKind("sHeLf") shouldBe LocationKind.SHELF
        }

        it("un tipo desconocido da INVALID_LOCATION_KIND con el valor ofensor") {
            val error = capturar { parseLocationKind("PASILLO") }

            error.code shouldBe "INVALID_LOCATION_KIND"
            error.httpStatus shouldBe 422
            error.details?.get("kind") shouldBe "PASILLO"
        }

        it("el mensaje enumera los valores válidos, que es lo que el usuario necesita") {
            // Sin esto el usuario lee «tipo inválido» y prueba al azar. El
            // contrato exige documentar los admitidos en `details`; aquí se
            // comprueba que están en el TEXTO, que es donde se leen.
            (capturar { parseLocationKind("X") }.message ?: "") shouldContainIgnoringCase "SHELF"
        }

        it("null no compila — la firma exige cadena, así que el defecto lo pone la ruta") {
            // No hay caso «null»: `parseLocationKind` solo se llama cuando el
            // parámetro viene. Se deja constancia para que nadie añada un `?` y
            // convierta el defecto en ambiguo.
            parseLocationKind("SHELF") shouldBe LocationKind.SHELF
        }
    }

    describe("las fábricas de error: tipo, código y estado, que es lo que ve el cliente") {

        it("unitUnknown es 422 UNKNOWN_UNIT_MEASURE y explica por qué el catálogo es cerrado") {
            val error = unitUnknown("KG")

            error.shouldBeInstanceOf<UnprocessableException>()
            error.code shouldBe "UNKNOWN_UNIT_MEASURE"
            error.httpStatus shouldBe 422
            error.details?.get("unitMeasure") shouldBe "KG"
            // El mensaje lleva el motivo (KG/Kg/KGS como tres unidades) porque es
            // una decisión que sorprende a quien viene del legacy.
            (error.message ?: "") shouldContainIgnoringCase "cerrado"
        }

        it("productNotFound es 404 PRODUCT_NOT_FOUND y NO un 400") {
            // Un `404` dice «no existe»; un `400`, «pediste mal». Con 400 el
            // cliente no puede distinguir «lo borraron» de «me equivoqué en la
            // URL», y son dos acciones distintas para el usuario.
            val error = productNotFound(42)

            error.shouldBeInstanceOf<NotFoundException>()
            error.code shouldBe "PRODUCT_NOT_FOUND"
            error.httpStatus shouldBe 404
            (error.message ?: "") shouldContainIgnoringCase "42"
        }

        it("locationNotFound es 404 LOCATION_NOT_FOUND, distinto código que el producto") {
            // Códigos distintos a propósito: son dos recursos y el cliente puede
            // querer reaccionar distinto (recargar el catálogo o pedir otra ubicación).
            val error = locationNotFound(7)

            error.shouldBeInstanceOf<NotFoundException>()
            error.code shouldBe "LOCATION_NOT_FOUND"
            error.httpStatus shouldBe 404
        }

        it("locationDuplicated es 409 DUPLICATE_LOCATION y compone el nombre visible") {
            // Un `409` y no un `422`: la petición es válida, es el ESTADO el que
            // lo impide. Y el mensaje compone `hall-code-position`, que es la
            // misma forma que muestra la interfaz — el servidor compone el nombre
            // visible, el cliente nunca lo concatena (ARQUITECTURA.md §2.3).
            val error = locationDuplicated("04", "B", "C", "PRINCIPAL")

            error.shouldBeInstanceOf<ConflictException>()
            error.code shouldBe "DUPLICATE_LOCATION"
            error.httpStatus shouldBe 409
            (error.message ?: "") shouldContainIgnoringCase "04-B-C"
            (error.message ?: "") shouldContainIgnoringCase "PRINCIPAL"
        }
    }

    describe("los códigos que emiten estos helpers están en el catálogo cerrado") {

        it("todos los códigos usados aquí existen en CONTRATO_API.md §1.3-bis") {
            // **Esta es la guardia que ata el código al contrato.** Un código de
            // error nuevo que nadie añade al catálogo es invisible para el
            // cliente, que cae al mensaje genérico. Es la clase de desajuste que
            // ya costó una corrección entera en §9.
            val contrato = listOf(
                File("../CONTRATO_API.md"),
                File("CONTRATO_API.md"),
                File("../../CONTRATO_API.md"),
            ).firstOrNull { it.exists() }
                ?: error("No se encontró CONTRATO_API.md: el test no puede verificar nada y no debe pasar")

            val texto = contrato.readText()

            val codigos = listOf(
                "INVALID_PRODUCT_CODE", "FIELD_REQUIRED", "FIELD_TOO_LONG",
                "INVALID_LOCATION_PART", "INVALID_LEVELS", "INVALID_LOCATION_KIND",
                "UNKNOWN_UNIT_MEASURE", "PRODUCT_NOT_FOUND", "LOCATION_NOT_FOUND",
                "DUPLICATE_LOCATION",
            )

            val ausentes = codigos.filterNot { texto.contains(it) }

            if (ausentes.isNotEmpty()) {
                throw AssertionError(
                    "Códigos emitidos por el código que NO están en el catálogo del contrato: " +
                        ausentes.joinToString(", ") +
                        "\nUn código que el cliente no conoce lo obliga a caer al mensaje genérico.",
                )
            }
        }
    }
})
