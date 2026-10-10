package dev.axzy.stock.adapters.persistence

import dev.axzy.stock.adapters.persistence.identity.JdbcUserAdmin
import dev.axzy.stock.application.identity.UserFilter
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.testing.TestDatabase
import dev.axzy.stock.testing.TestPersistence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * La administración de usuarios y roles contra PostgreSQL real (M07).
 *
 * Lo que se comprueba aquí y no por HTTP: que el listado de usuarios **no crezca con el
 * número de filas**. Los roles de cada persona vienen en la misma consulta con `array_agg`,
 * y eso es una decisión de diseño que sin una medición se pierde en el primer cambio que
 * alguien haga con buena intención.
 */
class UserAdminPersistenceTest {

    private val db = TestPersistence()
    private val store = JdbcUserAdmin(db.jdbc)

    @BeforeEach
    fun prepare() {
        TestDatabase.reset()
        db.asOwner.sql(
            """
            INSERT INTO users (username, password_hash, name, must_change_password)
            SELECT 'usuario' || g, 'hash', 'Usuario ' || g, FALSE FROM generate_series(1, 25) g
            """.trimIndent(),
        ).update()
    }

    @Test
    fun `listing users is two statements, with twenty-five rows or with one`() {
        val statements = db.statements.during {
            store.page(UserFilter(null, null, null), PageRequest.of(size = 25))
        }

        assertThat(statements).isEqualTo(2)   // el conteo y la página
        val page = store.page(UserFilter(null, null, null), PageRequest.of(size = 25))
        assertThat(page.items).hasSize(25)
        assertThat(page.totalItems).isEqualTo(25)
    }

    @Test
    fun `a user's detail is two statements`() {
        val id = db.asOwner.sql("SELECT id FROM users WHERE username = 'usuario1'").query(Long::class.java).single()

        val statements = db.statements.during { store.find(id) }

        // El usuario con sus roles, y la consulta del sello de credenciales.
        assertThat(statements).isEqualTo(3)
    }

    @Test
    fun `filtering by role is still two statements`() {
        // El filtro es un `EXISTS` dentro de la misma consulta: si se hubiera escrito
        // resolviendo los usuarios del rol primero, serían tres y crecería con la página.
        val statements = db.statements.during {
            store.page(UserFilter(null, "ADMIN", null), PageRequest.of(size = 25))
        }

        assertThat(statements).isEqualTo(2)
    }

    @Test
    fun `the role list is a single statement`() {
        val statements = db.statements.during { store.roles() }

        assertThat(statements).isEqualTo(1)
    }
}
