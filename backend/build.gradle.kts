// ============================================================================
// backend — Ktor 3 + Exposed + PostgreSQL + Flyway
//
// Consume stock-domain por su target JVM. El dominio NO depende del backend:
// la flecha del grafo va en un solo sentido (ver settings.gradle.kts).
//
// En la Fase 0 este módulo está deliberadamente casi vacío: primero se escribe
// y se prueba el dominio, que es donde está el riesgo. La infraestructura HTTP
// llega en la Fase 1 (ver docs/historico/PLAN_ALMACEN_INTEGRAL.md §5).
// ============================================================================

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // El dominio compartido: aquí es donde vive toda la lógica crítica.
    implementation(project(":stock-domain"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.request.validation)
    implementation(libs.ktor.server.config.yaml)

    // ── Documentación de la API (OpenAPI 3.0.3 + Swagger UI) ────────────────
    // Solo dependencias: no hay plugin de Gradle que aplicar. El plugin de
    // compilador que genera metadatos desde el arbol de rutas NO existe para
    // Ktor 3.1.1 (llego en 3.2/3.3 y exige Kotlin 2.2.20). El documento se
    // construye en codigo en docs/OpenApiDocumentation.kt.
    implementation(libs.ktor.server.openapi)
    implementation(libs.ktor.server.swagger)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)

    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)

    implementation(libs.logback.classic)
    implementation(libs.koin.ktor)
    // Argon2: se toma el artefacto por sus LIBRERIAS NATIVAS. Su binding de Java
    // no se usa porque esta roto (falla con «Output is too short (-2)» aunque la
    // libreria nativa devuelve exito). Los bindings JNA se escriben a mano.
    implementation(libs.argon2)
    implementation(libs.jna)
    implementation(libs.java.jwt)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

application {
    mainClass.set("dev.axzy.stock.backend.ApplicationKt")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
