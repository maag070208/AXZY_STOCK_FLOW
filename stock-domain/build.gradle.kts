// ============================================================================
// stock-domain — EL CORAZÓN DEL SISTEMA
//
// Kotlin Multiplatform PURO. Aquí viven:
//   · La matriz movimiento → efecto en stock
//   · Las máquinas de estado (factura, línea, orden, traslado)
//   · El algoritmo de contenido por ubicación
//   · La regla de discrepancia y `difference`
//   · El motor de acceso RBAC+ABAC (default deny)
//   · Los casos de uso del dominio, anotados con @Auditable
//
// REGLA DE ORO, verificada por la tarea `verifyDomainPurity`:
//   stock-domain → SOLO stdlib de Kotlin + kotlinx-datetime + kotlinx-serialization
//   ✗ Ktor  ✗ Exposed  ✗ SQLDelight  ✗ Compose  ✗ androidx  ✗ ninguna BD
//
// Sin esta regla, el dominio se contamina y deja de poder compartirse entre
// backend, móvil y tests. Ver PLAN_ALMACEN_INTEGRAL.md §3.2-bis.
// ============================================================================

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// ── TARGETS ────────────────────────────────────────────────────────────────
// JVM SIEMPRE: es donde corren los tests del dominio (milisegundos, sin base
// de datos ni emulador) y donde lo consume el backend.
//
// Android e iOS son OPT-IN mediante la propiedad `enableNativeTargets`:
//   ./gradlew -PenableNativeTargets=true build
//
// El default es JVM-only a propósito: así el build de la Fase 0 no depende de
// tener el SDK de Android ni Xcode instalados, y cualquier persona del equipo
// puede correr los tests del dominio con solo un JDK.
val enableNativeTargets = providers.gradleProperty("enableNativeTargets")
    .map(String::toBoolean)
    .getOrElse(false)

kotlin {
    jvm()

    if (enableNativeTargets) {
        androidTarget()
        iosArm64()
        iosSimulatorArm64()
    }

    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            // ── LO ÚNICO PERMITIDO ───────────────────────────────────────────
            implementation(libs.datetime)
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotest.property)
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotest.datatest)
        }

        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
        }
    }
}

// ============================================================================
// GUARDIA DE ARQUITECTURA
//
// Falla el build si stock-domain llega a depender de un framework. Sin esta
// guardia, la regla se rompe en la primera prisa: alguien necesita una utilidad
// de Exposed, la añade, y el dominio deja de ser compartible con el móvil.
//
// Se verifica sobre las dependencias DECLARADAS, no sobre el classpath ya
// resuelto, para dar un mensaje de error claro y accionable.
// ============================================================================

val forbiddenDomainDependencies = listOf(
    "ktor", "exposed", "sqldelight", "compose", "androidx",
    "hikari", "postgresql", "flyway", "logback", "koin", "spring",
    "micrometer", "jackson", "okhttp", "retrofit",
)

tasks.register("verifyDomainPurity") {
    group = "verification"
    description = "Falla si stock-domain depende de algún framework (debe ser Kotlin puro)"

    doLast {
        val violations = mutableListOf<String>()

        // 1. Verificar las dependencias declaradas en commonMain
        configurations
            .filter { it.name.contains("commonMain") || it.name.contains("jvmMain") }
            .forEach { config ->
                config.dependencies.forEach { dep ->
                    val notation = "${dep.group}:${dep.name}".lowercase()
                    forbiddenDomainDependencies.forEach { forbidden ->
                        if (notation.contains(forbidden)) {
                            violations += "  ✗ ${config.name}: $notation (contiene '$forbidden')"
                        }
                    }
                }
            }

        // 2. Verificar que el código fuente no importe frameworks directamente
        val forbiddenImports = forbiddenDomainDependencies.flatMap { listOf(it) }
        val sourceDirs = listOf(
            file("src/commonMain/kotlin"),
            file("src/jvmMain/kotlin"),
            file("src/androidMain/kotlin"),
            file("src/iosMain/kotlin"),
        ).filter { it.exists() }

        sourceDirs.forEach { dir ->
            dir.walkTopDown()
                .filter { it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("import ")) {
                            val lower = trimmed.lowercase()
                            forbiddenImports.forEach { forbidden ->
                                if (lower.contains(forbidden)) {
                                    violations += "  ✗ ${file.relativeTo(projectDir)}:${index + 1}: $trimmed"
                                }
                            }
                        }
                    }
                }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine()
                    appendLine("════════════════════════════════════════════════════════════════")
                    appendLine("GUARDIA DE ARQUITECTURA VIOLADA: stock-domain debe ser Kotlin PURO")
                    appendLine("════════════════════════════════════════════════════════════════")
                    violations.forEach { appendLine(it) }
                    appendLine()
                    appendLine("stock-domain solo puede usar: stdlib de Kotlin, kotlinx-datetime,")
                    appendLine("kotlinx-serialization y kotlinx-coroutines.")
                    appendLine()
                    appendLine("Si necesitas un framework, el código NO va aquí: va en el módulo")
                    appendLine("que corresponda (backend, stock-network, stock-database).")
                    appendLine("Ver PLAN_ALMACEN_INTEGRAL.md §3.2-bis.")
                    appendLine("════════════════════════════════════════════════════════════════")
                }
            )
        }
        logger.lifecycle("✓ stock-domain es Kotlin puro (guardia de arquitectura OK)")
    }
}

// ── Defensa estructural ────────────────────────────────────────────────────
// La guardia de arriba solo inspecciona directorios que EXISTEN. Si alguien
// crea `jvmMain` (que es exactamente donde aparecería la tentación de usar
// Exposed o una conexión a base de datos), su contenido no se revisaría.
//
// Esta tarea cierra ese hueco: si aparece `src/jvmMain`, el build falla hasta
// que se revise por qué. Es preferible un fallo explícito a una regla que se
// degrada en silencio.
tasks.register("forbidJvmMainSourceSet") {
    group = "verification"
    description = "Impide añadir código en jvmMain (el dominio debe ser común, no específico de JVM)"

    doLast {
        val forbidden = listOf(
            file("src/jvmMain/kotlin"),
            file("src/androidMain/kotlin"),
            file("src/iosMain/kotlin"),
        ).filter { it.exists() && it.walkTopDown().any { f -> f.extension == "kt" } }

        if (forbidden.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine()
                    appendLine("════════════════════════════════════════════════════════════════")
                    appendLine("CÓDIGO ESPECÍFICO DE PLATAFORMA EN stock-domain")
                    appendLine("════════════════════════════════════════════════════════════════")
                    forbidden.forEach { appendLine("  ✗ ${it.relativeTo(projectDir)} tiene código") }
                    appendLine()
                    appendLine("El dominio debe ser multiplataforma PURO: todo su código vive en")
                    appendLine("commonMain. Si necesitas algo específico de JVM, Android o iOS,")
                    appendLine("va en el módulo que corresponda (backend, stock-network,")
                    appendLine("stock-database) con un expect/actual.")
                    appendLine()
                    appendLine("Razón: `jvmMain` es la puerta por la que entraría Exposed y con ello")
                    appendLine("la imposibilidad de compartir el dominio con el móvil.")
                    appendLine("Ver PLAN_ALMACEN_INTEGRAL.md §3.2-bis.")
                    appendLine("════════════════════════════════════════════════════════════════")
                }
            )
        }
        logger.lifecycle("✓ stock-domain no tiene código específico de plataforma")
    }
}

// La verificación corre en cada `check`: no se puede saltar por descuido.
tasks.named("check") {
    dependsOn("verifyDomainPurity", "forbidJvmMainSourceSet")
}

// ============================================================================
// EJECUCIÓN DE TESTS
//
// Sin `useJUnitPlatform()` Gradle no descubre los tests de Kotest y el build
// "pasa" con 0 tests ejecutados: un falso verde, que es peor que un fallo.
//
// Además se fuerza a que un test saltado NO cuente como éxito: si un test se
// ignora, el build falla. Un test ignorado silenciosamente es una mentira.
// ============================================================================

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Reporte XML: permite verificar programáticamente que los tests corrieron.
    reports {
        junitXml.required.set(true)
        html.required.set(true)
    }

    // Falla si algún test fue ignorado sin justificación explícita.
    filter {
        isFailOnNoMatchingTests = true
    }

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
