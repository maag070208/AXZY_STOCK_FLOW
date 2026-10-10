import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "dev.axzy.stock"
    version = "0.1.0"
}

// ── Convención de los módulos de la API ─────────────────────────────────────
// Los cinco módulos de `api/` comparten toolchain, opciones del compilador y la plataforma de
// versiones. Cada uno declara en su propio build.gradle.kts SOLO de qué depende: es ahí donde se
// lee la regla de capas (docs/arquitectura/api-spring.md §1).
val springBootBom = libs.spring.boot.bom
val kotlinVersion = libs.versions.kotlin.get()

configure(subprojects.filter { it.path.startsWith(":api:") }) {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
            // Trata las anotaciones de nulabilidad de Spring como tipos de Kotlin: un `null` que
            // Spring declara imposible deja de compilar.
            freeCompilerArgs.addAll("-Xjsr305=strict")
        }
    }

    dependencies {
        // La plataforma fija versiones; no añade ninguna dependencia por sí sola. Por eso puede
        // usarla también `application`, que no depende de Spring.
        "implementation"(platform(springBootBom))
        // El BOM de Spring Boot gestiona Kotlin 2.3.x; el proyecto compila con el del catálogo.
        "implementation"(platform("org.jetbrains.kotlin:kotlin-bom:$kotlinVersion"))

        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Un módulo sin pruebas descubiertas no es un verde: es un hueco.
        filter { isFailOnNoMatchingTests = true }

        // Las pruebas de integración levantan PostgreSQL con Testcontainers, que busca el socket
        // de Docker en /var/run/docker.sock. Colima, OrbStack y Rancher lo dejan en otra ruta: se
        // toma la del contexto de Docker activo para no pedirle a nadie que exporte variables.
        doFirst {
            if (System.getenv("DOCKER_HOST") == null) {
                val host = runCatching {
                    val process = ProcessBuilder("docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}")
                        .redirectErrorStream(true).start()
                    process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }
                }.getOrNull()
                if (host != null && host.startsWith("unix://") && host != "unix:///var/run/docker.sock") {
                    environment("DOCKER_HOST", host)
                    // La ruta del socket DENTRO de la máquina virtual, que es donde lo monta Ryuk.
                    environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
                }
            }
        }
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

tasks.register("verifyArchitecture") {
    group = "verification"
    description = "Verifica la pureza del dominio y las reglas de capas de la API"
    dependsOn(":stock-domain:verifyDomainPurity", ":api:app:test")
}
