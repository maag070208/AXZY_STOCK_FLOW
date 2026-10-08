package dev.axzy.stock.backend.catalog

import dev.axzy.stock.backend.catalog.CambiosProducto
import dev.axzy.stock.backend.catalog.CambiosUbicacion
import dev.axzy.stock.backend.catalog.CantidadJson
import dev.axzy.stock.backend.catalog.CatalogImportService
import dev.axzy.stock.backend.catalog.ImportReportResponse
import dev.axzy.stock.backend.catalog.LocationCreateRequest
import dev.axzy.stock.backend.catalog.LocationRepository
import dev.axzy.stock.backend.catalog.LocationUpdateRequest
import dev.axzy.stock.backend.catalog.MotivoDeBorradoRequest
import dev.axzy.stock.backend.catalog.NuevaUbicacion
import dev.axzy.stock.backend.catalog.NuevoProducto
import dev.axzy.stock.backend.catalog.PageRequest
import dev.axzy.stock.backend.catalog.ProductCreateRequest
import dev.axzy.stock.backend.catalog.ProductRepository
import dev.axzy.stock.backend.catalog.ProductResponse
import dev.axzy.stock.backend.catalog.ProductUpdateRequest
import dev.axzy.stock.backend.catalog.TipoDeUbicacion
import dev.axzy.stock.backend.catalog.UnitOfMeasureRepository
import dev.axzy.stock.backend.catalog.WarehouseListResponse
import dev.axzy.stock.backend.catalog.WarehouseRepository
import dev.axzy.stock.backend.catalog.aDto
import dev.axzy.stock.backend.catalog.aRespuesta
import dev.axzy.stock.backend.catalog.traduciendoErroresDeBase
import dev.axzy.stock.backend.config.logger
import dev.axzy.stock.backend.error.BadRequestException
import dev.axzy.stock.backend.error.ConflictException
import dev.axzy.stock.backend.error.ForbiddenException
import dev.axzy.stock.backend.error.NotFoundException
import dev.axzy.stock.backend.error.UnauthorizedException
import dev.axzy.stock.backend.error.UnprocessableException
import dev.axzy.stock.backend.security.DenyAllPermissionChecker
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.backend.security.PermissionDecision
import dev.axzy.stock.domain.value.ProductCode
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * RUTAS DEL CATÁLOGO Y LAS UBICACIONES
 *
 * Implementa la sección §3 del contrato: productos, ubicaciones y bodegas.
 *
 * **Cada ruta exige su permiso de §11 antes de tocar la base.** La comprobación
 * va lo primero en el manejador, no después de validar el cuerpo: así una
 * petición sin autorización no puede distinguir «el recurso no existe» de «no
 * tienes permiso», que es información que no se regala.
 *
 * El verificador llega por parámetro y **por defecto deniega todo**
 * ([DenyAllPermissionChecker]): la identidad la conecta el módulo de identidad
 * pasando su propio [PermissionChecker], sin tocar este archivo.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Instala las rutas del catálogo.
 *
 * Uso desde `Application.kt`, dentro del bloque `routing { }`:
 *
 * ```kotlin
 * catalogRoutes(dataSource, verificadorDeIdentidad)
 * ```
 *
 * Sin el segundo argumento la implementación por defecto **deniega todo** con
 * 401: es preferible un catálogo inaccesible a un catálogo abierto.
 */
fun Route.catalogRoutes(
    dataSource: DataSource,
    permissionChecker: PermissionChecker = DenyAllPermissionChecker,
) {
    // Una sola conexión lógica para todo el módulo. `Database.connect` no abre
    // conexiones: envuelve el pool, y cada transacción toma la suya.
    val db = org.jetbrains.exposed.sql.Database.connect(dataSource)

    val productos = ProductRepository(db)
    val ubicaciones = LocationRepository(db)
    val bodegas = WarehouseRepository(db)
    val unidades = UnitOfMeasureRepository(db)
    val importacion = CatalogImportService(db, productos, unidades)

    rutasDeProductos(productos, unidades, importacion, permissionChecker)
    rutasDeUbicaciones(ubicaciones, bodegas, permissionChecker)
    rutasDeBodegas(bodegas, permissionChecker)
}

// ═════════════════════════════════════════════════════════════════════════════
// PRODUCTOS
// ═════════════════════════════════════════════════════════════════════════════

private fun Route.rutasDeProductos(
    productos: ProductRepository,
    unidades: UnitOfMeasureRepository,
    importacion: CatalogImportService,
    verificador: PermissionChecker,
) = route("/v1/products") {

    get {
        call.exigirPermiso(verificador, "product.view")

        val pagina = call.pagina(ProductRepository.CAMPOS_ORDENABLES, ProductRepository.ORDEN_POR_DEFECTO)
        val busqueda = call.request.queryParameters["q"]
        val activo = call.filtroBooleano("active")

        val resultado = productos.listar(pagina, busqueda, activo)
        call.respond(HttpStatusCode.OK, resultado.aRespuesta { it.aDto() })
    }

    post {
        call.exigirPermiso(verificador, "product.create")

        val peticion = call.receive<ProductCreateRequest>()
        val codigo = codigoDeProducto(peticion.productCode)
        val nombre = textoObligatorio(peticion.name, "name", 120)
        val unidad = unidades.porCodigo(peticion.unitMeasure)
            ?: throw unidadDesconocida(peticion.unitMeasure)

        // El prechequeo da el 409 legible en el caso normal; la garantía la
        // tiene el índice único de la base, que es el que no tiene carrera.
        if (productos.existeCodigo(codigo)) {
            throw ConflictException(
                code = "DUPLICATE_PRODUCT_CODE",
                message = "Ya existe un producto con el código '$codigo'.",
            )
        }

        val creado = traduciendoErroresDeBase {
            productos.crear(
                datos = NuevoProducto(
                    codigo = codigo,
                    nombre = nombre,
                    descripcion = peticion.description?.trim()?.takeIf { it.isNotEmpty() },
                    codigoUnidadMedida = unidad.code,
                ),
                unidadDeMedidaId = unidad.id,
            )
        }

        call.respond(HttpStatusCode.Created, creado.aDto())
    }

    // La ruta constante va antes que la de parámetro. Ktor resuelve por
    // prioridad, pero declararla primero deja claro que `/import` no es un id.
    post("/import") {
        call.exigirPermiso(verificador, "product.import")

        val (contenido, dryRun) = call.leerArchivoDeImport()
        val informe: ImportReportResponse = importacion.importar(contenido, dryRun)

        // 200 también en el import real: el cuerpo es el informe, y el informe
        // de una simulación y el de una carga difieren en `dryRun` y `persisted`.
        call.respond(HttpStatusCode.OK, informe)
    }

    get("/{id}") {
        call.exigirPermiso(verificador, "product.view")

        val id = call.idDeRuta()
        val producto = productos.porId(id) ?: throw noExisteProducto(id)
        call.respond(HttpStatusCode.OK, producto.aDto())
    }

    put("/{id}") {
        call.exigirPermiso(verificador, "product.edit")

        val id = call.idDeRuta()
        val actual = productos.porId(id) ?: throw noExisteProducto(id)
        val peticion = call.receive<ProductUpdateRequest>()

        // Tolerar el código igual permite el ida y vuelta de un cliente que
        // recibe el objeto del GET y lo devuelve modificado. Cambiarlo se
        // rechaza: está impreso en las etiquetas y en los estantes.
        peticion.productCode?.let { enviado ->
            if (enviado != actual.productCode) {
                throw UnprocessableException(
                    code = "PRODUCT_CODE_IMMUTABLE",
                    message = "El código de producto no se puede cambiar ('${actual.productCode}' → " +
                        "'$enviado'): es el código de barras impreso en las etiquetas y en los " +
                        "estantes, y cambiarlo obliga a reetiquetar el almacén. Existe una acción " +
                        "específica para reasignar códigos.",
                    details = mapOf("productCode" to enviado),
                )
            }
        }

        val nombre = textoObligatorio(peticion.name, "name", 120)
        val unidad = unidades.porCodigo(peticion.unitMeasure)
            ?: throw unidadDesconocida(peticion.unitMeasure)

        val actualizado = productos.actualizar(
            id = id,
            cambios = CambiosProducto(
                nombre = nombre,
                // En un PUT el cuerpo reemplaza al recurso: si no viene
                // descripción, se guarda sin descripción. Es la única forma de
                // borrarla, y omitirla no puede significar «déjala como está»
                // sin inventar un campo nuevo en el contrato.
                descripcion = peticion.description?.trim()?.takeIf { it.isNotEmpty() },
                unidadDeMedida = unidad.code,
            ),
            unidadDeMedidaId = unidad.id,
        ) ?: throw noExisteProducto(id)

        call.respond(HttpStatusCode.OK, actualizado.aDto())
    }

    delete("/{id}") {
        call.exigirPermiso(verificador, "product.delete")

        val id = call.idDeRuta()
        // El motivo se exige ANTES de mirar el producto: es un requisito de la
        // petición, no del recurso, y el contrato lo pide siempre.
        val motivo = call.motivoObligatorio()

        val producto = productos.porId(id) ?: throw noExisteProducto(id)

        if (!producto.active) {
            throw ConflictException(
                code = "PRODUCT_ALREADY_INACTIVE",
                message = "El producto '$id' ya estaba desactivado. Volver a desactivarlo " +
                    "sobrescribiría el motivo del primer borrado y perdería la traza.",
            )
        }

        // Un producto con existencias no se desactiva: el stock quedaría en un
        // producto invisible para el listado y el descuadre aparecería en el
        // siguiente conteo. Primero se ajusta a cero (con motivo) o se traslada.
        if (producto.stock.signum() != 0) {
            throw ConflictException(
                code = "PRODUCT_HAS_STOCK",
                message = "El producto '${producto.productCode}' tiene existencias y no se " +
                    "puede desactivar. Ajusta o traslada el stock primero.",
                details = mapOf("stock" to CantidadJson.deDecimal(producto.stock).comoTexto()),
            )
        }

        if (!productos.desactivar(id)) {
            // Carrera: otra petición lo desactivó entre la lectura y el UPDATE.
            throw ConflictException(
                code = "PRODUCT_ALREADY_INACTIVE",
                message = "El producto '$id' ya estaba desactivado.",
            )
        }

        // El motivo no tiene columna en `products` y el esquema no se toca: se
        // registra para que quede rastro del porqué. La traza con autor y fecha
        // corresponde a `audit_log`.
        logger.info(
            "Producto {} ({}) desactivado. Motivo declarado: {}",
            id,
            producto.productCode,
            motivo,
        )

        call.respond(HttpStatusCode.NoContent)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// UBICACIONES
// ═════════════════════════════════════════════════════════════════════════════

private fun Route.rutasDeUbicaciones(
    ubicaciones: LocationRepository,
    bodegas: WarehouseRepository,
    verificador: PermissionChecker,
) = route("/v1/locations") {

    get {
        call.exigirPermiso(verificador, "location.view")

        val pagina = call.pagina(LocationRepository.CAMPOS_ORDENABLES, LocationRepository.ORDEN_POR_DEFECTO)
        val busqueda = call.request.queryParameters["q"]
        val bodegaId = call.parametroLong("warehouseId")
        val tipo = call.request.queryParameters["kind"]?.let(::tipoDeUbicacion)
        val activo = call.filtroBooleano("active")

        val resultado = ubicaciones.listar(pagina, busqueda, bodegaId, tipo?.name, activo)
        call.respond(HttpStatusCode.OK, resultado.aRespuesta { it.aDto() })
    }

    post {
        call.exigirPermiso(verificador, "location.create")

        val peticion = call.receive<LocationCreateRequest>()
        val bodega = bodegas.porId(peticion.warehouseId)
            ?: throw UnprocessableException(
                code = "UNKNOWN_WAREHOUSE",
                message = "La bodega ${peticion.warehouseId} no existe.",
                details = mapOf("warehouseId" to peticion.warehouseId.toString()),
            )

        val hall = parteDeUbicacion(peticion.hall, "hall")
        val codigo = parteDeUbicacion(peticion.code, "code")
        val posicion = parteDeUbicacion(peticion.position, "position")
        val niveles = nivelesDeUbicacion(peticion.levels)
        val tipo = peticion.kind?.let(::tipoDeUbicacion) ?: TipoDeUbicacion.SHELF

        if (ubicaciones.existeTerna(bodega.id, hall, codigo, posicion, null)) {
            throw ubicacionDuplicada(hall, codigo, posicion, bodega.code)
        }

        val creada = traduciendoErroresDeBase {
            ubicaciones.crear(
                NuevaUbicacion(
                    warehouseId = bodega.id,
                    hall = hall,
                    code = codigo,
                    position = posicion,
                    levels = niveles,
                    kind = tipo.name,
                )
            )
        }

        call.respond(HttpStatusCode.Created, creada.aDto())
    }

    get("/{id}") {
        call.exigirPermiso(verificador, "location.view")

        val id = call.idDeRuta()
        val ubicacion = ubicaciones.porId(id) ?: throw noExisteUbicacion(id)
        call.respond(HttpStatusCode.OK, ubicacion.aDto())
    }

    put("/{id}") {
        call.exigirPermiso(verificador, "location.edit")

        val id = call.idDeRuta()
        val actual = ubicaciones.porId(id) ?: throw noExisteUbicacion(id)
        val peticion = call.receive<LocationUpdateRequest>()

        peticion.warehouseId?.let { enviado ->
            if (enviado != actual.warehouseId) {
                throw UnprocessableException(
                    code = "WAREHOUSE_IMMUTABLE",
                    message = "Una ubicación no se puede mover de bodega: el stock que " +
                        "contiene cambiaría de bodega sin ningún movimiento de inventario. " +
                        "Crea la ubicación en la bodega de destino y traslada el stock.",
                    details = mapOf("warehouseId" to enviado.toString()),
                )
            }
        }

        val hall = peticion.hall?.let { parteDeUbicacion(it, "hall") }
        val codigo = peticion.code?.let { parteDeUbicacion(it, "code") }
        val posicion = peticion.position?.let { parteDeUbicacion(it, "position") }
        val niveles = peticion.levels?.let { nivelesDeUbicacion(it) }
        val tipo = peticion.kind?.let(::tipoDeUbicacion)

        // La terna resultante se comprueba completa: cambiar solo la posición
        // puede chocar con una ubicación que ya existe.
        val nuevaTerna = Triple(
            hall ?: actual.hall,
            codigo ?: actual.code,
            posicion ?: actual.position,
        )
        val cambiaLaTerna = nuevaTerna != Triple(actual.hall, actual.code, actual.position)
        if (cambiaLaTerna &&
            ubicaciones.existeTerna(actual.warehouseId, nuevaTerna.first, nuevaTerna.second, nuevaTerna.third, id)
        ) {
            throw ubicacionDuplicada(nuevaTerna.first, nuevaTerna.second, nuevaTerna.third, actual.warehouseCode)
        }

        val actualizada = traduciendoErroresDeBase {
            ubicaciones.actualizar(
                id = id,
                cambios = CambiosUbicacion(
                    hall = hall,
                    code = codigo,
                    position = posicion,
                    levels = niveles,
                    kind = tipo?.name,
                    // `active` se puede volver a poner en true: sin esto, una
                    // ubicación desactivada por error solo se recuperaría por SQL.
                    activo = peticion.active,
                ),
            )
        } ?: throw noExisteUbicacion(id)

        call.respond(HttpStatusCode.OK, actualizada.aDto())
    }

    delete("/{id}") {
        call.exigirPermiso(verificador, "location.delete")

        val id = call.idDeRuta()
        // El motivo es opcional aquí (el contrato solo lo exige para productos),
        // pero si viene se registra: es la diferencia entre saber por qué se
        // apagó una ubicación y no saberlo.
        val motivo = call.motivoOpcional()

        val ubicacion = ubicaciones.porId(id) ?: throw noExisteUbicacion(id)

        if (!ubicacion.active) {
            throw ConflictException(
                code = "LOCATION_ALREADY_INACTIVE",
                message = "La ubicación '$id' ya estaba desactivada.",
            )
        }

        if (ubicacion.kind == TipoDeUbicacion.RECEIVING.name) {
            throw ConflictException(
                code = "RECEIVING_LOCATION_PROTECTED",
                message = "La ubicación de recepción no se puede desactivar: es donde " +
                    "vive lo recibido y no ubicado, y sin ella la recepción no tiene destino.",
            )
        }

        val stock = ubicaciones.totalDeStockDe(id)
        if (stock.signum() != 0) {
            throw ConflictException(
                code = "LOCATION_HAS_STOCK",
                message = "La ubicación '${ubicacion.displayName}' tiene existencias y no se " +
                    "puede desactivar. Traslada el stock primero.",
                details = mapOf("stock" to CantidadJson.deDecimal(stock).comoTexto()),
            )
        }

        if (!ubicaciones.desactivar(id)) {
            throw ConflictException(
                code = "LOCATION_ALREADY_INACTIVE",
                message = "La ubicación '$id' ya estaba desactivada.",
            )
        }

        logger.info("Ubicación {} ({}) desactivada. Motivo declarado: {}", id, ubicacion.displayName, motivo)

        call.respond(HttpStatusCode.NoContent)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// BODEGAS
// ═════════════════════════════════════════════════════════════════════════════

private fun Route.rutasDeBodegas(
    bodegas: WarehouseRepository,
    verificador: PermissionChecker,
) = route("/v1/warehouses") {

    get {
        // §11 no lista `GET /v1/warehouses`; §1.4 dice que este catálogo exige
        // `location.view`, que es el permiso que el dominio define para ver el
        // almacén físico. Dejarla sin permiso sería repetir el defecto del
        // legacy: rutas que parecían protegidas y no lo estaban.
        call.exigirPermiso(verificador, "location.view")

        // Sin paginación, y es deliberado: §1.4 exceptúa las bodegas porque son
        // un catálogo cerrado y diminuto que la interfaz necesita COMPLETO para
        // poblar el desplegable. Devolver solo la primera página obligaría al
        // cliente a pedirlas todas antes de poder dibujar el `select`.
        val busqueda = call.request.queryParameters["q"]
        val activo = call.filtroBooleano("active")

        val bodegasEncontradas = bodegas.listarTodas(busqueda, activo)
        call.respond(HttpStatusCode.OK, WarehouseListResponse(bodegasEncontradas.map { it.aDto() }))
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// AYUDANTES
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Exige el permiso de §11 y traduce la denegación al código correcto.
 *
 * `UNAUTHENTICATED` es 401 (falta identidad, hay que iniciar sesión) y todo lo
 * demás es 403 con su código propio: `PERMISSION_DENIED`, `SCOPE_INSUFFICIENT`,
 * `POLICY_DENIED` o `SEGREGATION_OF_DUTIES`. Colapsarlos en un 403 genérico es
 * el defecto del legacy: el usuario no sabe si pedir un permiso, esperar o
 * llamar a otra persona.
 */
private suspend fun ApplicationCall.exigirPermiso(verificador: PermissionChecker, permiso: String) {
    when (val decision = verificador.comprobar(this, permiso)) {
        PermissionDecision.Permitido -> Unit

        is PermissionDecision.Denegado ->
            if (decision.code == "UNAUTHENTICATED") {
                throw UnauthorizedException(decision.code, decision.message)
            } else {
                throw ForbiddenException(decision.code, decision.message)
            }
    }
}

/** Paginación de la petición, validada contra los campos admitidos del endpoint. */
private fun ApplicationCall.pagina(camposOrdenables: Set<String>, ordenPorDefecto: String): PageRequest =
    PageRequest.analizar(
        page = request.queryParameters["page"],
        size = request.queryParameters["size"],
        sort = request.queryParameters["sort"],
        camposOrdenables = camposOrdenables,
        ordenPorDefecto = ordenPorDefecto,
    )

/** Filtro booleano opcional de la query (`active=true`). */
private fun ApplicationCall.filtroBooleano(nombre: String): Boolean? {
    val crudo = request.queryParameters[nombre]?.trim() ?: return null
    return when (crudo.lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw BadRequestException(
            code = "INVALID_PARAMETER",
            message = "El parámetro '$nombre' debe ser true o false. Se recibió '$crudo'.",
            details = mapOf(nombre to crudo),
        )
    }
}

/** Parámetro de query numérico opcional. */
private fun ApplicationCall.parametroLong(nombre: String): Long? {
    val crudo = request.queryParameters[nombre]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return crudo.toLongOrNull() ?: throw BadRequestException(
        code = "INVALID_PARAMETER",
        message = "El parámetro '$nombre' debe ser un número entero. Se recibió '$crudo'.",
        details = mapOf(nombre to crudo),
    )
}

/** Identificador de la ruta. Se rechaza con 400 lo que no sea un entero positivo. */
private fun ApplicationCall.idDeRuta(): Long {
    val crudo = parameters["id"]
    val id = crudo?.toLongOrNull()
    if (id == null || id <= 0L) {
        throw BadRequestException(
            code = "INVALID_ID",
            message = "El identificador de la ruta debe ser un número entero positivo. " +
                "Se recibió '$crudo'.",
            details = mapOf("id" to crudo.orEmpty()),
        )
    }
    return id
}

/**
 * Motivo del borrado lógico, obligatorio.
 *
 * Se acepta en el cuerpo (`{"reason": "..."}`), que es lo que dice el contrato,
 * **y también** en la query (`?reason=`). El segundo camino no es un capricho:
 * hay proxies y clientes HTTP que descartan el cuerpo de un `DELETE`, y sin él
 * el usuario no podría borrar un producto.
 */
private suspend fun ApplicationCall.motivoObligatorio(): String =
    motivoOpcional() ?: throw UnprocessableException(
        code = "REASON_REQUIRED",
        message = "El borrado es lógico y exige un motivo: sin él, dentro de seis meses " +
            "nadie sabrá por qué se desactivó el producto.",
        details = mapOf("reason" to "obligatorio"),
    )

private suspend fun ApplicationCall.motivoOpcional(): String? {
    val delCuerpo = runCatching { receive<MotivoDeBorradoRequest>() }.getOrNull()?.reason
    val deLaQuery = request.queryParameters["reason"]
    return (delCuerpo ?: deLaQuery)?.trim()?.takeIf { it.isNotEmpty() }
}

/** Valida el código de producto con el objeto de valor del dominio. */
private fun codigoDeProducto(crudo: String): String {
    val resultado = ProductCode.validate(crudo)
    val error = resultado.exceptionOrNull()
    if (error != null) {
        throw UnprocessableException(
            code = "INVALID_PRODUCT_CODE",
            message = error.message ?: "El código de producto no es válido.",
            details = mapOf("productCode" to crudo),
        )
    }
    return resultado.getOrThrow().value
}

private fun textoObligatorio(crudo: String?, campo: String, maximo: Int): String {
    val limpio = crudo?.trim().orEmpty()
    if (limpio.isEmpty()) {
        throw UnprocessableException(
            code = "FIELD_REQUIRED",
            message = "El campo '$campo' es obligatorio.",
            details = mapOf(campo to "obligatorio"),
        )
    }
    if (limpio.length > maximo) {
        throw UnprocessableException(
            code = "FIELD_TOO_LONG",
            message = "El campo '$campo' admite $maximo caracteres y se recibieron ${limpio.length}.",
            details = mapOf(campo to limpio.length.toString()),
        )
    }
    return limpio
}

/**
 * Normaliza una parte de la terna de ubicación.
 *
 * Se pasa a mayúsculas porque la base impone el formato canónico
 * (`locations_code_canonical`) y el alfabeto real del legacy es `0-9`, `A-Z` y
 * `Ñ`. Normalizar aquí convierte una violación de restricción de PostgreSQL en
 * un 422 que explica qué campo está mal.
 */
private fun parteDeUbicacion(crudo: String?, campo: String): String {
    val limpio = crudo?.trim()?.uppercase().orEmpty()
    if (limpio.isEmpty()) {
        throw UnprocessableException(
            code = "FIELD_REQUIRED",
            message = "El campo '$campo' de la ubicación es obligatorio.",
            details = mapOf(campo to "obligatorio"),
        )
    }
    if (limpio.length > 20) {
        throw UnprocessableException(
            code = "FIELD_TOO_LONG",
            message = "El campo '$campo' admite 20 caracteres y se recibieron ${limpio.length}.",
            details = mapOf(campo to limpio.length.toString()),
        )
    }
    if (!ALFABETO_UBICACION.matches(limpio)) {
        throw UnprocessableException(
            code = "INVALID_LOCATION_PART",
            message = "El campo '$campo' solo admite dígitos y letras en mayúsculas " +
                "(A-Z, Ñ). Se recibió '$limpio'.",
            details = mapOf(campo to limpio),
        )
    }
    return limpio
}

private val ALFABETO_UBICACION = Regex("^[0-9A-ZÑ]+$")

private fun nivelesDeUbicacion(crudo: Int?): Int {
    val niveles = crudo ?: 1
    if (niveles < 1 || niveles > 99) {
        throw UnprocessableException(
            code = "INVALID_LEVELS",
            message = "El número de niveles debe estar entre 1 y 99. Se recibió $niveles.",
            details = mapOf("levels" to niveles.toString()),
        )
    }
    return niveles
}

private fun tipoDeUbicacion(crudo: String): TipoDeUbicacion =
    runCatching { TipoDeUbicacion.valueOf(crudo.trim().uppercase()) }.getOrElse {
        throw UnprocessableException(
            code = "INVALID_LOCATION_KIND",
            message = "El tipo de ubicación debe ser SHELF o RECEIVING. Se recibió '$crudo'.",
            details = mapOf("kind" to crudo),
        )
    }

private fun unidadDesconocida(crudo: String) = UnprocessableException(
    code = "UNKNOWN_UNIT_MEASURE",
    message = "La unidad de medida '$crudo' no existe en el catálogo de unidades. " +
        "El catálogo es cerrado a propósito: evita el clásico KG/Kg/KGS como tres " +
        "unidades distintas.",
    details = mapOf("unitMeasure" to crudo),
)

private fun noExisteProducto(id: Long) = NotFoundException(
    message = "El producto $id no existe.",
    code = "PRODUCT_NOT_FOUND",
)

private fun noExisteUbicacion(id: Long) = NotFoundException(
    message = "La ubicación $id no existe.",
    code = "LOCATION_NOT_FOUND",
)

private fun ubicacionDuplicada(hall: String, code: String, position: String, bodega: String) =
    ConflictException(
        code = "DUPLICATE_LOCATION",
        message = "Ya existe la ubicación $hall-$code-$position en la bodega $bodega.",
    )

/**
 * Lee el `multipart/form-data` del import.
 *
 * `dryRun` por defecto es **`true`**: si el cliente olvida el campo, se simula.
 * El olvido inverso —escribir 1.398 productos cuando se quería simular— no
 * tiene vuelta atrás.
 */
private suspend fun ApplicationCall.leerArchivoDeImport(): Pair<ByteArray, Boolean> {
    var dryRun = true
    var contenido: ByteArray? = null

    receiveMultipart().forEachPart { parte ->
        when (parte) {
            is PartData.FormItem -> if (parte.name == "dryRun") {
                dryRun = when (parte.value.trim().lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> throw BadRequestException(
                        code = "INVALID_DRY_RUN",
                        message = "El campo 'dryRun' debe ser true o false. Se recibió '${parte.value}'.",
                        details = mapOf("dryRun" to parte.value),
                    )
                }
            }

            is PartData.FileItem -> if (parte.name == "file") {
                contenido = parte.provider().readRemaining().readByteArray()
            }

            else -> Unit
        }
        parte.dispose()
    }

    val archivo = contenido ?: throw BadRequestException(
        code = "FILE_REQUIRED",
        message = "Falta la parte 'file' del formulario. El import es " +
            "multipart/form-data con 'file' (CSV o XLSX) y 'dryRun'.",
    )

    if (archivo.size > LIMITE_DE_ARCHIVO) {
        throw BadRequestException(
            code = "FILE_TOO_LARGE",
            message = "El archivo supera ${LIMITE_DE_ARCHIVO / (1024 * 1024)} MB. " +
                "El catálogo real ocupa unos cientos de KB.",
        )
    }

    return archivo to dryRun
}

/** 20 MB: holgado para el catálogo real y lejos de un archivo que ahogue el servidor. */
private const val LIMITE_DE_ARCHIVO = 20L * 1024 * 1024
