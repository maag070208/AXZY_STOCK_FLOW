package dev.axzy.stock.backend.identity

import dev.axzy.stock.backend.catalog.PageRequest
import dev.axzy.stock.backend.catalog.aRespuesta
import dev.axzy.stock.backend.error.BadRequestException
import dev.axzy.stock.backend.error.ForbiddenException
import dev.axzy.stock.backend.error.UnauthorizedException
import dev.axzy.stock.backend.identity.IdentityAdminService
import dev.axzy.stock.backend.identity.ModuloDePermisos
import dev.axzy.stock.backend.identity.PermisoAdmin
import dev.axzy.stock.backend.identity.RolAdmin
import dev.axzy.stock.backend.identity.UsuarioAdmin
import dev.axzy.stock.backend.security.DenyAllPermissionChecker
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.backend.security.PermissionDecision
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * RUTAS DE ADMINISTRACIÓN DE IDENTIDAD
 *
 * Las tres lecturas que faltaban para que las pantallas de administración dejen
 * de estar vacías:
 *
 *   · `GET /v1/users`        — personas, paginadas
 *   · `GET /v1/roles`        — roles con su número de permisos
 *   · `GET /v1/permissions`  — catálogo de permisos agrupado por módulo
 *
 * Las tres son de solo lectura. El alta y la edición de personas viven en otro
 * módulo, y traerlas aquí a medias habría dejado esta entrega sin un final claro.
 *
 * **El hash de la contraseña no aparece en ninguna respuesta.** No hay campo que
 * lo contenga ni en el DTO ni en la consulta, así que no depende de que alguien
 * se acuerde de borrarlo al añadir un campo nuevo.
 * ═══════════════════════════════════════════════════════════════════════════
 */

// ── LOS PERMISOS QUE SE EXIGEN, Y POR QUÉ NO SON LOS QUE PEDÍA EL ENCARGO ────
//
// El encargo pedía `user.view`, `role.view` y `permission.view`. **Los dos
// últimos NO existen en la base.** Comprobado contra las 76 filas de
// `permissions`:
//
//     SELECT key FROM permissions WHERE key LIKE 'role.%' OR key LIKE 'permission.%';
//     → permission.exception, role.assign, role.manage
//
// El catálogo del dominio (`PermissionCatalog`) los define igual: `role.manage`
// es «Administrar roles y permisos» y **cubre las dos cosas**, leer la lista de
// roles y leer el catálogo de permisos. Inventar un `role.view` que no está
// sembrado dejaría las rutas respondiendo 403 para todo el mundo —incluido el
// administrador—, que es peor que el defecto que se está corrigiendo. Y añadirlo
// al catálogo es tocar el dominio y el sembrado, que no es de este encargo.
//
// El plan de roles y permisos dice lo mismo: `GET /v1/roles` exige `role.manage`.
// Para el catálogo de permisos, `role.manage` es el permiso más cercano de los
// que existen, y es el que hace falta para la pantalla que lo consume: la matriz
// de roles, que sin el catálogo no se puede dibujar.
private const val PERMISO_VER_USUARIOS = "user.view"

/** `role.manage` es lo más cercano que existe a `role.view` y a `permission.view`. */
private const val PERMISO_ADMINISTRAR_ACCESO = "role.manage"

/**
 * Instala las rutas de administración de identidad.
 *
 * Uso desde `Application.kt`, dentro del bloque `routing { }`:
 *
 * ```kotlin
 * identityAdminRoutes(dataSource, verificadorDeIdentidad)
 * ```
 *
 * Sin el segundo argumento la implementación por defecto **deniega todo** con
 * 401: mientras la identidad no esté conectada, es preferible que el listado de
 * personas no responda a que responda a cualquiera.
 */
fun Route.identityAdminRoutes(
    dataSource: DataSource,
    permissionChecker: PermissionChecker = DenyAllPermissionChecker,
) {
    val servicio = IdentityAdminService(dataSource)

    // ═══════════════════════════════════════════════════════════════════════
    // GET /v1/users — personas, paginadas
    // ═══════════════════════════════════════════════════════════════════════
    route("/v1/users") {
        get {
            call.exigirPermiso(permissionChecker, PERMISO_VER_USUARIOS)

            val pagina = call.pagina(
                camposOrdenables = IdentityAdminService.CAMPOS_ORDENABLES_USUARIOS,
                ordenPorDefecto = IdentityAdminService.ORDEN_POR_DEFECTO_USUARIOS,
            )
            val busqueda = call.request.queryParameters["q"]
            val activo = call.filtroBooleano("active")

            val resultado = servicio.listarUsuarios(pagina, busqueda, activo)
            call.respond(HttpStatusCode.OK, resultado.aRespuesta { it.aDto() })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /v1/roles — roles con su número de permisos
    // ═══════════════════════════════════════════════════════════════════════
    route("/v1/roles") {
        get {
            call.exigirPermiso(permissionChecker, PERMISO_ADMINISTRAR_ACCESO)

            val pagina = call.pagina(
                camposOrdenables = IdentityAdminService.CAMPOS_ORDENABLES_ROLES,
                ordenPorDefecto = IdentityAdminService.ORDEN_POR_DEFECTO_ROLES,
            )
            val busqueda = call.request.queryParameters["q"]
            val activo = call.filtroBooleano("active")

            val resultado = servicio.listarRoles(pagina, busqueda, activo)
            call.respond(HttpStatusCode.OK, resultado.aRespuesta { it.aDto() })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /v1/permissions — el catálogo, agrupado por módulo
    // ═══════════════════════════════════════════════════════════════════════
    route("/v1/permissions") {
        get {
            call.exigirPermiso(permissionChecker, PERMISO_ADMINISTRAR_ACCESO)

            // Sin paginación, y es deliberado: §1.4 exceptúa los catálogos
            // cerrados que la interfaz necesita COMPLETOS —como las bodegas—
            // porque el cliente tendría que pedir todas las páginas antes de
            // poder dibujar nada. El catálogo de permisos es la matriz de la
            // pantalla de roles: llega entero, agrupado por módulo.
            val modulos = servicio.catalogoDePermisos()
            val planos = modulos.flatMap { it.permissions }

            call.respond(
                HttpStatusCode.OK,
                CatalogoDePermisosResponse(
                    totalItems = planos.size,
                    modules = modulos.map { it.aDto() },
                    // La lista plana viaja ADEMÁS de los grupos, no en su lugar:
                    // un selector de permisos no tiene por qué recorrer los
                    // módulos para pintar 76 opciones, y el dato es el mismo.
                    items = planos.map { it.aDto() },
                ),
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// RESPUESTAS
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Una persona del listado.
 *
 * **No hay campo de contraseña.** Ni el hash, ni un prefijo, ni un booleano que
 * diga si la tiene: nada de eso lo necesita la pantalla, y un campo que existe
 * es un campo que algún día se rellena por descuido.
 */
@Serializable
data class UsuarioResponse(
    val id: Long,
    val username: String,
    val name: String,
    /** Claves de los roles activos, por ejemplo `["JEFE_BODEGA"]`. */
    val roles: List<String>,
    /** `true` si la cuenta está habilitada. La columna de la base es `disabled`. */
    val active: Boolean,
    /** Último inicio de sesión en ISO-8601. Se omite si nunca ha entrado. */
    val lastLoginAt: String? = null,
)

/** Un rol con su número de permisos y de personas. */
@Serializable
data class RolResponse(
    val id: Long,
    val key: String,
    val name: String,
    val description: String? = null,
    val isSystem: Boolean,
    val active: Boolean,
    val sortOrder: Int,
    val permissionCount: Long,
    val userCount: Long,
)

/** Un permiso del catálogo con sus alcances admitidos. */
@Serializable
data class PermisoResponse(
    val key: String,
    val module: String,
    val resource: String,
    val action: String,
    val name: String,
    val description: String? = null,
    val scopes: List<String>,
    val sensitive: Boolean,
    val active: Boolean,
    val sortOrder: Int,
)

/** Un módulo del catálogo con sus permisos, en orden de presentación. */
@Serializable
data class ModuloDePermisosResponse(
    val module: String,
    /** Nombre legible (`IDENTITY` → «identidad»), para el encabezado de la pantalla. */
    val moduleName: String,
    val count: Int,
    val permissions: List<PermisoResponse>,
)

/**
 * El catálogo completo.
 *
 * Lleva el total explícito para que la pantalla pueda decir «76 permisos» sin
 * sumar los grupos, y la lista plana además de los grupos para el caso de uso
 * que no necesita el agrupado.
 */
@Serializable
data class CatalogoDePermisosResponse(
    val totalItems: Int,
    val modules: List<ModuloDePermisosResponse>,
    val items: List<PermisoResponse>,
)

// ═════════════════════════════════════════════════════════════════════════════
// TRADUCCIONES DE FILA A RESPUESTA
// ═════════════════════════════════════════════════════════════════════════════

private fun UsuarioAdmin.aDto() = UsuarioResponse(
    id = id,
    username = username,
    name = name,
    roles = roles,
    active = active,
    // La hora viaja como ISO-8601 en UTC (`2026-10-08T03:21:01.778480Z`) y no
    // como marca numérica: es legible en un registro y no depende de la zona
    // horaria de quien la lea.
    lastLoginAt = lastLoginAt?.toString(),
)

private fun RolAdmin.aDto() = RolResponse(
    id = id,
    key = key,
    name = name,
    description = description,
    isSystem = isSystem,
    active = active,
    sortOrder = sortOrder,
    permissionCount = permissionCount,
    userCount = userCount,
)

private fun PermisoAdmin.aDto() = PermisoResponse(
    key = key,
    module = module,
    resource = resource,
    action = action,
    name = name,
    description = description,
    scopes = scopes,
    sensitive = sensitive,
    active = active,
    sortOrder = sortOrder,
)

private fun ModuloDePermisos.aDto() = ModuloDePermisosResponse(
    module = module,
    moduleName = moduleName,
    count = permissions.size,
    permissions = permissions.map { it.aDto() },
)

// ═════════════════════════════════════════════════════════════════════════════
// AYUDANTES
//
// `exigirPermiso`, `pagina` y `filtroBooleano` son las mismas de CatalogRoutes,
// y están duplicadas a propósito: allí son `private` del archivo, y hacerlas
// públicas para compartirlas abriría la puerta a que cada módulo las use con
// reglas propias. InventoryRoutes tomó la misma decisión con `pagina`. Lo que sí
// se reutiliza —y es lo que importa— es `PageRequest`, `Page` y `aRespuesta`:
// la validación de `page`, `size` y `sort` es una sola en todo el backend.
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Exige el permiso y traduce la denegación al código correcto.
 *
 * `UNAUTHENTICATED` es 401 —falta identidad, hay que iniciar sesión— y todo lo
 * demás es 403 con su código propio. Colapsarlos en un 403 genérico dejaría al
 * usuario sin saber si le falta un permiso o si el permiso no alcanza.
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
