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
 * The layer rules of docs/arquitectura/api-spring.md §1, checked over the compiled classes.
 *
 * The compiler already prevents most of it (each module only sees what it declares). This test repeats them
 * because a transitive dependency or an `implementation` added in a hurry would break them without
 * anything failing to compile.
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
    fun `the domain depends on no framework nor on the outer layers`() {
        noClasses().that().resideInAPackage("dev.axzy.stock.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                *frameworks, "dev.axzy.stock.application..", "dev.axzy.stock.adapters..", "dev.axzy.stock.app..",
            )
            .check(classes)
    }

    @Test
    fun `the application depends only on the domain - neither Spring, nor JDBC, nor JSON, nor adapters`() {
        noClasses().that().resideInAPackage("dev.axzy.stock.application..")
            .should().dependOnClassesThat().resideInAnyPackage(*frameworks, "dev.axzy.stock.adapters..", "dev.axzy.stock.app..")
            .check(classes)
    }

    @Test
    fun `the adapters do not know each other`() {
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
    fun `nobody depends on the bootstrap module`() {
        noClasses().that().resideOutsideOfPackage("dev.axzy.stock.app..")
            .should().dependOnClassesThat().resideInAPackage("dev.axzy.stock.app..")
            .check(classes)
    }

    @Test
    fun `objects are created in a single place - only app declares configuration and beans`() {
        noClasses().that().resideOutsideOfPackage("dev.axzy.stock.app..")
            .should().beAnnotatedWith(Configuration::class.java)
            .check(classes)
        noMethods().that().areDeclaredInClassesThat().resideOutsideOfPackage("dev.axzy.stock.app..")
            .should().beAnnotatedWith(Bean::class.java)
            .check(classes)
    }

    @Test
    fun `only persistence talks to the database`() {
        noClasses().that().resideInAnyPackage("dev.axzy.stock.adapters.web..", "dev.axzy.stock.adapters.security..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..")
            .check(classes)
    }

    @Test
    fun `only the web adapter knows HTTP`() {
        noClasses().that().resideInAnyPackage("dev.axzy.stock.adapters.persistence..", "dev.axzy.stock.adapters.security..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "org.springframework.http..", "jakarta.servlet..")
            .check(classes)
    }
}
