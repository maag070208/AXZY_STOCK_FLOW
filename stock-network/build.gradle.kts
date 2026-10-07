// ============================================================================
// stock-network — Cliente Ktor y DTOs de la API (KMP)
//
// Responsabilidad: hablar con el backend. DTOs serializables, cliente Ktor,
// inyección del token, refresh en 401 e Idempotency-Key en las escrituras.
//
// NO contiene reglas de negocio: eso vive en stock-domain. Este módulo
// traduce entre el transporte (JSON, HTTP) y el dominio.
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
            api(project(":stock-domain"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotest.assertions.core)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
