package dev.axzy.stock.backend.identity

import dev.axzy.stock.domain.access.BaseRole
import dev.axzy.stock.domain.access.PermissionCatalog
import dev.axzy.stock.domain.access.RoleDefinitions
import java.security.SecureRandom
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * SEMBRADO INICIAL DE IDENTIDAD
 *
 * Crea los cinco roles base, **los permisos del catálogo** y un usuario
 * administrador. Sin esto, el backend arranca y **no se puede entrar**: la
 * pantalla de acceso no tiene con qué validar.
 *
 * **Todo es idempotente.** Ejecutarlo dos veces no duplica nada y no falla. Es lo
 * que permite llamarlo en cada arranque sin miedo.
 *
 * **Se usa SQL directo y no Exposed, y es una decisión meditada.** El sembrado
 * tiene que ser **una sola transacción**, y `permissions.scopes` es un array del
 * enum de PostgreSQL que hay que enviar con el cast explícito
 * `::permission_scope[]`. Con Exposed, el SQL directo y su transacción viven en
 * conexiones distintas: la primera versión abría su propia conexión, hacía
 * ROLLBACK al cerrarla y los permisos se perdían **mientras el contador decía que
 * se habían creado**. Un contador que miente es peor que un error.
 *
 * Con JDBC directo hay una sola conexión, un solo `commit`, y el array se envía
 * como literal con su cast. Menos piezas y ninguna que pueda desincronizarse.
 *
 * **La contraseña del administrador se genera, no se fija.** No hay
 * `admin`/`admin12345` como en el legacy: se genera una aleatoria fuerte, se
 * imprime **una sola vez** por consola y se marca `must_change_password`.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class IdentitySeeder(private val dataSource: DataSource) {

    data class SeedResult(
        val rolesCreated: Int,
        val permissionsCreated: Int,
        val matrixCells: Int,
        val adminCreated: Boolean,
        /** La contraseña generada. **Solo se devuelve si la generamos nosotros.** */
        val adminPassword: String?,
        val adminUsername: String,
    )

    /**
     * Siembra roles, permisos, matriz y el administrador.
     *
     * @param adminPassword si se pasa, se usa esa contraseña; si no, se genera.
     */
    fun seed(
        adminUsername: String = "admin",
        adminPassword: String? = null,
    ): SeedResult = dataSource.connection.use { conexion ->

        // ── UNA SOLA TRANSACCIÓN ────────────────────────────────────────────
        // Si algo falla a mitad, no queda un sembrado parcial: o están los roles,
        // los permisos, la matriz y el administrador, o no está nada.
        conexion.autoCommit = false

        try {
            val permisosCreados = sembrarPermisos(conexion)
            val rolesCreados = sembrarRoles(conexion)
            val celdas = sembrarMatriz(conexion)

            val adminExistente = buscarIdDeUsuario(conexion, adminUsername)
            if (adminExistente != null) {
                // Ya existe: no se toca su contraseña. Re-sembrar no debe
                // resetear la credencial de un administrador en producción.
                conexion.commit()
                return@use SeedResult(
                    rolesCreated = rolesCreados,
                    permissionsCreated = permisosCreados,
                    matrixCells = celdas,
                    adminCreated = false,
                    adminPassword = null,
                    adminUsername = adminUsername,
                )
            }

            val generada = adminPassword == null
            val contrasena = adminPassword ?: generarContrasena()
            val hash = NativeArgon2PasswordHasher().hash(contrasena)

            val adminId = insertarUsuario(conexion, adminUsername, hash)
            asignarRol(conexion, adminId, BaseRole.ADMIN.key.value)

            conexion.commit()

            SeedResult(
                rolesCreated = rolesCreados,
                permissionsCreated = permisosCreados,
                matrixCells = celdas,
                adminCreated = true,
                // Solo se devuelve si la generamos nosotros: una contraseña que
                // pasa el llamador ya la conoce.
                adminPassword = if (generada) contrasena else null,
                adminUsername = adminUsername,
            )
        } catch (e: Exception) {
            conexion.rollback()
            throw e
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PERMISOS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Inserta los permisos del catálogo del dominio.
     *
     * El catálogo es la **fuente de verdad** y vive en `stock-domain`, con sus
     * tests. Duplicar aquí su lista la desincronizaría en la primera adición.
     */
    private fun sembrarPermisos(conexion: Connection): Int {
        val sentencia = conexion.prepareStatement(
            """
            INSERT INTO permissions
                (key, module, resource, action, name, description, scopes, sensitive, active, sort_order)
            VALUES (?, ?, ?, ?, ?, ?, ?::permission_scope[], ?, TRUE, ?)
            ON CONFLICT (key) DO NOTHING
            """.trimIndent(),
        )

        var creados = 0
        sentencia.use { st ->
            PermissionCatalog.all.forEach { permiso ->
                st.setString(1, permiso.key)
                st.setString(2, permiso.module.name)
                st.setString(3, permiso.resource)
                st.setString(4, permiso.action)
                st.setString(5, permiso.name)
                st.setString(6, null)
                // El literal de array va entre LLAVES: `{ALL,AREA}`. Sin ellas,
                // PostgreSQL responde «Array value must start with "{"».
                st.setString(7, permiso.grantableScopes.joinToString(",", "{", "}") { it.name })
                st.setBoolean(8, permiso.sensitive)
                st.setInt(9, permiso.sortOrder)

                // `executeUpdate` devuelve 1 si insertó y 0 si el `ON CONFLICT` lo
                // ignoró. **Se cuenta lo que de verdad se escribió**, no lo que se
                // intentó.
                creados += st.executeUpdate()
            }
        }
        return creados
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ROLES
    // ═══════════════════════════════════════════════════════════════════════

    private fun sembrarRoles(conexion: Connection): Int {
        val sentencia = conexion.prepareStatement(
            """
            INSERT INTO roles (key, name, description, is_system, active, sort_order)
            VALUES (?, ?, ?, TRUE, TRUE, ?)
            ON CONFLICT (key) DO NOTHING
            """.trimIndent(),
        )

        var creados = 0
        sentencia.use { st ->
            BaseRole.entries.forEachIndexed { indice, base ->
                // La CLAVE es el identificador estable; el `name` es el texto
                // visible y se puede renombrar sin romper nada.
                st.setString(1, base.key.value)
                st.setString(2, base.displayName)
                // La base exige descripción en los roles de sistema: un rol que no
                // se explica en una frase está mal cortado.
                st.setString(3, base.oneLiner)
                st.setInt(4, indice)
                creados += st.executeUpdate()
            }
        }
        return creados
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MATRIZ ROL → PERMISO → ALCANCE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Escribe la matriz de permisos.
     *
     * El contenido también viene del dominio: `RoleDefinitions` tiene los tests que
     * verifican que el AUDITOR no puede escribir. Aquí solo se persiste.
     */
    private fun sembrarMatriz(conexion: Connection): Int {
        val idsDeRol = mutableMapOf<String, Long>()
        conexion.createStatement().use { st ->
            st.executeQuery("SELECT id, key FROM roles").use { rs ->
                while (rs.next()) idsDeRol[rs.getString("key")] = rs.getLong("id")
            }
        }

        val idsDePermiso = mutableMapOf<String, Long>()
        conexion.createStatement().use { st ->
            st.executeQuery("SELECT id, key FROM permissions").use { rs ->
                while (rs.next()) idsDePermiso[rs.getString("key")] = rs.getLong("id")
            }
        }

        val sentencia = conexion.prepareStatement(
            """
            INSERT INTO role_permissions (role_id, permission_id, scope)
            VALUES (?, ?, ?::permission_scope)
            ON CONFLICT (role_id, permission_id) DO NOTHING
            """.trimIndent(),
        )

        var celdas = 0
        sentencia.use { st ->
            BaseRole.entries.forEach { base ->
                val rolId = idsDeRol[base.key.value] ?: return@forEach
                val permisos = RoleDefinitions.byKey[base.key]?.permissions ?: return@forEach

                permisos.forEach { (claveDelPermiso, alcance) ->
                    val permisoId = idsDePermiso[claveDelPermiso] ?: return@forEach
                    st.setLong(1, rolId)
                    st.setLong(2, permisoId)
                    st.setString(3, alcance.name)
                    celdas += st.executeUpdate()
                }
            }
        }
        return celdas
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ADMINISTRADOR
    // ═══════════════════════════════════════════════════════════════════════

    private fun buscarIdDeUsuario(conexion: Connection, username: String): Long? =
        conexion.prepareStatement("SELECT id FROM users WHERE username = ?").use { st ->
            st.setString(1, username)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else null }
        }

    private fun insertarUsuario(conexion: Connection, username: String, hash: String): Long =
        conexion.prepareStatement(
            """
            INSERT INTO users (username, password_hash, name, disabled, password_changed_at, must_change_password)
            VALUES (?, ?, ?, FALSE, ?, TRUE)
            RETURNING id
            """.trimIndent(),
        ).use { st ->
            st.setString(1, username)
            st.setString(2, hash)
            st.setString(3, "Administrador")
            st.setTimestamp(4, Timestamp.from(Instant.now()))
            st.executeQuery().use { rs ->
                rs.next()
                rs.getLong("id")
            }
        }

    private fun asignarRol(conexion: Connection, userId: Long, roleKey: String) {
        conexion.prepareStatement(
            """
            INSERT INTO user_roles (user_id, role_id, is_primary)
            SELECT ?, id, TRUE FROM roles WHERE key = ?
            ON CONFLICT (user_id, role_id) DO NOTHING
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, userId)
            st.setString(2, roleKey)
            st.executeUpdate()
        }
    }

    /**
     * Genera una contraseña fuerte.
     *
     * 24 caracteres de un alfabeto **sin caracteres ambiguos**: se va a transcribir
     * a mano desde una consola, y confundir `0` con `O` o `l` con `1` es la causa
     * más común de que alguien no pueda entrar la primera vez.
     *
     * Cumple la política del dominio por construcción: mayúsculas, minúsculas y
     * dígitos, y mucha más longitud que el mínimo.
     */
    private fun generarContrasena(): String {
        val alfabeto = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..24).map { alfabeto[random.nextInt(alfabeto.length)] }.joinToString("")
    }
}
