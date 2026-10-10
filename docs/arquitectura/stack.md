# Stack de la reconstrucción
> **Referencia.** Qué se usa en cada parte y con qué criterio se fija cada versión. Las de la API y el
> dominio están fijadas y verificadas; las de la app y la web se fijan al empezar sus fases.
> Manda el archivo de build, no esta tabla. Última revisión: 2026-10-10.
> El stack anterior (Ktor, Exposed, Compose de la API) está en [historico/STACK-KTOR.md](../historico/STACK-KTOR.md).

## Criterio para fijar una versión

1. Usar la **versión estable (LTS cuando exista)** publicada por el proveedor en el momento de fijarla.
2. Anotar la fuente: página oficial de releases o repositorio Maven Central / npm.
3. Anotar la fecha de verificación. Una versión sin fecha se considera no verificada.
4. No usar una versión de pre-lanzamiento (alfa, beta, RC) en producción.

## API

Verificadas el 2026-10-10 en los `maven-metadata.xml` de Maven Central y en el BOM
`spring-boot-dependencies-4.1.1.pom`. Se declaran en `gradle/libs.versions.toml`; las que gestiona el
BOM de Spring Boot no se repiten ahí.

| Componente | Función | Versión | Dónde se declara |
|---|---|---|---|
| JDK | Runtime y toolchain de la API | `25` (Temurin `25.0.4.1`, lo descarga Gradle) | `build.gradle.kts` (`jvmToolchain(25)`) |
| Kotlin | Lenguaje | `2.4.20` | `libs.versions.toml` |
| Spring Boot | Framework de la API | `4.1.1` | `libs.versions.toml` |
| Spring Framework / Security | — | `7.0.9` / `7.1.1` | BOM de Spring Boot |
| Spring JDBC (`JdbcClient`) | Acceso a PostgreSQL | — | BOM |
| PostgreSQL (controlador) | — | `42.7.13` | BOM |
| HikariCP | Pool de conexiones | `7.0.2` | BOM |
| Jackson | JSON | `3.1.5` | BOM |
| Flyway | Migraciones (servicio `migrate` y pruebas) | `12.4.0` | BOM, y `docker-compose.yml` |
| BouncyCastle | Argon2id | `1.86` | `libs.versions.toml` |
| Gradle | Build | `9.3.1` | `gradle/wrapper/gradle-wrapper.properties` |
| JUnit Jupiter / AssertJ | Pruebas de la API | `6.0.3` / `3.27.7` | BOM |
| Testcontainers | PostgreSQL real en pruebas | `2.0.5` | BOM |
| ArchUnit | Reglas de capas | `1.5.1` | `libs.versions.toml` |
| Kotest | Pruebas del dominio | `5.9.1` | `libs.versions.toml` |

**Notas de versión.**

- Kotlin `2.4.21` ya existe; es un parche posterior al `2.4.20` elegido. Subir es cambiar una línea.
- El BOM de Spring Boot 4.1.1 gestiona Kotlin `2.3.21`; el proyecto compila con `2.4.20` y alinea la
  biblioteca estándar con `kotlin-bom` (`build.gradle.kts`).
- Flyway se alinea con el BOM (`12.4.0`) y no con la última publicada (`13.10.0`): así el servicio
  `migrate` y las pruebas usan exactamente la misma versión.
- Kotest se queda en `5.9.1`: la 6 cambia el API de las pruebas por datos que usa el dominio.
- `springdoc-openapi` (`3.1.1` disponible) no está añadido todavía; entra con el primer módulo de negocio.

## Dominio compartido

| Componente | Versión | Dónde se declara |
|---|---|---|
| Kotlin Multiplatform | `2.4.20` | `libs.versions.toml` |
| kotlinx-coroutines | `1.10.2` | `libs.versions.toml` |
| kotlinx-serialization | `1.11.0` | `libs.versions.toml` |
| kotlinx-datetime | `0.6.2` | `libs.versions.toml` |

Se compila a bytecode de Java 17 para que lo carguen la API y Android
(`stock-domain/build.gradle.kts`). `kotlinx-datetime` se queda en `0.6.2`: desde la `0.7`, `Instant` y
`Clock` pasan a `kotlin.time`, y el dominio usa los de kotlinx.

## App

| Componente | Función | Versión | Fuente y fecha |
|---|---|---|---|
| Kotlin Multiplatform | Núcleo compartido Android e iOS | Igual que la API | — |
| Compose Multiplatform | Interfaz | *Pendiente* | — |
| Android Gradle Plugin | Build Android | *Pendiente* | — |
| Ktor Client | Llamadas HTTP desde la app | *Pendiente* | — |
| SQLDelight | Caché local | *Pendiente* | — |

> Nota: el cliente HTTP de la app usa Ktor **solo como cliente**. No es la API. Esta es la única
> presencia de Ktor que sigue en el stack.

## Web

| Componente | Función | Versión | Fuente y fecha |
|---|---|---|---|
| React / React DOM | Interfaz | *Pendiente* (19.x) | — |
| TypeScript | Tipos | *Pendiente* | — |
| Vite | Build y servidor de desarrollo | *Pendiente* | — |
| AXZY UI System (`@axzydev/axzy_ui_system`) | Componentes y tema | `^1.4.7` (en CYC, instalado `1.4.7`) | Uso real en `~/DEV/CYC/web/package.json` y `node_modules`, revisado el 2026-10-09 |
| Tailwind CSS / `@tailwindcss/vite` | Utilidades; se usa en capas ([web-react.md](web-react.md) §5) | `4.3.3` / `^4.3.3` (como en CYC) | `~/DEV/CYC/web/package.json` |
| React Router | Rutas | *Pendiente* | — |
| Playwright | Pruebas de navegador | *Pendiente* | — |
| ESLint con `eslint-plugin-boundaries` | Imponer las capas FSD | *Pendiente* | — |

## Infraestructura

| Componente | Versión | Dónde se declara |
|---|---|---|
| Docker Compose | Versión de la especificación actual | `docker-compose.yml` (raíz) |
| Imagen de PostgreSQL | `postgres:16` | `docker-compose.yml` |
| Imagen de migraciones | `flyway/flyway:12.4.0` | `docker-compose.yml` |
| Imagen de la API | `eclipse-temurin:25-jre-alpine` (388 MB con el jar) | `api/Dockerfile` |
| Imagen de la web | nginx estable | `web/Dockerfile` |

## Qué se decide en la fase 0

Llenar cada fila *Pendiente*, con versión, fuente y fecha. Si una versión no existe en su fuente
oficial en el momento de fijarla, no se usa.
