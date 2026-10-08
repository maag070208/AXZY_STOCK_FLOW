package dev.axzy.stock.backend.routes

import dev.axzy.stock.backend.catalog.Page
import dev.axzy.stock.backend.catalog.PageRequest
import dev.axzy.stock.backend.catalog.aRespuesta
import dev.axzy.stock.backend.inventory.InventoryService
import dev.axzy.stock.backend.security.PermissionDecision
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.inventory.AdjustmentDirection
import dev.axzy.stock.domain.inventory.AdjustmentOrigin
import dev.axzy.stock.domain.inventory.AdjustmentReason
import dev.axzy.stock.domain.inventory.AdjustmentRequest
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * RUTAS DE INVENTARIO
 *
 * Los seis endpoints de `CONTRATO_API.md` §4 y §9.
 *
 * **Ninguna ruta mueve stock por su cuenta.** Todas delegan en [InventoryService],
 * que a su vez pasa por el motor SQL. Es lo que garantiza que las tres cosas que
 * componen un movimiento —ajustar ubicaciones, recalcular el total y escribir el
 * kardex— ocurran siempre juntas y en una transacción.
 *
 * El kardex es **append-only**: no hay `PUT` ni `DELETE`. Las correcciones son
 * movimientos nuevos, y esa restricción se expresa no escribiendo esas rutas.
 * ═══════════════════════════════════════════════════════════════════════════
 */

@Serializable
data class AjusteRequest(
    val productCode: String,
    val locationCode: String,
    /** Número decimal con 3 decimales. La dirección indica si suma o resta. */
    val quantity: Double,
    /** `INCREASE` o `DECREASE`. */
    val direction: String,
    val reason: String,
    val origin: String,
    val note: String? = null,
    /**
     * Quién aprueba, si el ajuste lo requiere.
     *
     * Va en el cuerpo y no se deduce del token porque **tiene que ser otra
     * persona**: el dominio rechaza que coincida con quien solicita, y eso es la
     * segregación de funciones.
     */
    val approverId: Long? = null,
)

@Serializable
data class TrasladoRequest(
    val productCode: String,
    val fromLocationCode: String,
    val toLocationCode: String,
    val quantity: Double,
)

@Serializable
data class MovimientoResponse(
    val kardexIds: List<Long>,
    val status: String,
    val total: String,
    val message: String? = null,
)

fun Route.inventoryRoutes(
    servicio: InventoryService,
    permissionChecker: PermissionChecker,
) {

    /** Comprueba el permiso y corta con el código correcto si no lo tiene. */
    suspend fun exigir(
        call: io.ktor.server.application.ApplicationCall,
        permiso: String,
    ) {
        when (val decision = permissionChecker.comprobar(call, permiso)) {
            is PermissionDecision.Permitido -> Unit
            is PermissionDecision.Denegado ->
                throw dev.axzy.stock.backend.error.ForbiddenException(decision.code, decision.message)
        }
    }

    /** Traduce la cantidad del contrato (3 decimales) a la del dominio. */
    fun cantidad(valor: Double): Quantity =
        Quantity.ofScaled(java.math.BigDecimal.valueOf(valor).movePointRight(3).toLong())

    // ═══════════════════════════════════════════════════════════════════════
    // POST /v1/inventory/adjustments — la regla única
    // ═══════════════════════════════════════════════════════════════════════
    post("/v1/inventory/adjustments") {
        val cuerpo = call.receive<AjusteRequest>()

        val direccion = runCatching { AdjustmentDirection.valueOf(cuerpo.direction.uppercase()) }
            .getOrNull()
            ?: throw dev.axzy.stock.backend.error.BadRequestException(
                "INVALID_PARAMETER",
                "La dirección debe ser INCREASE o DECREASE.",
                mapOf("allowed" to "INCREASE, DECREASE"),
            )

        val motivo = runCatching { AdjustmentReason.valueOf(cuerpo.reason.uppercase()) }
            .getOrNull()
            ?: throw dev.axzy.stock.backend.error.BadRequestException(
                "INVALID_PARAMETER",
                "El motivo no es válido.",
                mapOf("allowed" to AdjustmentReason.entries.joinToString(", ") { it.name }),
            )

        val origen = runCatching { AdjustmentOrigin.valueOf(cuerpo.origin.uppercase()) }
            .getOrNull()
            ?: throw dev.axzy.stock.backend.error.BadRequestException(
                "INVALID_PARAMETER",
                "El origen no es válido.",
                mapOf("allowed" to AdjustmentOrigin.entries.joinToString(", ") { it.name }),
            )

        // El permiso depende de la DIRECCIÓN y del MOTIVO: aumentar no es lo mismo
        // que mermar, y un robo no es lo mismo que un error de conteo. Un permiso
        // genérico de «ajustar» daría a cualquiera la capacidad de tapar un robo.
        val permiso = permisoDelAjuste(direccion, motivo)
        exigir(call, permiso)

        val producto = ProductCode.validate(cuerpo.productCode).getOrNull()
            ?: throw dev.axzy.stock.backend.error.BadRequestException(
                "INVALID_PRODUCT_CODE",
                "El código de producto '${cuerpo.productCode}' no es válido.",
            )

        val ubicacion = LocationCode.validate(cuerpo.locationCode).getOrNull()
            ?: throw dev.axzy.stock.backend.error.BadRequestException(
                "INVALID_LOCATION_CODE",
                "La ubicación '${cuerpo.locationCode}' no es válida.",
            )

        // El usuario sale del token; el aprobador del CUERPO, porque tiene que ser
        // otra persona y el dominio lo comprueba.
        val solicitante = call.usuarioDelToken() ?: throw dev.axzy.stock.backend.error.UnauthorizedException()

        val solicitud = when (
            val resultado = AdjustmentRequest.create(
                productCode = producto,
                locationCode = ubicacion,
                quantity = cantidad(cuerpo.quantity),
                direction = direccion,
                reason = motivo,
                origin = origen,
                note = cuerpo.note,
                requestedBy = solicitante,
                // **La clave se valida aquí y no se deja al CAST de PostgreSQL.**
                // `?::uuid` con un valor que no es UUID lanza una excepción de
                // PostgreSQL que sale como **500 INTERNAL_ERROR**, cuando el problema
                // es del cliente y merece un **400**. Lo encontré pasando
                // `Idempotency-Key: v-SINUBICAR-...` en una prueba mía.
                //
                // Un 500 es una mentira: dice «el servidor falló» cuando el servidor
                // está bien y el que se equivocó fue quien llamó. Y en los registros
                // deja un incidente que no existe.
                idempotencyKey = claveDeIdempotencia(call),
            )
        ) {
            is Either.Left -> throw dev.axzy.stock.backend.error.UnprocessableException(
                resultado.error.code,
                resultado.error.message,
            )
            is Either.Right -> resultado.value
        }

        val resultado = servicio.ajustar(
            solicitud = solicitud,
            aprobadorId = cuerpo.approverId,
            ip = call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim(),
        )

        val codigo = if (resultado.estado == "PENDING_APPROVAL") {
            HttpStatusCode.Accepted
        } else {
            HttpStatusCode.Created
        }

        call.respond(
            codigo,
            MovimientoResponse(
                kardexIds = resultado.movimientos,
                status = resultado.estado,
                total = resultado.total.unidadesTexto(),
                message = resultado.motivo,
            ),
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // POST /v1/inventory/locate y /v1/inventory/move
    // ═══════════════════════════════════════════════════════════════════════
    post("/v1/inventory/locate") {
        // Mismo permiso que ubicar una línea de factura: es el mismo acto físico.
        exigir(call, "invoice.locate")
        manejarTraslado(call, servicio, ::cantidad)
    }

    post("/v1/inventory/move") {
        // Mover entre estantes es una operación distinta de ubicar: ubicar saca de
        // recepción, mover reorganiza. El operador tiene la primera pero no
        // necesariamente la segunda.
        exigir(call, "inventory.move")
        manejarTraslado(call, servicio, ::cantidad)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /v1/inventory/stock
    // ═══════════════════════════════════════════════════════════════════════
    get("/v1/inventory/stock") {
        exigir(call, "inventory.view")

        // Los campos ordenables se declaran POR ENDPOINT: el contrato lo exige, y
        // `quantity` no está porque ordenar por saldo en la base exigiría un índice
        // que no existe para un reporte que se lee de a poco.
        val page = call.pagina(camposOrdenables = setOf("productCode"), ordenPorDefecto = "productCode")

        val items = servicio.existencias(
            productCode = call.request.queryParameters["productCode"],
            locationCode = call.request.queryParameters["locationCode"],
            soloConExistencia = call.request.queryParameters["onlyWithStock"]?.toBoolean() ?: true,
            offset = page.offset.toInt(),
            limite = page.size,
        )

        // **EL TOTAL SE CUENTA, NO SE ADIVINA.** Antes se pasaba `items.size`, así
        // que el sobre decía «50 resultados» siempre y el cliente no podía pintar
        // «1.064 existencias, 22 páginas». Lo encontró un agente al conectar la
        // pantalla de inventario.
        val total = servicio.contarExistencias(
            productCode = call.request.queryParameters["productCode"],
            locationCode = call.request.queryParameters["locationCode"],
            soloConExistencia = call.request.queryParameters["onlyWithStock"]?.toBoolean() ?: true,
        )

        call.respond(
            HttpStatusCode.OK,
            Page(items, page.page, page.size, total).aRespuesta { it },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /v1/inventory/kardex
    // ═══════════════════════════════════════════════════════════════════════
    get("/v1/inventory/kardex") {
        exigir(call, "kardex.view")

        val page = call.pagina(
            camposOrdenables = setOf("movedAt", "productCode", "moveType"),
            ordenPorDefecto = "movedAt",
        )
        val producto = call.request.queryParameters["productCode"]
        val tipo = call.request.queryParameters["moveType"]
        val desde = call.request.queryParameters["from"]?.let { runCatching { Instant.parse("${it}T00:00:00Z") }.getOrNull() }
        val hasta = call.request.queryParameters["to"]?.let { runCatching { Instant.parse("${it}T23:59:59Z") }.getOrNull() }

        val items = servicio.kardex(
            productCode = producto,
            desde = desde,
            hasta = hasta,
            tipo = tipo,
            offset = page.offset.toInt(),
            limite = page.size,
        )

        val total = servicio.contarKardex(productCode = producto, tipo = tipo, desde = desde, hasta = hasta)

        call.respond(HttpStatusCode.OK, Page(items, page.page, page.size, total).aRespuesta { it })
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /v1/reports/kardex-vs-projection — la reconciliación
    // ═══════════════════════════════════════════════════════════════════════
    get("/v1/reports/kardex-vs-projection") {
        exigir(call, "inventory.reconcile")

        val divergencias = servicio.divergencias()

        call.respond(HttpStatusCode.OK, ReporteReconciliacion(
            checkedAt = Instant.now().toString(),
            // Cero divergencias es el estado sano, y el reporte lo dice con un
            // booleano explícito en lugar de obligar a contar el arreglo.
            cuadra = divergencias.isEmpty(),
            divergences = divergencias.size,
            items = divergencias,
        ))
    }
}

@Serializable
data class ReporteReconciliacion(
    val checkedAt: String,
    val cuadra: Boolean,
    val divergences: Int,
    val items: List<dev.axzy.stock.backend.inventory.DivergenciaDto>,
)

/**
 * El permiso que exige un ajuste, según su dirección y su motivo.
 *
 * **No hay un permiso genérico de «ajustar», y es deliberado.** Aumentar el
 * inventario no es lo mismo que mermarlo, y un robo no es lo mismo que un error de
 * conteo. Un permiso único daría a cualquiera la capacidad de tapar un robo con un
 * ajuste que él mismo firma.
 */
private fun permisoDelAjuste(
    direccion: AdjustmentDirection,
    motivo: AdjustmentReason,
): String = when {
    direccion == AdjustmentDirection.INCREASE -> "inventory.adjust_increase"
    motivo == AdjustmentReason.THEFT -> "inventory.adjust_theft"
    motivo == AdjustmentReason.DAMAGE -> "inventory.adjust_damage"
    motivo == AdjustmentReason.SHORTAGE -> "inventory.adjust_shortage"
    else -> "inventory.adjust_count_error"
}

/** Cuerpo compartido por ubicar y mover. */
private suspend fun manejarTraslado(
    call: io.ktor.server.application.ApplicationCall,
    servicio: InventoryService,
    cantidad: (Double) -> Quantity,
) {
    val cuerpo = call.receive<TrasladoRequest>()

    val producto = ProductCode.validate(cuerpo.productCode).getOrNull()
        ?: throw dev.axzy.stock.backend.error.BadRequestException(
            "INVALID_PRODUCT_CODE",
            "El código de producto no es válido.",
        )

    val desde = LocationCode.validate(cuerpo.fromLocationCode).getOrNull()
        ?: throw dev.axzy.stock.backend.error.BadRequestException(
            "INVALID_LOCATION_CODE",
            "La ubicación de origen no es válida.",
        )

    val hasta = LocationCode.validate(cuerpo.toLocationCode).getOrNull()
        ?: throw dev.axzy.stock.backend.error.BadRequestException(
            "INVALID_LOCATION_CODE",
            "La ubicación de destino no es válida.",
        )

    val usuario = call.usuarioDelToken() ?: throw dev.axzy.stock.backend.error.UnauthorizedException()

    val kardexId = servicio.ubicar(
        productCode = producto,
        desde = desde,
        hasta = hasta,
        cantidad = cantidad(cuerpo.quantity),
        usuarioId = usuario,
        clave = claveDeIdempotencia(call),
    )

    call.respond(
        HttpStatusCode.Created,
        MovimientoResponse(
            kardexIds = listOf(kardexId),
            status = "APPLIED",
            // El total NO cambió: es un traslado. Se devuelve el mensaje para que
            // el cliente no crea que el movimiento no hizo nada.
            total = "",
            message = "Traslado registrado. El total del producto no cambia: es un " +
                "movimiento entre ubicaciones, no una entrada ni una salida.",
        ),
    )
}

/** La cantidad como texto con 3 decimales, que es el formato del contrato. */
private fun Quantity.unidadesTexto(): String =
    java.math.BigDecimal.valueOf(this.value).movePointLeft(3).toPlainString()

/**
 * La paginación de la petición, validada contra los campos que admite el endpoint.
 *
 * Se copia el patrón del catálogo en lugar de inventar otro: `page`, `size` y
 * `sort` se comportan igual en todos los listados, y un `size` mayor de 200 se
 * **rechaza** en vez de recortarse — recortar hace que el cliente crea que tiene
 * todos los datos.
 */
private fun io.ktor.server.application.ApplicationCall.pagina(
    camposOrdenables: Set<String>,
    ordenPorDefecto: String,
): PageRequest = PageRequest.analizar(
    page = request.queryParameters["page"],
    size = request.queryParameters["size"],
    sort = request.queryParameters["sort"],
    camposOrdenables = camposOrdenables,
    ordenPorDefecto = ordenPorDefecto,
)

/**
 * La clave de idempotencia de la petición, validada como UUID.
 *
 * `null` si no viene, que es válido: significa «no soy un reintento». Si viene y no
 * es un UUID, se rechaza con **400** en lugar de dejar que PostgreSQL lance un error
 * que saldría como **500**.
 */
private fun claveDeIdempotencia(
    call: io.ktor.server.application.ApplicationCall,
): String? {
    val cruda = call.request.headers["Idempotency-Key"]?.trim() ?: return null
    if (cruda.isEmpty()) return null

    return try {
        java.util.UUID.fromString(cruda).toString()
    } catch (_: IllegalArgumentException) {
        throw dev.axzy.stock.backend.error.BadRequestException(
            "INVALID_IDEMPOTENCY_KEY",
            "La cabecera 'Idempotency-Key' debe ser un UUID. Se recibió '$cruda'.",
            mapOf("header" to "Idempotency-Key"),
        )
    }
}
