package dev.axzy.stock.app

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Las reglas de capas de docs/arquitectura/api-spring.md §1, comprobadas sobre las clases compiladas.
 *
 * El compilador ya impide la mayoría (cada módulo solo ve lo que declara). Esta prueba las repite
 * porque una dependencia transitiva o un `implementation` añadido con prisa las rompería sin que
 * nada dejara de compilar.
 */
class ArchitectureTest {

    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("dev.axzy.stock")

    private val frameworks = arrayOf(
        "org.springframework..", "java.sql..", "javax.sql..", "jakarta..", "tools.jackson..", "com.fasterxml..",
        "com.nimbusds..", "org.postgresql..", "org.slf4j..",
    )

    @Test
    fun `el dominio no depende de ningun framework ni de las capas de fuera`() {
        noClasses().that().resideInAPackage("dev.axzy.stock.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                *frameworks, "dev.axzy.stock.application..", "dev.axzy.stock.adapters..", "dev.axzy.stock.app..",
            )
            .check(classes)
    }

    @Test
    fun `la aplicacion depende solo del dominio - ni Spring, ni JDBC, ni JSON, ni adaptadores`() {
        noClasses().that().resideInAPackage("dev.axzy.stock.application..")
            .should().dependOnClassesThat().resideInAnyPackage(*frameworks, "dev.axzy.stock.adapters..", "dev.axzy.stock.app..")
            .check(classes)
    }

    @Test
    fun `los adaptadores no se conocen entre si`() {
        val adapters = listOf("web", "persistence", "security")
        for (adapter in adapters) {
            val others = adapters.filter { it != adapter }.map { "dev.axzy.stock.adapters.$it.." }.toTypedArray()
            noClasses().that().resideInAPackage("dev.axzy.stock.adapters.$adapter..")
                .should().dependOnClassesThat().resideInAnyPackage(*others)
                .because("un controlador llama a un caso de uso, nunca a un repositorio, y viceversa")
                .check(classes)
        }
    }

    @Test
    fun `nadie depende del modulo de arranque`() {
        noClasses().that().resideOutsideOfPackage("dev.axzy.stock.app..")
            .should().dependOnClassesThat().resideInAPackage("dev.axzy.stock.app..")
            .check(classes)
    }

    @Test
    fun `los objetos se crean en un solo lugar - solo app declara configuracion y beans`() {
        noClasses().that().resideOutsideOfPackage("dev.axzy.stock.app..")
            .should().beAnnotatedWith(Configuration::class.java)
            .check(classes)
        noMethods().that().areDeclaredInClassesThat().resideOutsideOfPackage("dev.axzy.stock.app..")
            .should().beAnnotatedWith(Bean::class.java)
            .check(classes)
    }

    @Test
    fun `solo la persistencia habla con la base de datos`() {
        noClasses().that().resideInAnyPackage("dev.axzy.stock.adapters.web..", "dev.axzy.stock.adapters.security..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..")
            .check(classes)
    }

    @Test
    fun `solo el adaptador web conoce HTTP`() {
        noClasses().that().resideInAnyPackage("dev.axzy.stock.adapters.persistence..", "dev.axzy.stock.adapters.security..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "org.springframework.http..", "jakarta.servlet..")
            .check(classes)
    }
}
