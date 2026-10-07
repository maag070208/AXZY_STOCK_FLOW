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
    // El plugin de Android para KMP de AGP 9 se aplica SIEMPRE, y el target lo
    // crea él a partir del bloque `android {}`. Ver stock-domain/build.gradle.kts:
    // aplicarlo condicionalmente deja el DSL sin accesores de tipo, y AGP valida
    // `compileSdk` en cuanto se aplica. El precio es que la configuración
    // necesita el SDK de Android; el beneficio, que el `actual` de Android se
    // compila de verdad en vez de quedarse como código muerto sin verificar.
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// ── TARGETS ────────────────────────────────────────────────────────────────
// JVM SIEMPRE: es donde corren los tests (con MockEngine, en milisegundos y sin
// emulador) y donde lo consume el backend.
//
// iOS es OPT-IN, con la MISMA propiedad que usa stock-domain, para que un solo
// flag compile todo el grafo:
//   ./gradlew -PenableIosTargets=true build
val enableIosTargets = providers.gradleProperty("enableIosTargets")
    .map(String::toBoolean)
    .getOrElse(false)

/** Compatibilidad con el nombre anterior de la propiedad. */
val enableNativeTargets = providers.gradleProperty("enableNativeTargets")
    .map(String::toBoolean)
    .getOrElse(false)

kotlin {
    jvm()

    if (enableIosTargets || enableNativeTargets) {
        iosArm64()
        iosSimulatorArm64()
    }

    android {
        namespace = "dev.axzy.stock.network"
        compileSdk {
            version = release(libs.versions.androidCompileSdk.get().toInt())
        }
        minSdk = libs.versions.androidMinSdk.get().toInt()
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
            // Para el `X-Client-Timezone`: la zona del dispositivo, en IANA. Se
            // toma del sistema y no de una constante porque el backend la usa
            // para decidir la fecha de negocio del turno de noche.
            implementation(libs.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotest.assertions.core)
            implementation(libs.coroutines.test)
            // MockEngine: sin él, el camino de error (401, 409, 422, 429) solo se
            // podría probar contra un servidor real, que es justo lo que no se
            // puede hacer en CI.
            implementation(libs.ktor.client.mock)
        }
        // Android y JVM comparten motor: OkHttp. Son source sets HERMANOS (la
        // jerarquía por defecto no hace que androidMain herede de jvmMain), así
        // que el `actual` está duplicado en los dos, a propósito: es más barato
        // que un source set intermedio solo para compartir ocho líneas.
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

// El reporte XML es la única prueba de que los tests se ejecutaron: Gradle
// informa «BUILD SUCCESSFUL» igual con cero tests.
tasks.withType<Test>().configureEach {
    reports {
        junitXml.required.set(true)
        html.required.set(true)
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
