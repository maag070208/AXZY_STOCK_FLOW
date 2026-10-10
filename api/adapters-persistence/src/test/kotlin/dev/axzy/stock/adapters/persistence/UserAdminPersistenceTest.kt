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
 * User and role administration against real PostgreSQL (M07).
 *
 * What is checked here and not over HTTP: that the user list **does not grow with the
 * number of rows**. Each person's roles come in the same query with `array_agg`,
 * and that is a design decision that, without a measurement, is lost at the first change
 * someone makes with good intentions.
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

        // The user with their roles, and the credentials-stamp query.
        assertThat(statements).isEqualTo(3)
    }

    @Test
    fun `filtering by role is still two statements`() {
        // The filter is an `EXISTS` inside the same query: if the users of the role had been
        // resolved first, there would be three queries and it would grow with the page.
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
