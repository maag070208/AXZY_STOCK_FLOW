package dev.axzy.stock.domain.inventory

import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.Quantity
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CONTENIDO DE UNA UBICACIÓN — DOS IMPLEMENTACIONES, A PROPÓSITO
 *
 * Aquí conviven **dos formas de calcular cuánto hay en una ubicación**:
 *
 *  · [LocationContent.direct]  — el contenido es una **consulta directa** al
 *    saldo de la ubicación. Es el modelo del sistema nuevo.
 *
 *  · [LocationContent.legacyAlgorithm] — reproduce **el algoritmo del legacy**
 *    (`getLocationContent`), con su semántica histórica: si el último
 *    movimiento es un ajuste, el total es ese ajuste; si no, suma los `LOCATION`
 *    y resta los `OUT` desde el último ajuste.
 *
 * **¿Por qué mantener el algoritmo viejo si está mal?** Porque el plan exige
 * una decisión con datos, no con intuición (hallazgo R-67):
 *
 *   > *"Se decide con datos delante y se congela con tests de caracterización
 *   > antes de tocar."*
 *
 * El algoritmo nuevo depende de que `LOCATION` **no** reste del total, y el
 * viejo depende de que **sí** reste. Si simplificáramos a ciegas, los reportes
 * históricos cambiarían sin que nadie supiera por qué.
 *
 * Con las dos implementaciones se puede ejecutar [LocationContentComparison],
 * que **cuantifica la diferencia** sobre datos reales y la explica. Cuando la
 * diferencia sea toda atribuible al bug del legacy, se simplifica con
 * tranquilidad; si no, hay una regla de negocio que no vimos.
 * ═══════════════════════════════════════════════════════════════════════════
 */
object LocationContent {

    /**
     * **El modelo del sistema nuevo.**
     *
     * El contenido de una ubicación es simplemente lo que hay ahí. No hay
     * cálculo: el saldo ya está en el libro, mantenido por las transacciones.
     *
     * Es correcto por construcción, y por eso es O(1): no recorre el ledger.
     */
    fun direct(
        ledger: InventoryLedger,
        productCode: String,
        location: LocationCode,
    ): Quantity = ledger.quantityAt(productCode, location)

    /**
     * **El algoritmo del legacy**, reproducido tal cual.
     *
     * Para qué sirve: para comparar. Su salida permite medir cuánto se desvía
     * el sistema viejo del nuevo, y esa cifra es el insumo de la decisión R-67
     * y del informe de discrepancias de la migración (Fase 7).
     *
     * Reglas que reproduce, en el mismo orden que el original:
     *
     *  1. Se recorren las entradas de la ubicación en orden **descendente** y
     *     se toma la **última** del producto: los movimientos anteriores del
     *     mismo producto se ignoran.
     *  2. Si esa última entrada es un `ADJUSTMENT`, el total es **su cantidad**.
     *  3. Si no, se busca el último `ADJUSTMENT` del producto en esa ubicación y:
     *     · se **suman** los `LOCATION` posteriores a ese ajuste (incluido él),
     *     · se **restan** los `OUT` posteriores.
     *  4. Si no hay ningún ajuste, se suman **todos** los `LOCATION` y se restan
     *     **todos** los `OUT`.
     *  5. Se descartan los totales menores o iguales a cero.
     *
     * **El defecto está en el paso 4 y en la definición de `LOCATION`:** el
     * algoritmo trata la ubicación como resultado de sus propios movimientos,
     * en lugar de leer el saldo. Combinado con que en el legacy `LOCATION`
     * restaba del total y además mutaba la entrada de recepción, producía el
     * descuadre acumulado.
     */
    fun legacyAlgorithm(
        ledger: InventoryLedger,
        productCode: String,
        location: LocationCode,
    ): Quantity {
        val entradasDeLaUbicacion = ledger.entries
            .filter { it.productCode == productCode && (it.to == location || it.from == location) }

        if (entradasDeLaUbicacion.isEmpty()) return Quantity.ZERO

        // Paso 1: la última entrada del producto en esa ubicación.
        val ultima = entradasDeLaUbicacion.last()

        // Paso 2: si la última es un ajuste, el total es ese ajuste.
        if (ultima.type == MovementType.ADJUSTMENT_IN || ultima.type == MovementType.ADJUSTMENT_OUT) {
            return totalDelAjuste(ultima)
        }

        // Paso 3: buscar el último ajuste anterior.
        val ultimoAjuste = entradasDeLaUbicacion.lastOrNull {
            it.type == MovementType.ADJUSTMENT_IN || it.type == MovementType.ADJUSTMENT_OUT
        }

        val desdeSecuencia = ultimoAjuste?.sequence ?: 0L

        // Paso 4: sumar LOCATION y restar OUT desde el ajuste (o desde el inicio).
        val suma = entradasDeLaUbicacion
            .filter { it.sequence >= desdeSecuencia }
            .filter {
                it.type == MovementType.LOCATE ||
                    it.type == MovementType.MOVE_WITHIN_WAREHOUSE ||
                    it.type == MovementType.TRANSFER_IN ||
                    it.type == MovementType.RECEIPT_IN ||
                    it.type == MovementType.INITIAL
            }
            .sumOf { it.quantity.value }

        val resta = entradasDeLaUbicacion
            .filter { it.sequence >= desdeSecuencia }
            .filter {
                it.type == MovementType.FULFILLMENT_OUT ||
                    it.type == MovementType.ADJUSTMENT_OUT ||
                    it.type == MovementType.TRANSFER_OUT
            }
            .sumOf { it.quantity.value }

        val total = Quantity.ofScaled(suma - resta)

        // Paso 5: se descartan los totales <= 0.
        return if (total.isPositive) total else Quantity.ZERO
    }

    private fun totalDelAjuste(entry: LedgerEntry): Quantity =
        if (entry.type == MovementType.ADJUSTMENT_OUT) Quantity.ZERO else entry.quantity
}

/**
 * Resultado de comparar los dos métodos sobre una ubicación concreta.
 * Es el insumo del informe de discrepancias de la migración (Fase 7).
 */
data class ContentDivergence(
    val productCode: String,
    val location: LocationCode,
    /** Lo que dice el modelo nuevo (el saldo real). */
    val modelSays: Quantity,
    /** Lo que diría el algoritmo del legacy. */
    val legacySays: Quantity,
) {
    val difference: Quantity get() = modelSays - legacySays
    val isNegativeDifference: Boolean get() = difference.isNegative
    val absoluteDifference: Long get() = abs(difference.value)

    /**
     * Indica si la divergencia se explica **por el bug del doble descuento**.
     *
     * Cuando el legacy ubicaba mercancía, descontaba del total. Por eso su
     * algoritmo tiende a reportar **menos** de lo que hay, y la diferencia
     * debería ser positiva (el modelo nuevo ve más stock). Una divergencia
     * **negativa** no se explica por el bug: apunta a otra causa y merece
     * revisión manual.
     */
    val explainedByKnownBug: Boolean
        get() = difference.isPositive || difference.isZero
}

/**
 * Compara los dos métodos sobre todo un libro y devuelve las divergencias.
 *
 * **Cómo se usa en la migración:** se carga un histórico real del legacy en el
 * modelo, se corre esto, y el resultado es el informe que se presenta al
 * negocio antes de cortar. Si todas las divergencias son positivas, el
 * descuadre es el bug conocido y la reconciliación es directa. Si hay
 * negativas, hay que investigar antes de tocar nada.
 */
object LocationContentComparison {

    fun compare(ledger: InventoryLedger): List<ContentDivergence> =
        ledger.allBalances()
            .filter { it.quantity.isPositive }
            .map { saldo ->
                ContentDivergence(
                    productCode = saldo.productCode,
                    location = saldo.location,
                    modelSays = LocationContent.direct(ledger, saldo.productCode, saldo.location),
                    legacySays = LocationContent.legacyAlgorithm(
                        ledger, saldo.productCode, saldo.location,
                    ),
                )
            }

    /** Solo las ubicaciones donde los dos métodos discrepan. */
    fun onlyDivergent(ledger: InventoryLedger): List<ContentDivergence> =
        compare(ledger).filter { it.difference.value != 0L }

    /**
     * Resumen para el informe. Devuelve un texto legible, no una estructura:
     * el objetivo es que alguien del negocio pueda leerlo y decidir.
     */
    fun report(ledger: InventoryLedger): String {
        val divergencias = onlyDivergent(ledger)
        if (divergencias.isEmpty()) {
            return "Sin divergencias: los dos métodos coinciden en las " +
                "${compare(ledger).size} ubicaciones con stock."
        }

        val explicadas = divergencias.count { it.explainedByKnownBug }
        val sinExplicar = divergencias.filterNot { it.explainedByKnownBug }
        val descuadreTotal = divergencias.sumOf { it.absoluteDifference }

        return buildString {
            appendLine("Informe de divergencias de contenido por ubicación")
            appendLine("─────────────────────────────────────────────────────")
            appendLine("Ubicaciones con discrepancia: ${divergencias.size}")
            appendLine("  · Explicadas por el bug conocido (el modelo ve MÁS): $explicadas")
            appendLine("  · Sin explicar (el modelo ve MENOS): ${sinExplicar.size}")
            appendLine("Descuadre absoluto acumulado: ${Quantity.ofScaled(descuadreTotal)} unidades")
            if (sinExplicar.isNotEmpty()) {
                appendLine()
                appendLine("⚠️  Las siguientes NO se explican por el bug conocido y requieren")
                appendLine("    revisión manual antes de simplificar el algoritmo:")
                sinExplicar.take(20).forEach {
                    appendLine(
                        "    · ${it.productCode} en ${it.location.value}: " +
                            "modelo=${it.modelSays} legacy=${it.legacySays}"
                    )
                }
                if (sinExplicar.size > 20) {
                    appendLine("    … y ${sinExplicar.size - 20} más")
                }
            }
        }
    }
}
