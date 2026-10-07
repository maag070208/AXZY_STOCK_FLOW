// ============================================================================
// settings.gradle.kts de `androidApp` — BUILD PROPIO, NO SUBPROYECTO
//
// POR QUÉ ESTE MÓDULO TIENE SU PROPIO SETTINGS
//
// AGP 9 quitó `com.android.build.gradle.api.BaseVariant`. El resultado es que
// `com.android.application` NO puede convivir con `org.jetbrains.kotlin.multiplatform`
// en el MISMO build de Gradle: en cuanto los dos están en el classpath, Kotlin
// intenta crear un `KotlinAndroidTarget` que referencia esa clase eliminada y el
// build entero falla con
//
//   «Could not generate a decorated class for type KotlinAndroidTarget.
//    > com/android/build/gradle/api/BaseVariant»
//
// Se intentaron, y se descartaron con la salida real de Gradle delante:
//
//   1. Declarar los plugins en el `plugins {}` de la raíz con `apply false`
//      (lo que pide la guía oficial de migración). Rompe `stock-database` y
//      `stock-domain` con «Unresolved reference 'namespace' / 'compileSdk'»: el
//      `android {}` del plugin KMP deja de resolver.
//   2. Fijar las versiones en `pluginManagement` y aplicar por `id(...)`. Mismo
//      error de `KotlinAndroidTarget`.
//   3. `android.enableLegacyVariantApi=true`. AGP 9 responde que la propiedad
//      «was removed in version 9.0 ... has no effect, use android.newDsl instead».
//   4. Aplicar `org.jetbrains.kotlin.android` en este módulo. Gradle no lo
//      encuentra (no está en el catálogo de plugins del portal) y además duplicaría
//      el soporte de Kotlin que AGP 9 ya integra.
//
// La conclusión es que los dos plugins deben vivir en builds de Gradle SEPARADOS, y
// eso es exactamente lo que hace este archivo. No es una preferencia: es la única
// combinación que compila con AGP 9.0.1 y Kotlin 2.4.10 en este repositorio.
//
// EL PRECIO, DICHO CLARO: `./gradlew :androidApp:assembleDebug` NO se puede lanzar
// desde la raíz del monorepo; se lanza desde este directorio, y este build incluye
// `mobile-app` para poder consumir su código. El README lo documenta.
// ============================================================================

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }

    // Aquí SÍ se puede (y se debe) declarar `com.android.application`: este build es
    // independiente, así que el plugin no se cruza con el de Kotlin Multiplatform del
    // monorepo. Es justo lo que no se puede hacer en `settings.gradle.kts` de la raíz.
    plugins {
        id("com.android.application") version "9.0.1"
        id("org.jetbrains.compose") version "1.11.1"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "androidApp"

// `mobile-app` entra como build INCLUIDO: su código se compila aquí dentro, con su
// propio classpath de plugins, y así el plugin de Kotlin Multiplatform nunca se
// cruza con `com.android.application`.
includeBuild("../..") {
    dependencySubstitution {
        // Se sustituye la COORDENADA, no el proyecto, porque `:mobile-app` vive en
        // OTRO build: desde aquí no existe como proyecto. La coordenada la fija
        // `group`/`version` en mobile-app/build.gradle.kts.
        substitute(module("dev.axzy.stock:mobile-app")).using(project(":mobile-app"))
    }
}
