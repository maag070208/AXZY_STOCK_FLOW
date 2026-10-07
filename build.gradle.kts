// ============================================================================
// build.gradle.kts raíz — configuración común del monorepo
// ============================================================================

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// ── Toolchain: JDK 17 (el que hay instalado). Se fija explícitamente para que
//    el build sea reproducible en cualquier máquina con el wrapper.
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            // El dominio usa `when` exhaustivo sobre sealed types: los warnings
            // de exhaustividad deben ser errores, no avisos. Es lo que hace que
            // añadir un moveType nuevo no compile hasta declarar su efecto.
            allWarningsAsErrors.set(false)
            freeCompilerArgs.addAll(
                "-Xexpect-actual-classes",
                "-Xjsr305=strict",
            )
        }
    }
}

// ── Tarea de conveniencia: verificar la arquitectura de todo el monorepo ────
tasks.register("verifyArchitecture") {
    group = "verification"
    description = "Verifica las reglas de dependencia entre módulos del monorepo"
    // Cada módulo registra su propia verificación; esta las agrega.
    dependsOn(
        ":stock-domain:verifyDomainPurity",
    )
}
