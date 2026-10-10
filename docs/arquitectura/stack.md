# Stack verificado
> **Referencia.** Versiones reales del proyecto y el archivo donde están declaradas. No es
> normativo: manda el archivo de build. Última verificación: 2026-10-09.

## Núcleo Kotlin y coroutines

| Componente | Versión | Dónde se declara |
|---|---|---|
| Kotlin | `2.4.10` | `gradle/libs.versions.toml` (`[versions] kotlin`) |
| kotlinx-coroutines | `1.10.1` | `gradle/libs.versions.toml` (`coroutines`) |
| kotlinx-serialization | `1.8.0` | `gradle/libs.versions.toml` (`serialization`) |
| kotlinx-datetime | `0.6.2` | `gradle/libs.versions.toml` (`datetime`) |
| JDK (toolchain) | `17` | `backend/build.gradle.kts`, `stock-domain/build.gradle.kts`, `mobile-app/shared/build.gradle.kts` (`jvmToolchain(17)`) |

## Backend

| Componente | Versión | Dónde se declara |
|---|---|---|
| Ktor | `3.1.1` | `gradle/libs.versions.toml` (`ktor`) |
| Exposed | `0.59.0` | `gradle/libs.versions.toml` (`exposed`) |
| PostgreSQL (driver JDBC) | `42.7.5` | `gradle/libs.versions.toml` (`postgresql`) |
| PostgreSQL (servidor) | `16-alpine` | `infra/docker-compose.yml` (`image: postgres:16-alpine`) |
| HikariCP | `6.2.1` | `gradle/libs.versions.toml` (`hikari`) |
| Flyway | `11.3.4` | `gradle/libs.versions.toml` (`flyway`) |
| Koin | `4.0.4` | `gradle/libs.versions.toml` (`koin`) |
| Argon2 (`argon2-jvm`) | `2.12` | `gradle/libs.versions.toml` (`argon2`) |
| JNA | `5.17.0` | `gradle/libs.versions.toml` (`jna`) |
| JWT (`java-jwt`) | `4.6.1` | `gradle/libs.versions.toml` (`jwt`) |
| Logback | `1.5.16` | `gradle/libs.versions.toml` (`logback`) |

Las dependencias del backend se aplican en `backend/build.gradle.kts` y publican el
`mainClass` `dev.axzy.stock.backend.ApplicationKt`. **Koin está en el classpath y no se usa**;
el cableado es a mano con `by lazy` ([api-ktor.md](api-ktor.md) §1.8). **Argon2 se toma por
sus librerías nativas**: el binding de Java de `argon2-jvm` no se usa porque falla con
«Output is too short (-2)» aunque la librería nativa devuelva éxito; los bindings JNA se
escriben a mano.

## Móvil

| Componente | Versión | Dónde se declara |
|---|---|---|
| Android Gradle Plugin | `9.1.1` | `gradle/libs.versions.toml` (`androidGradlePlugin`) y `mobile-app/settings.gradle.kts` |
| Compose Multiplatform | `1.11.1` | `gradle/libs.versions.toml` (`composeMultiplatform`) |
| Kotlin (plugins del móvil) | `2.4.10` | `mobile-app/settings.gradle.kts` |
| Material 3 | `1.11.0-alpha07` | `gradle/libs.versions.toml` (`composeMaterial3`) |
| SQLDelight | `2.1.0` | `gradle/libs.versions.toml` (`sqldelight`) |
| androidx.datastore | `1.1.7` | `gradle/libs.versions.toml` (`androidxDatastore`) |
| Android `minSdk` / `compileSdk` | `24` / `36` | `gradle/libs.versions.toml` (`androidMinSdk`, `androidCompileSdk`) |

> ⚠️ **Contradicción conocida de versión de AGP.** `gradle/libs.versions.toml` declara
> `androidGradlePlugin = "9.1.1"` y `mobile-app/settings.gradle.kts` fija `9.1.1`, pero el
> `pluginManagement` del `settings.gradle.kts` de la raíz fija
> `com.android.kotlin.multiplatform.library` en **`9.0.1`**. Al subir AGP hay que tocar los
> dos sitios (lo advierte el propio `mobile-app/settings.gradle.kts`); hoy no coinciden.

## Web

| Componente | Versión | Dónde se declara |
|---|---|---|
| React / React DOM | `19.0.0` | `web/package.json` (`dependencies`) |
| TypeScript | `~5.7.2` | `web/package.json` (`devDependencies`) |
| Vite | `^6.1.0` | `web/package.json` (`devDependencies`) |
| Tailwind CSS / `@tailwindcss/vite` | `4.3.3` / `^4.3.3` | `web/package.json` (`devDependencies`) |
| Playwright (`@playwright/test`) | `^1.64.0` | `web/package.json` (`devDependencies`) |
| React Router | `^7.2.0` | `web/package.json` (`dependencies`) |
| axios | `^1.7.9` | `web/package.json` (`dependencies`) |
| ESLint / typescript-eslint | `^9.19.0` / `^8.22.0` | `web/package.json` (`devDependencies`) |
| `eslint-plugin-boundaries` | `^7.2.0` | `web/package.json` (`devDependencies`) — impone las capas FSD |
| `@axzydev/axzy_ui_system` | `1.3.6` | `web/package.json` (`dependencies`) |

## Calidad y pruebas

| Componente | Versión | Dónde se declara |
|---|---|---|
| Kotest | `5.9.1` | `gradle/libs.versions.toml` (`kotest`) |
| detekt | `1.23.8` | `gradle/libs.versions.toml` (`detekt`) |
| Turbine | `1.2.0` | `gradle/libs.versions.toml` (`turbine`) |

`MockK` y `Testcontainers` **no están** en el proyecto
([api-ktor.md](api-ktor.md) §1.17).

## Notas de la composición de builds

- `settings.gradle.kts` incluye `:stock-domain`, `:stock-network`, `:stock-database`,
  `:stock-datastore` y `:backend`, y **no** incluye `:mobile-app`: el móvil es un build
  aparte ([app-kmp.md](app-kmp.md)).
- La tarea `verifyArchitecture` de `build.gradle.kts` agrega
  `:stock-domain:verifyDomainPurity`.
- El `pluginManagement` del `settings.gradle.kts` de la raíz declara las versiones de los
  plugins del móvil sin meterlos en el classpath del build; `libs.versions.toml` no puede
  usarse ahí porque el catálogo no existe cuando se evalúa ese bloque.
