// ============================================================================
// shared — Biblioteca KMP + Compose Multiplatform (Android + iOS)
//
// QUÉ ES Y QUÉ NO ES
//   · Es la UI COMPARTIDA (Android e iOS) y el puente a las plataformas.
//   · NO es el APK: el punto de entrada Android vive en `../androidApp`, que
//     aplica `com.android.application`. Este módulo es una BIBLIOTECA KMP.
//   · NO tiene DTOs ni cliente HTTP: eso vive en `stock-network`, que otro agente
//     construye en paralelo. Aquí solo hay interfaces de repositorio local y
//     datos de ejemplo, para que la app arranque y se pueda revisar sin backend.
//   · NO tiene reglas de negocio: el escáner, las máquinas de estado y los casos
//     de uso vienen de `stock-domain`. Duplicarlos aquí garantizaría que el móvil
//     y el backend decidan distinto, que es justo lo que el módulo compartido
//     existe para evitar (PLAN_ALMACEN_INTEGRAL.md §3.2-bis).
//
// VERTICAL SLICE, NO CAPAS (§3.4): todo lo de "capturar factura" vive en
// `feature/invoices/`, no repartido entre una carpeta `ui/` y otra `model/`
// globales. Así se ve de un golpe qué existe y se borra un flujo completo sin
// dejar huérfanos.
//
// ANTES ESTE ARCHIVO ERA `mobile-app/build.gradle.kts`
//
// La reestructuración movió `mobile-app/src/` a `mobile-app/shared/src/` para que
// `mobile-app` sea un build de KMP autocontenido con la MISMA forma que el
// proyecto de referencia (`shared/` + `androidApp/`), y no un submódulo del
// monorepo con un segundo build de Gradle anidado dentro. El paquete Kotlin NO
// cambia: sigue siendo `dev.axzy.stock.mobile`.
// ============================================================================

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Kotlin Multiplatform. La version la fija la clave `kotlin` del catalogo
    // (2.4.10), que es la MISMA que usa el ejemplo real de PuertoNuevoApp y la
    // minima que admite Compose Multiplatform 1.11.1.
    alias(libs.plugins.kotlin.multiplatform)
    // Los tres siguientes van por `id(...)` SIN versión: la versión la fija el
    // `pluginManagement` de `../settings.gradle.kts` y la raíz del build la
    // declara con `apply false`. Es el mismo patrón que el proyecto de
    // referencia `PuertoNuevoApp`, y el que evita que el compilador de Kotlin DSL
    // encuentre dos definiciones de `android {}`.
    //
    // Compose Multiplatform: el DSL `compose.*` y el empaquetado por target.
    id("org.jetbrains.compose")
    // El compilador de Compose. DEBE ir en la MISMA versión que el plugin de Kotlin
    // de arriba; si no, Compose Compiler genera bytecode que el compilador rechaza.
    id("org.jetbrains.kotlin.plugin.compose")
    // El plugin de Android para KMP de AGP 9. Reemplaza a `com.android.library`, que
    // no soporta Kotlin Multiplatform de forma nativa: con AGP 9 el target de
    // Android se declara DENTRO del bloque `kotlin {}`.
    id("com.android.kotlin.multiplatform.library")
}

// ── TARGETS ────────────────────────────────────────────────────────────────
// iOS es OPT-OUT: se compila por defecto porque `shared` es un módulo
// MULTIPLATAFORMA y sus `expect/actual` de iOS solo se validan compilando para
// iOS. Se puede desactivar —para no descargar el toolchain de Kotlin/Native, que
// son cientos de MB— con `-PenableIosTargets=false`.
//
//   ./gradlew :shared:jvmTest                        ← el caso de uso frecuente
//   ./gradlew :shared:compileKotlinIosArm64          ← compila iOS
//   ./gradlew :shared:jvmTest -PenableIosTargets=false
//
// (Antes era OPT-IN por la misma razón que en `stock-domain` —el toolchain—,
// pero con el build ya autocontenido el coste de descubrirlo tarde es mayor que
// el de la descarga: un `expect` sin `actual` no falla hasta que se compila iOS.)
//
// Coordenada Maven explícita. Sigue siendo necesaria aunque el APK viva ahora en
// el MISMO build: el catálogo la publica como artefacto para quien consuma el
// móvil desde fuera, y una coordenada sin `group` sale como `shared:` —con el
// grupo vacío— y no es resoluble.
group = "dev.axzy.stock"
version = "0.1.0"

val enableIosTargets = providers.gradleProperty("enableIosTargets")
    .map(String::toBoolean)
    .getOrElse(true)

kotlin {
    // ── JVM ────────────────────────────────────────────────────────────────
    // NO es "para escritorio": es donde corren los tests del reducer y de los
    // ViewModels con máquina de estados, en milisegundos y **sin emulador**.
    // Es la razón de haber puesto las máquinas de estado en `stock-domain`, y sin
    // este target esa razón no se puede cobrar (PLAN Fase 6, criterio de
    // aceptación: «los tests de los ViewModels con máquina de estados pasan en
    // JVM sin arrancar emulador»).
    jvm()

    if (enableIosTargets) {
        listOf(
            iosArm64(),
            iosSimulatorArm64(),
        ).forEach { iosTarget ->
            iosTarget.binaries.framework {
                // `MainViewController` es el puente que consume iosApp.
                baseName = "MobileApp"
                // Estático: un framework dinámico por app obligaría a firmarlo y a
                // copiarlo al bundle; estático lo enlaza SwiftUI directamente.
                isStatic = true
            }
        }
    }

    // ── Android ────────────────────────────────────────────────────────────
    // El bloque se configura SIEMPRE porque AGP 9 valida `compileSdk` en cuanto se
    // aplica el plugin: un bloque vacío falla la configuración con «compileSdk
    // version is not set». Configurarlo no compila nada si no se pide el target.
    //
    // AGP 9 cambió la sintaxis: `compileSdk` es un bloque con `version = release(n)`.
    // La forma escalar `compileSdk = 36` NO funciona.
    android {
        namespace = "dev.axzy.stock.mobile"

        compileSdk {
            version = release(libs.versions.androidCompileSdk.get().toInt())
        }
        minSdk = libs.versions.androidMinSdk.get().toInt()

        compilerOptions {
            // 11 y no 17: es el jvmTarget del ejemplo real y el que soportan sin
            // sorpresas los dispositivos de bodega, que es donde va a correr.
            jvmTarget = JvmTarget.JVM_11
        }

        // Los recursos de Android se habilitan porque el entry point los usa para
        // el tema y el icono.
        androidResources {
            enable = true
        }

        // Test de Android en el host (JVM): permite correr en esta máquina lo que
        // dependa del framework de Android. La lógica va en `jvmTest`.
        withHostTest {}
    }

    jvmToolchain(17)

    sourceSets {
        // ── commonMain: TODO lo que se comparte ─────────────────────────────
        commonMain.dependencies {
            // El dominio es el corazón: escáner, máquinas de estado y casos de uso.
            //
            // Se declara por COORDENADA, no con `project(":stock-domain")`. El
            // dominio vive en el build del monorepo, que entra como build INCLUIDO
            // (`includeBuild("..")` en `../settings.gradle.kts`); la composición de
            // builds solo sustituye dependencias de MÓDULO, y un `project(...)` se
            // resuelve dentro del build que lo escribe y no cruza la frontera
            // («Project with path ':stock-domain' could not be found»).
            //
            // No hay dos copias del dominio: la misma instancia la usan el backend
            // y el móvil, que es el motivo por el que el dominio no se copia aquí.
            //
            // `api` y no `implementation` porque los `expect/actual` y los
            // ViewModels exponen tipos del dominio (ScanState, ScanWindow,
            // ProductCode) y las features los usan directamente.
            api("dev.axzy.stock:stock-domain")

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.ui.tooling.preview)

            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)

            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }

        // ── androidMain: SOLO lo que Android necesita ───────────────────────
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)

            // Cámara y lectura de códigos. Variante *bundled* de ML Kit: el modelo
            // viaja en el APK. La variante de Play Services obligaría a que el
            // dispositivo tuviera los servicios actualizados, y en bodega eso no se
            // puede asumir.
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.mlkit.barcode)

            implementation(libs.compose.ui.tooling)
        }

        // ── iosMain: cámara por AVFoundation + Vision ───────────────────────
        // Aquí no se declaran dependencias: se usan los frameworks del sistema, que
        // en Kotlin/Native ya vienen en la distribución. Declarar un artefacto
        // externo para esto sería una dependencia engañosa.

        // ── Tests: en JVM, sin emulador ─────────────────────────────────────
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotest.runner.junit5)
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotest.datatest)
            implementation(libs.coroutines.test)
            // Turbine: para afirmar sobre un `StateFlow` sin condiciones de carrera.
            // Sin él, un test de reducer acaba en `delay()` a ojo.
            implementation(libs.turbine)
        }
    }
}

// `compose.uiTooling` solo hace falta en el classpath de EJECUCIÓN de Android
// (para el preview en el dispositivo), no en el de compilación.
dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}

// ============================================================================
// EJECUCIÓN DE TESTS
//
// Sin `useJUnitPlatform()` Gradle no descubre los tests de Kotest y el build
// «pasa» con 0 tests ejecutados: un falso verde, que es peor que un fallo.
//
// `isFailOnNoMatchingTests` cierra el otro agujero: un filtro que no casa con
// ningún test también devuelve éxito, y así se detecta.
// ============================================================================
tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    reports {
        junitXml.required.set(true)
        html.required.set(true)
    }

    filter {
        isFailOnNoMatchingTests = true
    }

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
