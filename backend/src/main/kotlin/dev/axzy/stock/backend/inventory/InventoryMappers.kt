package dev.axzy.stock.backend.inventory

import java.sql.ResultSet

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MAPPERS DE INVENTARIO — de fila de base de datos a DTO
 *
 * **POR QUÉ EXISTEN.** Antes, la construcción del DTO estaba **dentro del bucle de
 * lectura de la consulta**, mezclada con el `WHERE`, el `LIMIT` y el `ORDER BY`:
 *
 *     connection.prepareStatement(sqlConFiltros).use { st ->
 *         st.executeQuery().use { rs ->
 *             while (rs.next()) {
 *                 add(KardexDto(id = rs.getLong("id"), /* 15 campos más */))
 *             }
 *         }
 *     }
 *
 * Para saber qué forma tiene la respuesta había que leer el método entero —que además
 * paginaba, filtraba y armaba SQL—. Y si cambiaba una columna, había que encontrarla en
 * todos los sitios donde se mapeaba lo mismo.
 *
 * ---
 *
 * ## ⚠️ LA TRAMPA QUE EL COMPILADOR **NO** DETECTA
 *
 * Un mapper lee la columna **por su nombre en una cadena**:
 *
 *     getString("product_code")     // compila siempre
 *
 * Si el SQL dijera `AS productCode`, esta línea **seguiría compilando** y devolvería
 * `null` en silencio. **Un mapper mal escrito no rompe: miente.** Por eso la regla del
 * proyecto es comprobar los mappers con `curl` contra la respuesta real, además de con
 * tests: `tsc`/el compilador de Kotlin validan tipos, no acuerdos sobre cadenas.
 *
 * Por el mismo motivo estos tests **no se conforman con que compile**: ver
 * `InventoryMappersTest`.
 *
 * ## Por qué `internal` y no `private`
 *
 * Para poder probarlos desde el test, que vive en otro archivo. `private` los dejaría
 * inaccesibles y la única forma de probarlos sería arrancar un servidor y una base de
 * datos — que es justo lo que los mappers existen para evitar.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Convierte una fila del kardex en su DTO de respuesta.
 *
 * **Los dos nombres de ubicación llegan ya calculados, y no se leen del `ResultSet`.**
 * El nombre visible lo compone el servidor —una ubicación especial se llama `RECEPCION`,
 * no `RECEPCION-RECEPCION-RECEPCION`— y esa decisión vive en un solo sitio. Si el mapper
 * los leyera de las columnas crudas, tendría que repetir la regla, y **dos copias de una
 * regla de presentación acaban divergiendo**: el día que cambie el formato de las
 * etiquetas, el kardex lo mostraría distinto que las existencias.
 *
 * @param fromLocationDisplayName nombre visible del origen, ya compuesto. `null` si el
 *   movimiento no tiene origen (una carga inicial, por ejemplo).
 * @param toLocationDisplayName nombre visible del destino, ya compuesto.
 */
internal fun ResultSet.toKardexDto(
    fromLocationDisplayName: String?,
    toLocationDisplayName: String?,
): KardexDto = KardexDto(
    id = getLong("id"),
    movedAt = getTimestamp("moved_at").toInstant().toString(),
    productCode = getString("product_code"),
    productName = getString("product_name"),
    moveType = getString("move_type"),
    // `toPlainString()` y no `toString()`: `"5193.000"` conserva los 3 decimales del
    // contrato, mientras que `toString()` de un BigDecimal puede dar notación
    // científica (`5.193E+3`) con números grandes. Con 10,9 millones de unidades en el
    // inventario, ese caso no es hipotético.
    units = getBigDecimal("units").toPlainString(),
    quantityAfter = getBigDecimal("quantity_after").toPlainString(),
    fromLocationDisplayName = fromLocationDisplayName,
    toLocationDisplayName = toLocationDisplayName,
    container = getString("container"),
    orderNumber = getString("order_number"),
    adjustmentReason = getString("adjustment_reason"),
    adjustmentOrigin = getString("adjustment_origin"),
    // `takeIf { !wasNull() }` y no `getLong` a secas: `getLong` devuelve **0** para un
    // NULL, así que un movimiento del sistema (sin usuario) aparecería como del usuario
    // 0 — un usuario que no existe. El `wasNull()` es la única forma de distinguir
    // «cero» de «ninguno», y **hay que llamarlo inmediatamente después del `get`**.
    userId = getLong("user_id").takeIf { !wasNull() },
    userName = getString("user_name"),
    note = getString("note"),
)

/**
 * Convierte una fila de existencias por ubicación en su DTO.
 *
 * **El nombre visible de la ubicación se calcula con [locationDisplayName], y no se lee
 * de una columna.** La base guarda las tres partes por separado (`hall`, `code`,
 * `position`) y el nombre que ve el operador se compone en el servidor. Las ubicaciones
 * especiales —`RECEPCION` y `SIN-UBICAR`— tienen además las tres partes **iguales**
 * (`hall = code = position`), así que concatenarlas daría
 * `SINUBICARSINUBICARSINUBICAR`: **el nombre visible no es una concatenación, es una
 * decisión**, y por eso vive en una función y no en un `CONCAT` del SQL.
 */
internal fun ResultSet.toStockByLocationDto(): StockByLocationDto = StockByLocationDto(
    productId = getLong("product_id"),
    productCode = getString("product_code"),
    productName = getString("product_name"),
    locationId = getLong("location_id"),
    locationDisplayName = locationDisplayName(
        getString("hall"),
        getString("code"),
        getString("position"),
        getString("kind"),
    ),
    warehouseId = getLong("warehouse_id"),
    warehouseCode = getString("warehouse_code"),
    quantity = getBigDecimal("quantity").toPlainString(),
)

/**
 * Convierte una fila de `comprobar_proyecciones()` en su DTO.
 *
 * Los tres totales van como **cadena** con `toPlainString()`, igual que el resto de
 * cantidades del contrato (`CONTRATO_API.md` §1.7). Aquí importa más que en ningún otro
 * sitio: este endpoint **existe precisamente para detectar descuadres**, y devolver los
 * totales como número invitaría a que el cliente los comparara en coma flotante.
 * **Un reporte de reconciliación que redondea es peor que no tenerlo**, porque dice que
 * todo cuadra cuando la diferencia está por debajo de la precisión del `double`.
 */
internal fun ResultSet.toDivergenceDto(): DivergenceDto = DivergenceDto(
    productId = getLong("product_id"),
    productCode = getString("product_code"),
    totalEnBd = getBigDecimal("total_en_bd").toPlainString(),
    totalEsperado = getBigDecimal("total_esperado").toPlainString(),
    diferencia = getBigDecimal("diferencia").toPlainString(),
)

internal fun locationDisplayName(hall: String, code: String, position: String, kind: String): String =
    when (kind) {
        "RECEIVING" -> "RECEPCION"
        "UNASSIGNED" -> "SIN-UBICAR"
        else -> "$hall-$code-$position"
    }
