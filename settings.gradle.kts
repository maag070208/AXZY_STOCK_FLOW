// ============================================================================
// settings.gradle.kts — Monorepo del Almacén Integral
//
// ARQUITECTURA DE MÓDULOS (grafo unidireccional, sin ciclos):
//
//   backend ──→ stock-domain ──→ (solo stdlib Kotlin + kotlinx)
//   mobile  ──→ stock-domain
//           ──→ stock-network ──→ stock-domain
//           ──→ stock-database ─→ stock-domain
//           ──→ stock-datastore
//
// stock-domain vive en la RAÍZ a propósito: es un ciudadano de primera clase,
// consumido por el backend Y por el móvil. No es un detalle de implementación
// de ninguno de los dos. Ver PLAN_ALMACEN_INTEGRAL.md §3.1 y §3.2-bis.
// ============================================================================

rootProject.name = "stock"

// ── Grupo Maven de TODOS los módulos del monorepo ──────────────────────────
//
// POR QUÉ ESTA LÍNEA EXISTE (añadida en la reestructuración de `mobile-app`)
//
// `mobile-app` es ahora un build de Gradle autocontenido que consume el dominio
// como BUILD INCLUIDO (`mobile-app/settings.gradle.kts` → `includeBuild("..")`).
// La composición de builds solo sustituye dependencias declaradas por
// COORDENADA (`dev.axzy.stock:stock-domain`), y sin un `group` la coordenada de
// `stock-domain` sale como `stock-domain` a secas, que Gradle rechaza:
//
//   «Cannot convert the provided notation to an object of type
//    ComponentSelector: stock-domain»
//
// Se fija aquí y no en cada `build.gradle.kts` porque es una propiedad del
// monorepo, no de un módulo: los seis módulos comparten grupo y así hay un solo
// sitio que cambiar. NO cambia ninguna ruta ni ninguna dependencia interna —
// todas las dependencias entre módulos del monorepo se declaran con
// `project(":...")`, que no usa el grupo.
group = "dev.axzy.stock"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }

    // ── Versiones de los plugins de la app móvil, resueltas AQUÍ ────────────
    //
    // POR QUÉ AQUÍ Y NO EN EL `build.gradle.kts` RAÍZ
    //
    // La guía oficial de migración a AGP 9 con Kotlin Multiplatform pide declarar
    // estos plugins en el `plugins {}` de la raíz con `apply false` para que su
    // versión sea única en todo el build. Se probó y rompe este monorepo: en cuanto
    // `android-kmp-library` entra en el classpath raíz, el bloque `android {}` de
    // `stock-database` (y de `stock-domain`) deja de resolver y el build falla con
    // «Unresolved reference 'namespace' / 'compileSdk'» en módulos que nadie tocó.
    //
    // `pluginManagement` fija la versión SIN meter el plugin en el classpath del
    // build: cada módulo lo aplica en su propio classpath. Por eso
    // `mobile-app/build.gradle.kts` los pide con `id(...)` sin versión.
    //
    // Los números están repetidos respecto a `gradle/libs.versions.toml` a
    // propósito (los alias del catálogo ponen la versión en el `plugins {}` del
    // módulo, que es lo que Gradle rechaza aquí). Al subir AGP, Compose o Kotlin hay
    // que actualizar LOS DOS sitios; el README de `mobile-app/` lo indica.
    plugins {
        // Solo los de `:mobile-app`. `com.android.application` NO se declara aquí:
        // lo aplica `mobile-app/androidApp` en SU propio build, y meterlo en este
        // classpath es exactamente lo que rompía la configuración del monorepo.
        id("com.android.kotlin.multiplatform.library") version "9.0.1"
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

// ── Módulos Kotlin Multiplatform compartidos ────────────────────────────────
// stock-domain: el corazón del sistema. Kotlin PURO, sin Ktor, sin Exposed,
//   sin SQLDelight, sin Compose, sin androidx. Una guardia de arquitectura en
//   el build FALLA si esto se rompe (ver stock-domain/build.gradle.kts).
include(":stock-domain")
include(":stock-network")
include(":stock-database")
include(":stock-datastore")

// ── Backend Ktor ───────────────────────────────────────────────────────────
include(":backend")

// ── Aplicación móvil (KMP + Compose Multiplatform) ─────────────────────────
// Comparte stock-domain con el backend, así que las decisiones de negocio son
// IDÉNTICAS en las dos partes por construcción, no por disciplina.
//
// El directorio se crea al incluirlo: si no existe, Gradle falla al configurar.
include(":mobile-app")

// ── Punto de entrada Android: NO es un subproyecto de este build ───────────
//
// `androidApp` tiene su PROPIO build de Gradle (`mobile-app/androidApp/`), y no por
// gusto: con AGP 9, `com.android.application` y `org.jetbrains.kotlin.multiplatform`
// no pueden coexistir en el mismo build. En cuanto comparten classpath, Kotlin
// intenta crear su `KotlinAndroidTarget` y falla con
//
//   «Could not generate a decorated class for type KotlinAndroidTarget.
//    > com/android/build/gradle/api/BaseVariant»
//
// —una clase que AGP 9 eliminó— y el build ENTERO del monorepo deja de configurar,
// incluidos los módulos de otros agentes. Por eso se saca de aquí.
//
// Es la misma decisión que en el ejemplo real: `iosApp` es un proyecto de Xcode
// aparte, no un módulo de Gradle. Aquí `androidApp` es un build de Gradle aparte.
// Se compila con `cd mobile-app/androidApp && ./gradlew assembleDebug`.
// El detalle y las cuatro alternativas probadas están en
// `mobile-app/androidApp/settings.gradle.kts`.

// ── Web (React JS, proyecto aparte) ────────────────────────────────────────
// NO es un módulo de Gradle: es un proyecto de pnpm en `web/`. Se deja
// constancia aquí para que quien lea el grafo sepa que existe y de quién depende
// (consume el contrato de CONTRATO_API.md).
