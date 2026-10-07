// ============================================================================
// stock-datastore — Preferencias multiplataforma (KMP)
//
// Responsabilidad: configuración del servidor (AppConfig con esquema, host y
// puerto, en lugar de la IP pelada del legacy), preferencias de usuario,
// idioma y almacenamiento seguro del token.
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
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
