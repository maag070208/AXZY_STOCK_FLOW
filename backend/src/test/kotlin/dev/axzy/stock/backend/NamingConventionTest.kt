package dev.axzy.stock.backend

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * GUARDIA: LOS IDENTIFICADORES VAN EN INGLÉS
 *
 * **Existe porque el estándar se incumplió y nadie lo notó.** El usuario lo exigió:
 *
 *   «tenemos que seguir estándares como que todo debe de ir en inglés: variables,
 *    métodos, constantes, tablas, propiedades. Solo comentarios en español.»
 *
 * Se hizo un renombrado de ~200 identificadores... y **dos rondas después yo mismo
 * escribí `val ipValida` y `fun pareceIp`** en un archivo nuevo. El usuario lo encontró
 * leyendo el código, no un test.
 *
 * Es exactamente la clase de fallo que este proyecto lleva toda la sesión cerrando:
 * **una regla escrita que nada hace cumplir acaba incumpliéndose**, y el que la
 * incumple suele ser quien la escribió, porque ya no la está mirando.
 *
 * **NO comprueba los comentarios** (van en español a propósito) ni las **cadenas**
 * (los mensajes de error **los lee el operador** y se quedan en español). Solo los
 * nombres.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class NamingConventionTest : DescribeSpec({

    describe("los identificadores del código están en inglés") {

        it("ningún `val`, `var`, `fun` ni `class` usa una palabra española") {
            val raices = listOf(
                File("../backend/src/main/kotlin"),
                File("src/main/kotlin"),
                File("../stock-domain/src/commonMain/kotlin"),
            )
                .filter { it.exists() }
                // Las mismas rutas llegan por dos caminos según el directorio de
                // trabajo, y sin deduplicar cada violación salía dos veces: un informe
                // con duplicados hace dudar de si hay dos problemas o uno.
                .distinctBy { it.canonicalPath }

            // Si no se encuentra ninguna raíz, el test MIENTE: pasaría sin comprobar
            // nada. Es el mismo falso verde que Gradle da con 0 tests ejecutados.
            raices.shouldBeNotEmpty("No se encontró ninguna raíz de código que revisar")

            val violaciones = raices.flatMap { raiz -> revisar(raiz) }

            if (violaciones.isNotEmpty()) {
                throw AssertionError(
                    buildString {
                        appendLine("Identificadores en español encontrados:")
                        violaciones.forEach { appendLine("  $it") }
                        appendLine()
                        appendLine("El estándar es: identificadores en INGLÉS, comentarios en")
                        appendLine("español. Los mensajes de error y los `displayName` NO se")
                        appendLine("traducen: los lee el operador.")
                    },
                )
            }
        }
    }
})

/**
 * Las palabras españolas que NO pueden aparecer en un identificador.
 *
 * **Lista corta y deliberada, no un diccionario.** Solo recoge los términos que
 * aparecen de verdad en este dominio —los que ya se usaron y los que es fácil volver a
 * usar por inercia—. Una lista exhaustiva daría falsos positivos con palabras que son
 * iguales en los dos idiomas (`total`, `error`, `nota`/`note`) y el equipo acabaría
 * desactivando la guardia, que es peor que no tenerla.
 */
private val PALABRAS_ESPANOLAS = listOf(
    // Sustantivos del dominio que ya se usaron en español
    "cantidad", "motivo", "nota", "ubicacion", "producto", "factura", "usuario",
    "bodega", "unidades", "saldo", "conexion", "existencia", "solicitud", "resultado",
    "linea", "pedido", "etiqueta", "traslado", "ajuste", "movimiento", "almacen",
    // Adjetivos y auxiliares que delatan una frase en español
    "valida", "valido", "nuevo", "nueva", "anterior", "siguiente", "parece", "nombre",
    // ⚠️ `decision` NO está en la lista, y se comprobó: es IDÉNTICA en inglés y en
    // español. Incluirla daba falsos positivos sobre `AccessDecision` y sobre la
    // columna `decision` de `audit_log`, que están bien escritas.
    //
    // **Es exactamente el riesgo que advierte el comentario de arriba, y lo cometí al
    // escribir la lista.** Una guardia con falsos positivos se acaba desactivando, y
    // entonces deja de vigilar lo que sí importa.
    "correlacion", "permiso", "estado", "fecha", "hora",
    // Verbos, que es como suelen aparecer en un `fun`
    "crear", "aprobar", "rechazar", "capturar", "ajustar", "ubicar", "mover",
    "buscar", "leer", "escribir", "contar", "calcular", "obtener", "listar",
    "validar", "comprobar", "resolver", "registrar", "actualizar", "eliminar",
)

/** Los identificadores declarados en un archivo, sin comentarios ni cadenas. */
private fun revisar(raiz: File): List<String> =
    raiz.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { archivo ->
            archivo.readLines().mapIndexedNotNull { indice, linea ->
                val limpia = sinComentarioNiCadena(linea)
                val nombre = identificadorDe(limpia) ?: return@mapIndexedNotNull null
                val enEspanol = PALABRAS_ESPANOLAS.firstOrNull { palabra ->
                    // Se compara el nombre COMPLETO y cada segmento camelCase, para
                    // cazar `ipValida` (segmento `valida`) y no solo `valida` a secas.
                    val segmentos = nombre.split(Regex("(?=[A-Z])|_")).map { it.lowercase() }
                    segmentos.any { it == palabra } || nombre.lowercase() == palabra
                }
                if (enEspanol == null) null
                else "${archivo.name}:${indice + 1}  «$nombre»  (contiene «$enEspanol»)"
            }.asSequence()
        }
        .toList()

/**
 * Quita el comentario y el contenido de las cadenas.
 *
 * **Sin esto la guardia sería inservible**: los comentarios van en español por
 * decisión, y los mensajes de error también. Se marca el inicio de cadena y se corta
 * ahí, que es suficiente para un análisis línea a línea.
 */
private fun sinComentarioNiCadena(linea: String): String {
    val sinComentario = when {
        linea.trimStart().startsWith("//") -> return ""
        linea.trimStart().startsWith("*") -> return ""
        else -> linea.substringBefore("//")
    }
    return sinComentario.substringBefore('"').substringBefore("'")
}

/**
 * El identificador que declara la línea, si declara alguno.
 *
 * Se acepta `private`, `internal`, `override`, `suspend` y las anotaciones delante:
 * un `private val ipValida` es tan identificador como un `val ipValida`.
 */
private fun identificadorDe(linea: String): String? {
    val patron = Regex(
        """(?:^|\s)(?:private\s+|internal\s+|public\s+|override\s+|suspend\s+|const\s+|lateinit\s+)*""" +
            """(?:val|var|fun|class|object)\s+([a-zA-Z_][a-zA-Z0-9_]*)""",
    )
    return patron.find(linea.trim())?.groupValues?.get(1)
}

/** Extensión local para que el mensaje del fallo sea el de kotest. */
private fun List<*>.shouldBeNotEmpty(mensaje: String) {
    if (isEmpty()) throw AssertionError(mensaje)
}
