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
 * I5 sobre las clases compiladas: **toda ruta declara qué exige**.
 *
 * `RoutePermissionAudit` hace esta misma comprobación al arrancar y tumba la API si
 * falla. Esta prueba repite el control sobre el bytecode para que el fallo se vea
 * al compilar y no al desplegar, y añade la mitad que el arranque no puede
 * comprobar sola: que **cada permiso declarado exista en el catálogo**.
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
    fun `no hay controladores sin rutas - la lista no puede quedar vacia por accidente`() {
        assertThat(classes.map { it.name }).isNotEmpty()
    }

    @Test
    fun `toda ruta declara permiso, solo identidad o que es publica`() {
        val undeclared = mutableListOf<String>()

        for (controller in classes) {
            for (method in controller.methods) {
                if (mappingAnnotations.none { method.isAnnotatedWith(it) }) continue
                val declarations = listOf(
                    RequiresPermission::class.java,
                    AuthenticatedOnly::class.java,
                    PublicRoute::class.java,
                    // Una ruta de servicio (la impresora) declara que no la llama una
                    // persona: es la cuarta forma de decir qué exige una ruta.
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
    fun `cada permiso declarado existe en el catalogo del dominio`() {
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
