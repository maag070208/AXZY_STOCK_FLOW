package dev.axzy.stock.app

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import dev.axzy.stock.adapters.web.http.AuthenticatedOnly
import dev.axzy.stock.adapters.web.http.PublicRoute
import dev.axzy.stock.adapters.web.http.ServiceKeyOnly
import dev.axzy.stock.adapters.web.http.RequiresPermission
import dev.axzy.stock.domain.access.PermissionCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RestController

/**
 * I5 over the compiled classes: **every route declares what it requires**.
 *
 * `RoutePermissionAudit` does this same check at startup and brings the API down if it
 * fails. This test repeats the check over the bytecode so that the failure shows
 * at compile time and not at deploy, and adds the half that startup cannot
 * check on its own: that **each declared permission exists in the catalog**.
 */
class RoutePermissionTest {

    private val classes: List<JavaClass> = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("dev.axzy.stock.adapters.web")
        .filter { it.isAnnotatedWith(RestController::class.java) }

    private val mappingAnnotations = listOf(
        GetMapping::class.java,
        PostMapping::class.java,
        PutMapping::class.java,
        PatchMapping::class.java,
        DeleteMapping::class.java,
    )

    @Test
    fun `there are no controllers without routes - the list cannot end up empty by accident`() {
        assertThat(classes.map { it.name }).isNotEmpty()
    }

    @Test
    fun `every route declares a permission, only identity, or that it is public`() {
        val undeclared = mutableListOf<String>()

        for (controller in classes) {
            for (method in controller.methods) {
                if (mappingAnnotations.none { method.isAnnotatedWith(it) }) continue
                val declarations = listOf(
                    RequiresPermission::class.java,
                    AuthenticatedOnly::class.java,
                    PublicRoute::class.java,
                    // A service route (the printer) declares that it is not called by a
                    // person: it is the fourth way of saying what a route requires.
                    ServiceKeyOnly::class.java,
                ).count { method.isAnnotatedWith(it) }
                if (declarations != 1) {
                    undeclared += "${controller.simpleName}.${method.name} ($declarations declaraciones)"
                }
            }
        }

        assertThat(undeclared).isEmpty()
    }

    @Test
    fun `every declared permission exists in the domain catalog`() {
        val declared = mutableListOf<String>()

        for (controller in classes) {
            for (method in controller.methods) {
                if (!method.isAnnotatedWith(RequiresPermission::class.java)) continue
                val permissions = method.getAnnotationOfType(RequiresPermission::class.java).value
                declared += permissions.map { "${controller.simpleName}.${method.name} → $it" }
            }
        }

        assertThat(declared).isNotEmpty()
        val unknown = declared.filterNot { PermissionCatalog.keys.contains(it.substringAfter("→ ")) }
        assertThat(unknown).isEmpty()
    }
}
