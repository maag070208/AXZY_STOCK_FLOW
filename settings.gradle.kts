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
// de ninguno de los dos. Ver docs/historico/PLAN_ALMACEN_INTEGRAL.md §3.1 y §3.2-bis.
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
//
// Se aplica al proyecto RAÍZ desde aquí y no con un `group = ` suelto porque el
// script de `settings` no tiene esa propiedad: el grupo pertenece a `Project`, y
// `gradle.rootProject {}` es la forma de alcanzarlo desde `settings`.
gradle.rootProject {
    group = "dev.axzy.stock"
}

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

// ── Aplicación móvil (KMP + Compose Multiplatform): BUILD APARTE ────────────
//
// YA NO SE INCLUYE AQUÍ, Y ES DELIBERADO.
//
// `mobile-app` es ahora un build de Gradle autocontenido —con su propio
// `mobile-app/settings.gradle.kts`, su raíz de proyecto y sus dos módulos
// `:shared` y `:androidApp`—, igual que el proyecto de referencia
// `PuertoNuevoApp`. Eso es lo que permite abrirlo en solitario en IntelliJ sin el
// error «Task 'prepareKotlinBuildScriptModel' not found in project ':mobile-app'».
//
// Los DOS motivos por los que no puede seguir siendo un subproyecto de este build:
//
//   1. Una raíz de proyecto de Gradle solo puede tener un `settings.gradle.kts`.
//      Si el monorepo incluye `mobile-app`, ese directorio deja de ser raíz de su
//      propio build y vuelve el problema de IntelliJ que se acaba de arreglar.
//
//   2. Aunque se incluyera, no serviría de nada: este build vería `:mobile-app`
//      como UN proyecto y no como el build que es, así que
//      `./gradlew :mobile-app:shared:jvmTest` falla con
//      «project 'shared' not found in project ':mobile-app'». Comprobado.
//
// EL GRAFO NO CAMBIA: el móvil sigue consumiendo `stock-domain`, y lo hace como
// BUILD INCLUIDO (`mobile-app/settings.gradle.kts` → `includeBuild("..")`). Es una
// instancia del mismo módulo, no una copia: las decisiones de negocio siguen siendo
// IDÉNTICAS en el backend y en el móvil por construcción, no por disciplina.
//
// EL PRECIO, DICHO CLARO: los comandos del móvil se lanzan desde `mobile-app/`,
// no desde la raíz.
//
//   cd mobile-app && ./gradlew :shared:jvmTest
//   cd mobile-app && ./gradlew :androidApp:assembleDebug
//
// `mobile-app/gradlew` es una COPIA del wrapper del monorepo (no un enlace: un
// enlace rompería la resolución de `APP_HOME` y el script acabaría usando el
// `settings.gradle.kts` de la raíz). `mobile-app/gradle` SÍ es un enlace a
// `../gradle`, así que el catálogo de versiones y el wrapper son los mismos: no
// hay dos listas de versiones que se puedan separar.

// ── Web (React JS, proyecto aparte) ────────────────────────────────────────
// NO es un módulo de Gradle: es un proyecto de pnpm en `web/`. Se deja
// constancia aquí para que quien lea el grafo sepa que existe y de quién depende
// (consume el contrato de docs/historico/CONTRATO_API.md).
