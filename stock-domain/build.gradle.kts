// ============================================================================
// stock-domain — LAS REGLAS DE NEGOCIO, COMPARTIDAS POR LA API Y LA APP
//
// Kotlin Multiplatform PURO. Aquí viven la matriz movimiento → efecto en stock, las máquinas de
// estado (factura, línea, orden, traslado), la regla de discrepancia, el motor de acceso RBAC+ABAC
// y el modelo de auditoría.
//
// REGLA, verificada por `verifyDomainPurity`:
//   stock-domain → SOLO stdlib de Kotlin + kotlinx-datetime + kotlinx-serialization + coroutines
//   ✗ Spring  ✗ JDBC  ✗ Compose  ✗ androidx  ✗ ninguna base de datos
//
// Decisión D6 (docs/plan/PLAN-REHACER.md): una sola copia de cada regla.
// ============================================================================

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// iOS es opt-in: `./gradlew -PenableIosTargets=true build`. Compilarlo exige Xcode, y la API no
// debe depender de eso. Android NO es un target aquí: una app Android consume el artefacto JVM de
// este módulo, así que el dominio se compila sin el Android Gradle Plugin ni el SDK.
val enableIosTargets = providers.gradleProperty("enableIosTargets")
    .map(String::toBoolean)
    .getOrElse(false)

kotlin {
    jvm {
        compilerOptions {
            // Bytecode de Java 17, y solo el API de Java 17: lo puede cargar la API (JDK 25) y
            // también Android. Se fija el destino y no el JDK con que se compila, para no exigir
            // tener instalado un JDK 17 además del que ejecuta el build.
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjdk-release=17")
        }
    }

    if (enableIosTargets) {
        // Compilar a Kotlin/Native valida que el dominio no depende de la JVM.
        iosArm64()
        iosSimulatorArm64()
    }

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

        // jvmTest: la guardia de las migraciones SQL y los tests que leen recursos del classpath.
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

        // 1. Verificar TODAS las configuraciones de código de producción.
        //
        // SE INCLUYEN androidMain e iosMain A PROPÓSITO, y antes no estaban.
        // Ésa era una incoherencia real de esta guardia: revisaba las
        // dependencias solo de `commonMain` y `jvmMain`, pero escaneaba los
        // imports de los cuatro source sets. Es decir, se podía declarar CameraX
        // en `androidMain` sin que la guardia dijera nada, y el error aparecía
        // después, al escribir el primer `import androidx.`.
        //
        // La regla que se quiere imponer es simple: **el dominio no depende de
        // frameworks de plataforma, en NINGÚN source set**. Es lo que permite
        // que el mismo código tome las mismas decisiones en el backend, en
        // Android y en iOS.
        configurations
            .filter {
                it.name.contains("Main") &&
                    !it.name.contains("Test") &&
                    !it.name.contains("Metadata")
            }
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

        // 3. Verificar que no se usen anotaciones o APIs SOLO de JVM.
        //
        // ── POR QUÉ EXISTE ESTA COMPROBACIÓN ────────────────────────────────
        // La guardia anterior revisaba DEPENDENCIAS e IMPORTS. Ninguna de las dos
        // detectó que el código usaba `@JvmInline` sin calificar, que es una
        // anotación de la JVM: el dominio compilaba en JVM y **fallaba al
        // compilar para iOS** con «Unresolved reference 'JvmInline'».
        //
        // Lo encontró Kotlin/Native, no la guardia. Ésta es la corrección: la
        // guardia ahora detecta lo mismo que encontró el compilador, sin esperar
        // a tener Xcode y el toolchain de iOS instalados.
        //
        // La forma correcta es `@kotlin.jvm.JvmInline`, que sí es multiplataforma.
        val anotacionesSoloJvm = listOf(
            "@JvmInline", "@JvmName", "@JvmStatic", "@JvmField",
            "@JvmOverloads", "@JvmSuppressWildcards", "@JvmWildcard",
            "@Synchronized", "@Strictfp", "@Transient", "@Volatile",
        )

        sourceDirs.forEach { dir ->
            dir.walkTopDown()
                .filter { it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        val trimmed = line.trim()
                        anotacionesSoloJvm.forEach { anotacion ->
                            // Se excluye la forma calificada, que sí es correcta.
                            val calificada = "@kotlin.jvm." + anotacion.removePrefix("@")
                            if (trimmed.contains(anotacion + " ") ||
                                trimmed.endsWith(anotacion) ||
                                trimmed.contains(anotacion + "(")
                            ) {
                                if (!trimmed.contains(calificada)) {
                                    violations += "  ✗ ${file.relativeTo(projectDir)}:${index + 1}: " +
                                        "'$anotacion' es una anotación SOLO de JVM. Usa " +
                                        "'@kotlin.jvm.${anotacion.removePrefix("@")}' o elimínala."
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
                    appendLine("que corresponda de la API (api/application o api/adapters-*).")
                    appendLine()
                    appendLine("Un uso de JVM sin calificar compila en JVM y ROMPE el build de iOS.")
                    appendLine("Se detecta aquí para no depender de tener Xcode instalado.")
                    appendLine("Ver docs/historico/PLAN_ALMACEN_INTEGRAL.md §3.2-bis.")
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
                    appendLine("va en el módulo de la API o de la app que corresponda.")
                    appendLine()
                    appendLine("Razón: `jvmMain` es la puerta por la que entraría Exposed y con ello")
                    appendLine("la imposibilidad de compartir el dominio con el móvil.")
                    appendLine("Ver docs/historico/PLAN_ALMACEN_INTEGRAL.md §3.2-bis.")
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
