# Reconstruction stack
> **Reference.** What is used in each part and by which criterion each version is pinned. The API and the domain
> versions are pinned and verified; the app and the web ones are pinned when their phases start.
> The build file governs, not this table. Last revised: 2026-10-10.
> The previous stack (Ktor, Exposed, the API's Compose) is in [historico/STACK-KTOR.md](../historico/STACK-KTOR.md).

## Criterion for pinning a version

1. Use the **stable version (LTS when one exists)** published by the provider at the time of pinning.
2. Note the source: official releases page or Maven Central / npm repository.
3. Note the verification date. A version without a date is considered unverified.
4. Do not use a pre-release version (alpha, beta, RC) in production.

## API

Verified on 2026-10-10 in the `maven-metadata.xml` of Maven Central and in the BOM
`spring-boot-dependencies-4.1.1.pom`. They are declared in `gradle/libs.versions.toml`; those managed by the
Spring Boot BOM are not repeated there.

| Component | Role | Version | Where it is declared |
|---|---|---|---|
| JDK | Runtime and toolchain of the API | `25` (Temurin `25.0.4.1`, downloaded by Gradle) | `build.gradle.kts` (`jvmToolchain(25)`) |
| Kotlin | Language | `2.4.20` | `libs.versions.toml` |
| Spring Boot | API framework | `4.1.1` | `libs.versions.toml` |
| Spring Framework / Security | — | `7.0.9` / `7.1.1` | Spring Boot BOM |
| Spring JDBC (`JdbcClient`) | PostgreSQL access | — | BOM |
| PostgreSQL (driver) | — | `42.7.13` | BOM |
| HikariCP | Connection pool | `7.0.2` | BOM |
| Jackson | JSON | `3.1.5` | BOM |
| Flyway | Migrations (`migrate` service and tests) | `12.4.0` | BOM, and `docker-compose.yml` |
| BouncyCastle | Argon2id | `1.86` | `libs.versions.toml` |
| Gradle | Build | `9.3.1` | `gradle/wrapper/gradle-wrapper.properties` |
| JUnit Jupiter / AssertJ | API tests | `6.0.3` / `3.27.7` | BOM |
| Testcontainers | Real PostgreSQL in tests | `2.0.5` | BOM |
| ArchUnit | Layer rules | `1.5.1` | `libs.versions.toml` |
| Kotest | Domain tests | `5.9.1` | `libs.versions.toml` |

**Version notes.**

- Kotlin `2.4.21` already exists; it is a patch after the chosen `2.4.20`. Upgrading is changing one line.
- The Spring Boot 4.1.1 BOM manages Kotlin `2.3.21`; the project compiles with `2.4.20` and aligns the
  standard library with `kotlin-bom` (`build.gradle.kts`).
- Flyway is aligned with the BOM (`12.4.0`) and not with the latest published (`13.10.0`): so the `migrate` service and the
  tests use exactly the same version.
- Kotest stays at `5.9.1`: version 6 changes the data-driven test API that the domain uses.
- `springdoc-openapi` (`3.1.1` available) is not added yet; it comes in with the first business module.

## Shared domain

| Component | Version | Where it is declared |
|---|---|---|
| Kotlin Multiplatform | `2.4.20` | `libs.versions.toml` |
| kotlinx-coroutines | `1.10.2` | `libs.versions.toml` |
| kotlinx-serialization | `1.11.0` | `libs.versions.toml` |
| kotlinx-datetime | `0.6.2` | `libs.versions.toml` |

It is compiled to Java 17 bytecode so that the API and Android can load it
(`stock-domain/build.gradle.kts`). `kotlinx-datetime` stays at `0.6.2`: from `0.7` on, `Instant` and
`Clock` move to `kotlin.time`, and the domain uses the kotlinx ones.

## App

| Component | Role | Version | Source and date |
|---|---|---|---|
| Kotlin Multiplatform | Shared core for Android and iOS | `2.4.20`, same as the API | `mobile-app/gradle/libs.versions.toml`. PuertoNuevoApp uses `2.4.10`; the app uses the domain's version so it reads the domain's metadata |
| Compose Multiplatform | Interface | `1.11.1` (Material 3 `1.11.0-alpha07`) | `mobile-app/gradle/libs.versions.toml`; Maven Central, 2026-10-10. The same as PuertoNuevoApp |
| Android Gradle Plugin | Android build | `9.0.1` | `mobile-app/gradle/libs.versions.toml`; Google's Maven, 2026-10-10. The same as PuertoNuevoApp |
| Ktor Client | HTTP calls from the app | *Pending* | — |
| SQLDelight | Local cache | *Pending* | — |

> Note: the app's HTTP client uses Ktor **only as a client**. It is not the API. This is the only
> remaining presence of Ktor in the stack.

## Web

| Component | Role | Version | Source and date |
|---|---|---|---|
| React / React DOM | Interface | `19.0.0` | `web/package.json`, installed and building on 2026-10-10 |
| TypeScript | Types | `~5.7.2` (installed 5.7.3) | `web/package.json` |
| Vite | Build and development server | `^6.1.0` (installed 6.4.4) | `web/package.json` |
| `@vitejs/plugin-react-swc` | React compilation | `3.8.0` | `web/package.json` |
| AXZY UI System (`@axzydev/axzy_ui_system`) | Components and theme | `^1.4.7` (installed 1.4.7) | `web/package.json`; `d.ts` read on 2026-10-10 |
| Tailwind CSS / `@tailwindcss/vite` | Utilities; used in layers ([web-react.md](web-react.md) §5) | `4.3.3` / `^4.3.3` | `web/package.json` |
| React Router | Routes | `^7.2.0` | `web/package.json` |
| Redux Toolkit / React Redux | Session state | `^2.5.1` / `^9.2.0` | `web/package.json` |
| Playwright | Browser tests | `^1.64.0` | `web/package.json`; 30 tests green on 2026-10-10 |
| ESLint with `eslint-plugin-boundaries` | Enforcing the FSD layers | `^9.19.0` / `^7.2.0` | `web/package.json`; the guard was verified by making it fail |

The web versions are pinned **when its phase starts** (2026-10-10), as §«What is decided in phase 0» requires. They are the ones
CYC already used —same product family and same UI system— and they are checked by installing them: `pnpm install`,
`tsc --noEmit`, `eslint --max-warnings=0` and `vite build`.

## Infrastructure

| Component | Version | Where it is declared |
|---|---|---|
| Docker Compose | Current specification version | `docker-compose.yml` (root) |
| PostgreSQL image | `postgres:16` | `docker-compose.yml` |
| Migrations image | `flyway/flyway:12.4.0` | `docker-compose.yml` |
| API image | `eclipse-temurin:25-jre-alpine` (388 MB with the jar) | `api/Dockerfile` |
| Web image | nginx stable | `web/Dockerfile` |

## What is decided in phase 0

Fill in each *Pending* row, with version, source and date. If a version does not exist in its
official source at the time of pinning it, it is not used.
