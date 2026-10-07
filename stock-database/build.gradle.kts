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
    alias(libs.plugins.kotlin.multiplatform)
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
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
