package dev.axzy.stock.backend.tools

import dev.axzy.stock.backend.identity.NativeArgon2PasswordHasher
import java.sql.DriverManager

/**
 * Crea un usuario supervisor para poder probar la regla B1 de punta a punta.
 *
 * **No es un test ni parte del producto**: es la herramienta que hacía falta para
 * comprobar que la segregación de funciones deja aprobar a OTRA persona, que es la
 * mitad de la regla que no se puede verificar con un solo usuario.
 */
fun main(args: Array<String>) {
    require(args.size == 4) { "Uso: <url> <usuario> <clave> <rol>" }
    val (url, usuario, clave, rol) = args

    val hasher = NativeArgon2PasswordHasher()
    val hash = hasher.hash(clave)

    DriverManager.getConnection(url).use { c ->
        c.autoCommit = true
        val userId = c.prepareStatement(
            """
            INSERT INTO users (username, password_hash, name, disabled, password_changed_at, must_change_password)
            VALUES (?, ?, ?, FALSE, now(), FALSE)
            ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash
            RETURNING id
            """.trimIndent(),
        ).use { st ->
            st.setString(1, usuario); st.setString(2, hash); st.setString(3, usuario)
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        val rolId = c.prepareStatement("SELECT id FROM roles WHERE key = ?").use { st ->
            st.setString(1, rol); st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        c.prepareStatement(
            "INSERT INTO user_roles (user_id, role_id, is_primary) VALUES (?, ?, TRUE) ON CONFLICT DO NOTHING",
        ).use { st -> st.setLong(1, userId); st.setLong(2, rolId); st.executeUpdate() }

        println("  usuario=$usuario id=$userId rol=$rol")
    }
}
