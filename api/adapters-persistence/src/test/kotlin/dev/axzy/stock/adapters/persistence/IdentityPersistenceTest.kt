package dev.axzy.stock.adapters.persistence

import dev.axzy.stock.adapters.persistence.identity.JdbcAccessCatalogStore
import dev.axzy.stock.adapters.persistence.identity.JdbcUserAccounts
import dev.axzy.stock.application.identity.BootstrapIdentity
import dev.axzy.stock.application.identity.MissingBootstrapPassword
import dev.axzy.stock.domain.access.BaseRole
import dev.axzy.stock.domain.access.PermissionCatalog
import dev.axzy.stock.domain.access.PermissionScope
import dev.axzy.stock.domain.access.RoleDefinitions
import dev.axzy.stock.testing.FakePasswordHasher
import dev.axzy.stock.testing.TestDatabase
import dev.axzy.stock.testing.TestPersistence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class IdentityPersistenceTest {

    private val db = TestPersistence()
    private val store = JdbcAccessCatalogStore(db.jdbc, db.json)
    private val users = JdbcUserAccounts(db.jdbc)
    private val hasher = FakePasswordHasher()

    // Las claves de rol son las del dominio (`JEFE_BODEGA`, …): se toman de allí, no se escriben a mano.
    private val manager = BaseRole.WAREHOUSE_MANAGER.key.value
    private val operations = BaseRole.OPERATIONS_MANAGER.key.value
    private val auditor = BaseRole.AUDITOR.key.value

    private fun bootstrap(password: String? = "Almacen-Seguro-2026") =
        BootstrapIdentity(store, hasher, adminUsername = "admin", adminPassword = password)

    @BeforeEach
    fun clean() = TestDatabase.reset()

    @Test
    fun `el primer arranque siembra el catalogo, los roles base y el administrador`() {
        val result = bootstrap().execute()

        assertThat(result.permissionsSynced).isEqualTo(PermissionCatalog.all.size)
        assertThat(result.rolesCreated).isEqualTo(BaseRole.entries.size)
        assertThat(result.grantsCreated).isEqualTo(RoleDefinitions.baseRoles.sumOf { role -> role.permissions.count { it.value.grants } })
        assertThat(result.adminCreated).isTrue()
        assertThat(result.versions.matrix).isEqualTo(1)
        assertThat(result.versions.policies).isEqualTo(1)

        val admin = users.findByUsername("admin")!!
        assertThat(admin.mustChangePassword).isTrue()
        assertThat(hasher.verify("Almacen-Seguro-2026", admin.passwordHash)).isTrue()
    }

    @Test
    fun `arrancar otra vez no cambia nada - el sembrado es idempotente`() {
        bootstrap().execute()

        val second = bootstrap().execute()

        assertThat(second.permissionsSynced).isZero()
        assertThat(second.rolesCreated).isZero()
        assertThat(second.grantsCreated).isZero()
        assertThat(second.adminCreated).isFalse()
        assertThat(second.versions.matrix).isEqualTo(1)
        assertThat(db.jdbc.sql("SELECT count(*) FROM config_versions").query(Int::class.java).single()).isEqualTo(2)
    }

    @Test
    fun `todo el sembrado son sentencias por conjunto, no una por permiso`() {
        val statements = db.statements.during { bootstrap().execute() }

        // 3 sincronizaciones + matriz actual + 2 versiones (lectura y escritura cada una) + usuarios.
        assertThat(statements).isLessThanOrEqualTo(12)
    }

    @Test
    fun `una celda que cambio un administrador no se pisa, y la matriz gana una version`() {
        bootstrap().execute()
        db.asOwner.sql(
            """
            UPDATE role_permissions SET scope = 'OWN'
            WHERE role_id = (SELECT id FROM roles WHERE key = 'AUDITOR')
              AND permission_id = (SELECT id FROM permissions WHERE key = 'audit.view')
            """.trimIndent(),
        ).update()

        val result = bootstrap().execute()

        val scope = db.jdbc.sql(
            """
            SELECT rp.scope::text FROM role_permissions rp
            JOIN roles r ON r.id = rp.role_id JOIN permissions p ON p.id = rp.permission_id
            WHERE r.key = 'AUDITOR' AND p.key = 'audit.view'
            """.trimIndent(),
        ).query(String::class.java).single()
        assertThat(scope).isEqualTo("OWN")
        assertThat(result.grantsCreated).isZero()
        assertThat(result.versions.matrix).isEqualTo(2)
    }

    @Test
    fun `sin usuarios y sin contrasena inicial, el arranque falla diciendo que falta`() {
        assertThatThrownBy { bootstrap(password = null).execute() }
            .isInstanceOf(MissingBootstrapPassword::class.java)
            .hasMessageContaining("ADMIN_BOOTSTRAP_PASSWORD")
        assertThat(store.countUsers()).isZero()
    }

    @Test
    fun `una contrasena inicial debil no crea al administrador`() {
        assertThatThrownBy { bootstrap(password = "admin123").execute() }
            .isInstanceOf(MissingBootstrapPassword::class.java)
        assertThat(store.countUsers()).isZero()
    }

    @Test
    fun `el actor trae sus roles, el alcance de cada permiso y sus bodegas`() {
        bootstrap().execute()
        val userId = store.createUser("jperez", "Juan Pérez", "x", mustChangePassword = false, roleKeys = listOf(manager))
        db.jdbc.sql("INSERT INTO user_warehouse_scopes (user_id, warehouse_id) VALUES (:id, 1)").param("id", userId).update()

        val snapshot = users.loadActor(userId)!!

        val expected = RoleDefinitions.byKey.getValue(BaseRole.WAREHOUSE_MANAGER.key).permissions.filterValues { it.grants }
        assertThat(snapshot.actor.username).isEqualTo("jperez")
        assertThat(snapshot.actor.displayName).isEqualTo("Juan Pérez")
        assertThat(snapshot.actor.user.active).isTrue()
        assertThat(snapshot.actor.roleKeys).containsExactly(manager)
        assertThat(snapshot.actor.user.roles.single().permissions).isEqualTo(expected)
        assertThat(snapshot.actor.user.warehouseIds).containsExactly(1L)
        assertThat(snapshot.passwordChangedAt).isNotNull()
    }

    @Test
    fun `R-P1 - cargar al actor son dos consultas, tenga un rol o tres`() {
        bootstrap().execute()
        val oneRole = store.createUser("uno", "Uno", "x", false, listOf(auditor))
        val threeRoles = store.createUser("tres", "Tres", "x", false, listOf(auditor, manager, operations))

        val withOne = db.statements.during { users.loadActor(oneRole) }
        val withThree = db.statements.during { users.loadActor(threeRoles) }

        assertThat(withOne).isEqualTo(2)
        assertThat(withThree).isEqualTo(2)
        assertThat(users.loadActor(threeRoles)!!.actor.roleKeys).hasSize(3)
    }

    @Test
    fun `una cuenta desactivada se carga como inactiva`() {
        bootstrap().execute()
        val userId = store.createUser("baja", "De Baja", "x", false, listOf(auditor))
        db.jdbc.sql("UPDATE users SET disabled = TRUE WHERE id = :id").param("id", userId).update()

        assertThat(users.loadActor(userId)!!.actor.user.active).isFalse()
    }

    @Test
    fun `cambiar la contrasena deja de exigir el cambio y anota cuando`() {
        bootstrap().execute()
        val admin = users.findByUsername("admin")!!
        val at = Instant.parse("2026-11-01T10:00:00Z")

        users.updatePassword(admin.id, "nuevo-hash", at)

        val updated = users.findById(admin.id)!!
        assertThat(updated.passwordHash).isEqualTo("nuevo-hash")
        assertThat(updated.mustChangePassword).isFalse()
        assertThat(updated.passwordChangedAt).isEqualTo(at)
    }

    @Test
    fun `un permiso que el rol tiene inactivo no llega al actor`() {
        bootstrap().execute()
        val userId = store.createUser("ana", "Ana", "x", false, listOf(auditor))
        db.asOwner.sql("UPDATE permissions SET active = FALSE WHERE key = 'audit.view'").update()

        val permissions = users.loadActor(userId)!!.actor.user.roles.single().permissions

        assertThat(permissions).doesNotContainKey("audit.view")
        assertThat(permissions.values).allMatch { it != PermissionScope.NONE }
    }
}
