package dev.axzy.stock.domain.scanner

import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ESCÁNER DE CÓDIGOS DE BARRAS — modelo compartido
 *
 * Resuelve los **dos defectos del escáner del legacy**, y los dos son de lógica
 * pura, así que se resuelven aquí y no en cada plataforma:
 *
 *  · **M4 / ventana desalineada.** La máscara visual del legacy medía 300×100
 *    píxeles, pero la validación por `e.bounds` aceptaba solo 250×50 centrados.
 *    Resultado: **escaneos válidos descartados** y operadores repitiendo el
 *    gesto sin entender por qué.
 *
 *    La causa raíz es que había **dos definiciones** de la misma ventana, en dos
 *    archivos distintos, y nada las mantenía sincronizadas. Aquí hay una sola
 *    constante ([ScanWindow.DEFAULT]) que usan la máscara y la validación. Con
 *    una sola fuente, la desalineación es imposible.
 *
 *  · **M3 / máquina de estados rota.** El legacy usaba un `step` numérico suelto.
 *    `step` se incrementaba una vez y el handler comparaba `step === 1`, así que
 *    la rama que asigna la ubicación **era inalcanzable** hasta que un error lo
 *    devolvía a 0. El operador necesitaba un tercer escaneo.
 *
 *    Aquí el estado es un tipo sellado: **las transiciones inválidas no se
 *    pueden escribir**. No hay ningún número que comparar mal.
 *
 * **Este modelo NO accede a la cámara.** La cámara es de plataforma
 * (`expect/actual` con CameraX + ML Kit en Android y AVFoundation + Vision en
 * iOS), pero **el qué hacer con lo escaneado es idéntico** en las dos.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Rectángulo en coordenadas normalizadas (0.0 a 1.0) del fotograma.
 *
 * **Normalizado a propósito:** la cámara entrega píxeles y las pantallas tienen
 * densidades distintas. Con píxeles, la misma ventana lógica mediría cosas
 * distintas en un teléfono y en una tablet, y la validación del legacy ya
 * fallaba por trabajar con píxeles absolutos.
 */
data class ScanRect(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
) {
    init {
        require(left in 0.0..1.0) { "left debe estar entre 0 y 1: $left" }
        require(top in 0.0..1.0) { "top debe estar entre 0 y 1: $top" }
        require(width > 0.0 && left + width <= 1.0001) {
            "La ventana excede el ancho: left=$left width=$width"
        }
        require(height > 0.0 && top + height <= 1.0001) {
            "La ventana excede el alto: top=$top height=$height"
        }
    }

    val right: Double get() = left + width
    val bottom: Double get() = top + height
    val centerX: Double get() = left + width / 2
    val centerY: Double get() = top + height / 2
}

/**
 * La ventana de escaneo. **Una sola definición para la máscara y la validación.**
 *
 * El legacy tenía la máscara de 300×100 y la validación de 250×50. Aquí la
 * máscara dibuja `window` y la validación comprueba `window`: físicamente no
 * pueden discrepar.
 */
data class ScanWindow(
    /** La zona donde debe caer el código para aceptarse. */
    val window: ScanRect,
    /**
     * Margen de tolerancia alrededor de la ventana, en fracción del fotograma.
     *
     * Existe porque un operador con el pulso tembloroso no merece repetir el
     * escaneo. El legacy tenía tolerancia CERO fuera de la ventana, lo que
     * combinado con el desalineamiento hacía el escaneo frustrante.
     */
    val tolerance: Double = 0.05,
) {
    init {
        require(tolerance in 0.0..0.5) { "La tolerancia debe estar entre 0 y 0.5: $tolerance" }
    }

    /** La ventana efectiva de aceptación: la visual más la tolerancia. */
    val effective: ScanRect
        get() = ScanRect(
            left = (window.left - tolerance).coerceAtLeast(0.0),
            top = (window.top - tolerance).coerceAtLeast(0.0),
            width = (window.width + tolerance * 2).coerceAtMost(1.0 - (window.left - tolerance).coerceAtLeast(0.0)),
            height = (window.height + tolerance * 2).coerceAtMost(1.0 - (window.top - tolerance).coerceAtLeast(0.0)),
        )

    companion object {
        /**
         * La ventana por defecto: una banda horizontal centrada en el tercio
         * superior-medio del fotograma.
         *
         * Es **una constante compartida**. La máscara visual y la validación la
         * leen de aquí, así que no pueden desalinearse.
         */
        val DEFAULT = ScanWindow(
            window = ScanRect(left = 0.10, top = 0.35, width = 0.80, height = 0.20),
            tolerance = 0.05,
        )
    }
}

/**
 * Comprueba si el centro de un código detectado cae dentro de la ventana.
 *
 * **Se usa el CENTRO y no el rectángulo completo.** El legacy comprobaba el
 * origen del bounding box (`y < top + 50`), así que un código alto quedaba fuera
 * aunque estuviera visualmente centrado. El centro es lo que el operador
 * percibe como "lo puse en la raya".
 */
fun ScanWindow.contains(bounds: ScanRect): Boolean {
    val effective = effective
    val insideX = bounds.centerX in effective.left..effective.right
    val insideY = bounds.centerY in effective.top..effective.bottom
    return insideX && insideY
}

/** Qué debe hacer el escáner en cada paso. */
enum class ScanTarget(val displayName: String) {
    PRODUCT("un código de producto"),
    LOCATION("una ubicación"),
    CONTAINER("un contenedor"),
    ;

    /** Pista de contexto para mostrar al operador. */
    fun instruction(step: Int): String = "Escanea $displayName (paso $step)"
}

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LA MÁQUINA DE ESTADOS DEL ESCÁNER
 *
 * Reemplaza el `step` numérico del legacy. **Las transiciones inválidas no se
 * pueden expresar**: no existe forma de asignar una ubicación si aún no se ha
 * escaneado el producto, porque el estado no lleva el producto.
 * ═══════════════════════════════════════════════════════════════════════════
 */
sealed interface ScanState {

    /** Qué se espera a continuación, para mostrar la instrucción correcta. */
    val expected: ScanTarget

    /** Número de paso, solo para la interfaz. **Nunca para lógica.** */
    val step: Int

    /** No se ha escaneado nada todavía. */
    data object Idle : ScanState {
        override val expected = ScanTarget.PRODUCT
        override val step = 1
    }

    /** Se escaneó el producto y se esperan las unidades. */
    data class ProductScanned(
        val product: ProductCode,
        /** Unidades a mover. `null` mientras el operador no las escriba. */
        val units: Long? = null,
    ) : ScanState {
        override val expected = ScanTarget.LOCATION
        override val step = if (units == null) 2 else 3
    }

    /** Producto, unidades y ubicación de destino: listo para confirmar. */
    data class ReadyToConfirm(
        val product: ProductCode,
        val units: Long,
        val to: LocationCode,
    ) : ScanState {
        override val expected = ScanTarget.LOCATION
        override val step = 4
    }

    /** Error recuperable: se muestra el motivo y se puede reintentar. */
    data class Failed(
        val reason: String,
        /** El estado al que volver al reintentar. */
        val retryFrom: ScanState,
    ) : ScanState {
        override val expected: ScanTarget get() = retryFrom.expected
        override val step: Int get() = retryFrom.step
    }
}

/** Lo que se escaneó, ya normalizado. */
data class ScanInput(val raw: String, val bounds: ScanRect)

/** Resultado de procesar un escaneo. */
sealed interface ScanOutcome {
    data class Advanced(val state: ScanState) : ScanOutcome
    data class Rejected(val reason: String) : ScanOutcome
}

/**
 * Procesa un escaneo según el estado actual.
 *
 * **Sin `when` sobre números**: el `when` es sobre el tipo de estado, así que el
 * compilador garantiza que no falta ningún caso. Ése es el defecto M3 resuelto
 * por construcción, no por cuidado.
 */
fun ScanState.onScan(
    input: ScanInput,
    window: ScanWindow = ScanWindow.DEFAULT,
    /** Resuelve si un código corresponde a un producto real. */
    resolveProduct: (String) -> ProductCode?,
    /** Resuelve si un código corresponde a una ubicación real. */
    resolveLocation: (String) -> LocationCode?,
    /** Unidades escritas por el operador, para el paso intermedio. */
    unitsEntered: Long? = null,
): ScanOutcome {
    // Cuerpo de bloque, no de expresión: dentro de un `when` de expresión no se
    // puede usar `return`, y aquí el retorno temprano hace el flujo más legible
    // que anidar condicionales.
    return when (this) {

    is ScanState.Idle -> {
        if (!window.contains(input.bounds)) {
            return ScanOutcome.Rejected(
                "El código quedó fuera de la ventana de escaneo. Céntralo en la banda."
            )
        }
        val product = resolveProduct(input.raw)
            ?: return ScanOutcome.Rejected("'${input.raw}' no es un código de producto conocido")
        ScanOutcome.Advanced(ScanState.ProductScanned(product, unitsEntered))
    }

    is ScanState.ProductScanned -> {
        // ── La regla que el legacy no podía expresar ────────────────────────
        // Aquí el producto YA está en el estado, así que no hay forma de
        // asignar una ubicación sin haber escaneado el producto antes.
        // El `step === 1` del legacy permitía justo lo contrario.
        if (units == null && unitsEntered == null) {
            return ScanOutcome.Rejected("Escribe las unidades antes de escanear la ubicación")
        }
        if (!window.contains(input.bounds)) {
            return ScanOutcome.Rejected("La ubicación quedó fuera de la ventana de escaneo")
        }
        val location = resolveLocation(input.raw)
            ?: return ScanOutcome.Rejected("'${input.raw}' no es una ubicación conocida")

        if (location.value == Receiving) {
            return ScanOutcome.Rejected("No se puede mover mercancía a la misma ubicación de origen")
        }

        ScanOutcome.Advanced(
            ScanState.ReadyToConfirm(
                product = product,
                units = units ?: unitsEntered!!,
                to = location,
            )
        )
    }

    is ScanState.ReadyToConfirm -> ScanOutcome.Rejected(
        "Ya hay un movimiento listo para confirmar. Confírmalo o cancélalo primero."
    )

    is ScanState.Failed -> ScanOutcome.Rejected(reason)
    }
}

/** Alias para el nombre de la ubicación de recepción, para no repetir literales. */
private const val Receiving = "RECEPCION"
