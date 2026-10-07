// ============================================================================
// stock-database — Caché offline y cola de sincronización (KMP, SQLDelight)
//
// Responsabilidad: permitir operar sin señal en el piso del almacén y
// sincronizar después **sin duplicar movimientos** (idempotencia por
// client_uuid). Se implementa en la Fase 6.
//
// Nota: stock-domain NO depende de este módulo. El dominio define los puertos
// (interfaces) y este módulo los implementa.
// ============================================================================

plugins {
    // Ver el comentario largo en stock-network/build.gradle.kts: AGP se aplica
    // siempre porque el plugin crea el target de Android a partir del bloque
    // `android {}`, y con eso el `actual` de Android se compila de verdad.
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
    // Genera el código de acceso a partir de los `.sq` y aplica los `.sqm`.
    alias(libs.plugins.sqldelight)
}

// ── TARGETS ────────────────────────────────────────────────────────────────
// JVM SIEMPRE: es donde corren los tests, con el driver JDBC sobre un SQLite
// real (archivo temporal), que es la única forma de probar que la cola
// SOBREVIVE a cerrar y reabrir la base.
//
// iOS es OPT-IN, con la misma propiedad que stock-domain:
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
        namespace = "dev.axzy.stock.database"
        compileSdk {
            version = release(libs.versions.androidCompileSdk.get().toInt())
        }
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            api(project(":stock-domain"))
            implementation(libs.coroutines.core)
            // El reloj con el que la cola sella `creada_en` y `ultimo_intento_en`.
            // Es multiplataforma y ya lo usa el dominio, así que no añade nada
            // nuevo al grafo.
            implementation(libs.datetime)
            implementation(libs.sqldelight.runtime)
            // Convertir las consultas en Flow: la cola de sincronización se
            // observa desde la UI para mostrar cuántas operaciones quedan
            // pendientes, y eso no se hace con sondeos.
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
        jvmMain.dependencies {
            // SQLite embebido por JDBC. El backend usa PostgreSQL, pero la caché
            // offline del móvil es SQLite en las tres plataformas.
            implementation(libs.sqldelight.driver.sqlite)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.driver.sqlite)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.driver.android)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.driver.native)
        }
    }
}

sqldelight {
    databases {
        create("StockDatabase") {
            // El paquete de las consultas generadas (`StockQueries`). El
            // `StockDatabaseImpl` cae en un subpaquete derivado del nombre; da
            // igual, porque solo se usa a través de la fachada del módulo.
            packageName.set("dev.axzy.stock.database")
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
