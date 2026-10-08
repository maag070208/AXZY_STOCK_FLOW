// ============================================================================
// settings.gradle.kts de `mobile-app` — BUILD KMP AUTOCONTENIDO
//
// POR QUÉ ESTE ARCHIVO EXISTE
//
// Antes `mobile-app` era un submódulo del build del monorepo, y el APK Android
// tenía que vivir en un build de Gradle APARTE (`androidApp/settings.gradle.kts`)
// porque se creía que `com.android.application` y el plugin de Kotlin
// Multiplatform no podían convivir. Con AGP 9 sí pueden: el plugin de Android
// para KMP (`com.android.kotlin.multiplatform.library`) declara el target de
// Android DENTRO del bloque `kotlin {}` y no arrastra `KotlinAndroidTarget`, que
// es la clase que AGP 9 eliminó (`com.android.build.gradle.api.BaseVariant`).
//
// Con este `settings.gradle.kts`, `mobile-app` es un build de Kotlin
// Multiplatform normal —como el proyecto de referencia `PuertoNuevoApp`—, con
// sus dos módulos y SIN builds anidados:
//
//   mobile-app/
//   ├── build.gradle.kts     ← declara los plugins con `apply false`
//   ├── settings.gradle.kts  ← este archivo
//   ├── shared/              ← la biblioteca KMP (UI compartida Android + iOS)
//   └── androidApp/          ← el APK (com.android.application)
//
// EL PRECIO (y por qué se paga con gusto)
//
// El dominio (`stock-domain`) NO puede ser un módulo de ESTE build: vive en el
// build del monorepo, que a su vez consumen el backend y los tests de negocio.
// Duplicarlo aquí sería tener dos definiciones de las reglas de negocio, que es
// exactamente lo que el módulo compartido existe para evitar
// (PLAN_ALMACEN_INTEGRAL.md §3.2-bis). Se consume como BUILD INCLUIDO
// (`includeBuild("..")`), que es la forma canónica de Gradle de componer dos
// builds independientes sin publicar nada en un repositorio Maven.
//
// LO QUE ESTO ARREGLA, QUE ERA EL PROBLEMA DE VERDAD
//
// Con el esquema anterior, abrir `mobile-app` en solitario en IntelliJ fallaba
// con «Task 'prepareKotlinBuildScriptModel' not found in project ':mobile-app'»:
// la raíz del proyecto abierto no era la raíz de ningún build de Gradle.
// Ahora `mobile-app` ES la raíz de su build, así que el IDE sincroniza como con
// cualquier proyecto KMP.
// ============================================================================

rootProject.name = "mobile-app"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }

    // ── Versiones de los plugins de este build, resueltas AQUÍ ──────────────
    //
    // Los números están repetidos respecto a `../gradle/libs.versions.toml` a
    // propósito: la MISMA razón que obligó a hacerlo en el build del monorepo.
    // Si los plugins se declaran en el `plugins {}` de la raíz con `apply false`,
    // sus clases entran en el classpath del script y el bloque `android {}` de
    // OTROS módulos —`stock-database`, `stock-domain`— deja de resolver: el
    // compilador de Kotlin DSL encuentra antes el `android()` obsoleto de
    // `KotlinTargetContainerWithPresetFunctions` que la extensión de AGP 9, y
    // falla con «Unresolved reference 'namespace'». Está reproducido y el
    // detalle está en el README de `mobile-app/` §9.
    //
    // AQUÍ sí se pueden declarar `com.android.application` y el plugin de KMP
    // juntos: son el MISMO build, y ese es justo el caso que el esquema anterior
    // daba por imposible. `apply false` en la raíz de este build hace que las
    // versiones se resuelvan una sola vez, sin meterlas en el classpath del
    // script.
    plugins {
        id("com.android.application") version "9.0.1"
        id("com.android.kotlin.multiplatform.library") version "9.0.1"
        id("org.jetbrains.compose") version "1.11.1"
        id("org.jetbrains.kotlin.multiplatform") version "2.4.10"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// ── El dominio, como build INCLUIDO ────────────────────────────────────────
//
// `includeBuild("..")` mete el build del monorepo en este: Gradle CONFIGURA
// solo los proyectos de los que se declara dependencia, no los nueve módulos, y
// sustituye la coordenada por el proyecto real sin publicar nada.
//
// La sustitución va por COORDENADA (`dev.axzy.stock:stock-domain`) y no por
// `project(":stock-domain")`. No es estilo: la sustitución de dependencias de la
// composición de builds solo cubre dependencias de MÓDULO. Un `project(":...")`
// se resuelve dentro del build que lo escribe y no cruza la frontera, así que
// `shared` fallaba con «Project with path ':stock-domain' could not be found».
//
// La contrapartida: el módulo tiene que tener grupo. El build del monorepo lo
// fija de una vez para todos sus módulos en `settings.gradle.kts`, y ese cambio
// es la ÚNICA línea que se tocó fuera de `mobile-app/` (motivo en el comentario
// de ese archivo).
includeBuild("..") {
    dependencySubstitution {
        substitute(module("dev.axzy.stock:stock-domain")).using(project(":stock-domain"))
    }
}

include(":shared")
include(":androidApp")
