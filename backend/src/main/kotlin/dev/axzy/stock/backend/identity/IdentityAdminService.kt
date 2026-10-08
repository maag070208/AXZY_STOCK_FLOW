package dev.axzy.stock.backend.identity

import dev.axzy.stock.backend.catalog.Page
import dev.axzy.stock.backend.catalog.PageRequest
import dev.axzy.stock.domain.access.PermissionModule
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ADMINISTRACIÓN DE IDENTIDAD — personas, roles y catálogo de permisos
 *
 * Las tres lecturas que las pantallas de administración necesitan para pintarse.
 * **Ninguna escribe**: el alta y la edición de personas son otra entrega, y
 * mezclarlas aquí dejaría este módulo a medio camino sin que nada lo delate.
 *
 * **Se usa SQL directo, y es una decisión, no una pereza.** La declaración de
 * `permissions` en [PermissionsTable] no incluye `description` ni `sort_order`,
 * que son justo las dos columnas que el catálogo agrupado necesita, y este
 * módulo no puede tocar ese archivo. Además `permissions.scopes` y
 * `role_permissions.scope` son enums de PostgreSQL: el mismo problema de tipos
 * que ya obligó a usar SQL directo en [IdentityRepository] y en [IdentitySeeder].
 * El array de alcances se lee con un `scopes::text` explícito, que no depende de
 * cómo decida tipar el driver un array de enum.
 *
 * **El hash de la contraseña no se selecciona en ninguna consulta.** No es que
 * se filtre después: nunca entra en la respuesta, así que no hay forma de que se
 * escape por un `copy`, un log o un cambio de DTO.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class IdentityAdminService(private val dataSource: DataSource) {

    companion object {

        /**
         * Campos de `sort` admitidos en `GET /v1/users`.
         *
         * Se declaran por endpoint, como exige §1.4: `password_hash` no está aquí
         * ni puede estarlo, y un campo fuera de la lista se rechaza con 400 en
         * lugar de ignorarse.
         */
        val SORTABLE_USER_FIELDS: Set<String> =
            setOf("username", "name", "createdAt", "lastLoginAt")

        const val DEFAULT_USER_ORDER = "username"

        /** Campos de `sort` admitidos en `GET /v1/roles`. */
        val SORTABLE_ROLE_FIELDS: Set<String> =
            setOf("key", "name", "sortOrder", "permissionCount", "userCount")

        const val DEFAULT_ROLE_ORDER = "sortOrder"
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PERSONAS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Lista paginada de personas.
     *
     * **El total se cuenta con un `count(*)` que lleva los MISMOS filtros que la
     * página.** Pasar `items.size` fue el defecto del listado de inventario: el
     * sobre decía «50 resultados» siempre y la pantalla no podía pintar cuántas
     * páginas hay. El `LIMIT` no puede gobernar el total.
     *
     * @param busqueda `q`, búsqueda libre sobre nombre y nombre de usuario.
     * @param activo `active`; por defecto se devuelven también los desactivados.
     *   Una persona desactivada no desaparece: se muestra como tal. Ocultarla
     *   haría creer que nunca existió y quien la busca para reactivarla no la
     *   encontraría.
     */
    fun listUsers(page: PageRequest, search: String?, active: Boolean?): Page<AdminUser> =
        dataSource.connection.use { connection ->
            val filter = userFilter(search, active)

            val total = connection.prepareStatement("SELECT count(*) FROM users ${filter.sql}")
                .use { statement ->
                    statement.withParameters(filter.parameters)
                    statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
                }

            val rows = connection.prepareStatement(
                """
                SELECT id, username, name, disabled, last_login_at
                FROM users
                ${filter.sql}
                ORDER BY ${userSortColumn(page)} ${directionOf(page)}, id ${directionOf(page)}
                LIMIT ? OFFSET ?
                """.trimIndent(),
            ).use { statement ->
                statement.withParameters(filter.parameters)
                statement.setInt(filter.parameters.size + 1, page.size)
                statement.setLong(filter.parameters.size + 2, page.offset)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                AdminUser(
                                    id = rs.getLong("id"),
                                    username = rs.getString("username"),
                                    name = rs.getString("name"),
                                    // La columna es `disabled` y la respuesta habla
                                    // de `active`: el esquema no se toca, la
                                    // traducción se hace aquí y en un solo sitio.
                                    active = !rs.getBoolean("disabled"),
                                    roles = emptyList(),
                                    lastLoginAt = rs.getTimestamp("last_login_at")?.toInstant(),
                                )
                            )
                        }
                    }
                }
            }

            // Los roles de TODA la página en una sola consulta. Pedirlos persona a
            // persona sería el N+1 que un listado no se puede permitir.
            val rolesPerPerson = rolesOf(connection, rows.map { it.id })

            Page(
                items = rows.map { it.copy(roles = rolesPerPerson[it.id].orEmpty()) },
                page = page.page,
                size = page.size,
                totalItems = total,
            )
        }

    /**
     * Los roles de un conjunto de personas, agrupados por persona.
     *
     * **Solo los roles activos**: son los que la persona tiene de verdad, porque
     * el motor de acceso ignora los inactivos ([IdentityRepository] también los
     * filtra). Devolver un rol apagado como si estuviera concedido prometería un
     * acceso que el motor va a denegar.
     */
    private fun rolesOf(connection: Connection, ids: List<Long>): Map<Long, List<String>> {
        if (ids.isEmpty()) return emptyMap()

        val markers = ids.joinToString(", ") { "?" }
        return connection.prepareStatement(
            """
            SELECT ur.user_id, r.key
            FROM user_roles ur
            JOIN roles r ON r.id = ur.role_id
            WHERE ur.user_id IN ($markers) AND r.active
            ORDER BY r.sort_order, r.key
            """.trimIndent(),
        ).use { statement ->
            statement.withParameters(ids)
            statement.executeQuery().use { rows ->
                val accumulated = mutableMapOf<Long, MutableList<String>>()
                while (rows.next()) {
                    accumulated.getOrPut(rows.getLong("user_id")) { mutableListOf() }
                        .add(rows.getString("key"))
                }
                accumulated
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ROLES
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Lista paginada de roles con sus recuentos.
     *
     * Se pagina aunque hoy haya cinco roles: el contrato §1.4 da el mismo sobre a
     * todos los listados, y una pantalla que lee `totalItems` no debe encontrarse
     * con que este endpoint no lo trae. Como los roles caben de sobra en la
     * primera página, el cliente que solo quiere el catálogo completo lo obtiene
     * igual con la petición por defecto.
     *
     * `permissionCount` cuenta **solo concesiones de permisos activos**: un
     * permiso desactivado no es acceso, y contarlo inflaría el número que la
     * pantalla usa para decidir si un rol está vacío.
     */
    fun listRoles(page: PageRequest, search: String?, active: Boolean?): Page<AdminRole> =
        dataSource.connection.use { connection ->
            val filter = roleFilter(search, active)

            val total = connection.prepareStatement("SELECT count(*) FROM roles r ${filter.sql}")
                .use { statement ->
                    statement.withParameters(filter.parameters)
                    statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
                }

            val rows = connection.prepareStatement(
                """
                SELECT r.id, r.key, r.name, r.description, r.is_system, r.active, r.sort_order,
                       (SELECT count(*)
                          FROM role_permissions rp
                          JOIN permissions p ON p.id = rp.permission_id
                         WHERE rp.role_id = r.id AND p.active) AS permission_count,
                       (SELECT count(*)
                          FROM user_roles ur
                         WHERE ur.role_id = r.id) AS user_count
                FROM roles r
                ${filter.sql}
                ORDER BY ${roleSortColumn(page)} ${directionOf(page)}, r.id ${directionOf(page)}
                LIMIT ? OFFSET ?
                """.trimIndent(),
            ).use { statement ->
                statement.withParameters(filter.parameters)
                statement.setInt(filter.parameters.size + 1, page.size)
                statement.setLong(filter.parameters.size + 2, page.offset)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                AdminRole(
                                    id = rs.getLong("id"),
                                    key = rs.getString("key"),
                                    name = rs.getString("name"),
                                    description = rs.getString("description"),
                                    isSystem = rs.getBoolean("is_system"),
                                    active = rs.getBoolean("active"),
                                    sortOrder = rs.getInt("sort_order"),
                                    permissionCount = rs.getLong("permission_count"),
                                    userCount = rs.getLong("user_count"),
                                )
                            )
                        }
                    }
                }
            }

            Page(
                items = rows,
                page = page.page,
                size = page.size,
                totalItems = total,
            )
        }

    // ═══════════════════════════════════════════════════════════════════════
    // CATÁLOGO DE PERMISOS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * El catálogo de permisos, agrupado por módulo.
     *
     * **Solo los activos.** Un permiso desactivado no se puede conceder, así que
     * no forma parte del catálogo que la matriz de roles ofrece; el catálogo
     * completo con los inactivos es otra ruta en el plan (`/v1/permissions/admin`),
     * que todavía no existe.
     *
     * El agrupado se hace en Kotlin y no con un `GROUP BY`: PostgreSQL no sabe
     * ordenar los módulos en el orden de presentación del catálogo, y un
     * `array_agg` obligaría a montar el JSON dentro del SQL. Aquí las filas ya
     * vienen en orden de `sort_order`, y `groupBy` conserva ese orden de aparición.
     */
    fun permissionCatalog(): List<PermissionModuleGroup> {
        val permissions = dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT key, module, resource, action, name, description,
                       scopes::text AS scopes, sensitive, active, sort_order
                FROM permissions
                WHERE active
                ORDER BY sort_order, key
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                AdminPermission(
                                    key = rs.getString("key"),
                                    module = rs.getString("module"),
                                    resource = rs.getString("resource"),
                                    action = rs.getString("action"),
                                    name = rs.getString("name"),
                                    description = rs.getString("description"),
                                    scopes = scopesOf(rs.getString("scopes")),
                                    sensitive = rs.getBoolean("sensitive"),
                                    active = rs.getBoolean("active"),
                                    sortOrder = rs.getInt("sort_order"),
                                )
                            )
                        }
                    }
                }
            }
        }

        return permissions.groupBy { it.module }
            .map { (key, fromModule) -> PermissionModuleGroup(key, displayNameOf(key), fromModule) }
    }

    /**
     * Los alcances de un permiso, desde el literal de array de PostgreSQL.
     *
     * La columna es `permission_scope[]`. Se lee como texto —`{OWN,AREA,ALL}`— en
     * lugar de con `getArray` porque el driver devuelve el array de enums como
     * objetos cuyo tipo depende de la versión, y aquí lo único que se necesita es
     * la lista de nombres. Se aceptan también las comillas dobles que PostgreSQL
     * pone alrededor de un valor que las necesite.
     */
    private fun scopesOf(literal: String?): List<String> =
        literal?.trim()?.removePrefix("{")?.removeSuffix("}")?.split(',')
            ?.map { it.trim().removeSurrounding("\"") }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /**
     * El nombre legible del módulo (`IDENTITY` → «identidad»).
     *
     * Se traduce con el enum del dominio, que es donde vive la presentación del
     * módulo, y si la base trae un módulo que el dominio todavía no conoce se
     * devuelve la clave cruda: **una pantalla con `FUTURO` es mejor que un error
     * 500** por un dato que la propia base aceptó.
     */
    private fun displayNameOf(key: String): String =
        runCatching { PermissionModule.valueOf(key).displayName }.getOrDefault(key)

    // ═══════════════════════════════════════════════════════════════════════
    // FILTROS Y ORDEN
    // ═══════════════════════════════════════════════════════════════════════

    /** Una cláusula `WHERE` ya compuesta, con sus parámetros en orden. */
    private data class SqlFilter(val sql: String, val parameters: List<Any?>)

    private fun userFilter(search: String?, active: Boolean?): SqlFilter {
        val conditions = mutableListOf<String>()
        val parameters = mutableListOf<Any?>()

        active?.let { value ->
            conditions += "disabled = ?"
            parameters += !value
        }

        search?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            // El patrón viaja como parámetro, nunca concatenado en el SQL. El `%`
            // escrito por el usuario es un comodín a propósito: esto es una
            // búsqueda libre, no una comparación exacta.
            conditions += "(lower(username) LIKE ? OR lower(name) LIKE ?)"
            val pattern = "%${text.lowercase()}%"
            parameters += pattern
            parameters += pattern
        }

        return SqlFilter(conditions.aWhere(), parameters)
    }

    private fun roleFilter(search: String?, active: Boolean?): SqlFilter {
        val conditions = mutableListOf<String>()
        val parameters = mutableListOf<Any?>()

        active?.let { value ->
            conditions += "r.active = ?"
            parameters += value
        }

        search?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            conditions += "(lower(r.key) LIKE ? OR lower(r.name) LIKE ?)"
            val pattern = "%${text.lowercase()}%"
            parameters += pattern
            parameters += pattern
        }

        return SqlFilter(conditions.aWhere(), parameters)
    }

    /**
     * Une las condiciones con `AND`.
     *
     * Sin condiciones devuelve cadena vacía y no `WHERE 1=1`: el `1=1` es el
     * truco que hace ilegible un generador de SQL cuando crece.
     */
    private fun List<String>.aWhere(): String =
        if (isEmpty()) "" else joinToString(prefix = "WHERE ", separator = " AND ") { "($it)" }

    /**
     * Traduce el campo de orden ya validado a su columna.
     *
     * **Nunca se interpola el valor que mandó el cliente.** `PageRequest.analizar`
     * ya lo comprobó contra [CAMPOS_ORDENABLES_USUARIOS], y este `when` solo
     * devuelve literales del código: aunque la validación fallara, de aquí no
     * puede salir SQL ajeno.
     */
    private fun userSortColumn(page: PageRequest): String = when (page.fieldOrder) {
        "name" -> "name"
        "createdAt" -> "created_at"
        "lastLoginAt" -> "last_login_at"
        else -> "username"
    }

    private fun roleSortColumn(page: PageRequest): String = when (page.fieldOrder) {
        "key" -> "r.key"
        "name" -> "r.name"
        "permissionCount" -> "permission_count"
        "userCount" -> "user_count"
        else -> "r.sort_order"
    }

    private fun directionOf(page: PageRequest): String = if (page.ascending) "ASC" else "DESC"

    /** Enlaza los parámetros en orden. Solo valores: ni un nombre de columna viaja así. */
    private fun PreparedStatement.withParameters(parameters: List<Any?>): PreparedStatement {
        parameters.forEachIndexed { index, value -> setObject(index + 1, value) }
        return this
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// FILAS DEL REPOSITORIO
//
// No son los DTO de la API: la fila de base y la respuesta HTTP se separan para
// que un cambio de esquema no obligue a cambiar el contrato, y al revés.
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Una persona del listado.
 *
 * **No tiene campo de contraseña, ni siquiera uno que se deje nulo.** No es que
 * se oculte al serializar: el hash no se selecciona en la consulta, así que no
 * existe en memoria en ningún momento de este camino.
 */
data class AdminUser(
    val id: Long,
    val username: String,
    val name: String,
    /** Claves de los roles activos, en el orden de presentación de los roles. */
    val roles: List<String>,
    val active: Boolean,
    val lastLoginAt: Instant?,
)

/** Un rol con cuántos permisos concede y cuántas personas lo tienen. */
data class AdminRole(
    val id: Long,
    val key: String,
    val name: String,
    val description: String?,
    val isSystem: Boolean,
    val active: Boolean,
    val sortOrder: Int,
    val permissionCount: Long,
    /**
     * Cuántas personas tienen el rol.
     *
     * No lo pidió la pantalla, lo pide la operación: un rol con personas no se
     * puede borrar, y el día que exista el borrado el cliente tiene que poder
     * avisar antes de intentarlo en lugar de recibir un 409 sin contexto.
     */
    val userCount: Long,
)

/** Un permiso del catálogo, con sus alcances admitidos. */
data class AdminPermission(
    val key: String,
    val module: String,
    val resource: String,
    val action: String,
    val name: String,
    val description: String?,
    /** Alcances admitidos (`OWN`, `AREA`, `ALL`). Un permiso no ofrece los que no estén aquí. */
    val scopes: List<String>,
    val sensitive: Boolean,
    val active: Boolean,
    val sortOrder: Int,
)

/** Un módulo del catálogo con sus permisos, en orden de presentación. */
data class PermissionModuleGroup(
    val module: String,
    val moduleName: String,
    val permissions: List<AdminPermission>,
)
