# Stack de la reconstrucción
> **Referencia.** Qué se usa en cada parte y con qué criterio se fija cada versión. Las versiones exactas
> **no están fijadas todavía**: se fijan en la fase 0 con la versión vigente y se anotan aquí con su
> fecha y su fuente. Hasta entonces, esta tabla no es normativa. Última revisión: 2026-10-09.
> El stack anterior (Ktor, Exposed, Compose de la API) está en [historico/STACK-KTOR.md](../historico/STACK-KTOR.md).

## Criterio para fijar una versión

1. Usar la **versión estable (LTS cuando exista)** publicada por el proveedor en el momento de fijarla.
2. Anotar la fuente: página oficial de releases o repositorio Maven Central / npm.
3. Anotar la fecha de verificación. Una versión sin fecha se considera no verificada.
4. No usar una versión de pre-lanzamiento (alfa, beta, RC) en producción.

## API

| Componente | Función | Versión | Fuente y fecha |
|---|---|---|---|
| JDK | Runtime y toolchain | `25` (Temurin LTS) | [Adoptium](https://adoptium.net/) y [endoflife.ai](https://endoflife.ai/eclipse-temurin/25), consultados el 2026-10-10. Patch `25.0.3` según endoflife.ai |
| Kotlin | Lenguaje | `2.4.20` | [Anuncio de JetBrains, sep. 2026](https://blog.jetbrains.com/kotlin/2026/09/kotlin-2-4-20-released/), consultado el 2026-10-10 |
| Spring Boot | Framework de la API | `4.1.x` (último 4.1.1 según fuente consultada) | [spring.io](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/) y [herodevs](https://www.herodevs.com/blog-posts/spring-boot-versions-eol-dates-and-latest-releases-april-2026), consultados el 2026-10-10. Requiere Java 17+. La línea 3.5 está fuera de soporte |
| Spring JDBC (`JdbcClient`) | Acceso a PostgreSQL | Incluido en Spring Boot | — |
| Flyway | Migraciones | *Pendiente* | — |
| PostgreSQL | Base de datos | `16` (la versión menor se fija aquí) | — |
| Argon2 (Spring Security) | Hash de contraseñas | Incluido en Spring Security | — |
| JWT | Tokens de acceso y refresh | *Pendiente* | — |
| Gradle (Kotlin DSL) | Build | *Pendiente* | — |
| JUnit 5 + Kotest | Pruebas | *Pendiente* | — |
| Testcontainers | PostgreSQL real en pruebas | *Pendiente* | — |
| ArchUnit | Reglas de capas (§1 de [api-spring.md](api-spring.md)) | *Pendiente* | — |
| springdoc-openapi | OpenAPI generado desde el código | *Pendiente* | — |

## Dominio compartido (si D6 se aprueba)

| Componente | Versión | Fuente y fecha |
|---|---|---|
| Kotlin Multiplatform | Igual que la API | — |
| kotlinx-datetime | *Pendiente* | — |
| kotlinx-serialization | *Pendiente* | — |

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
| Imagen de la API | JRE LTS sobre imagen mínima | `app/Dockerfile` |
| Imagen de la web | nginx estable | `web/Dockerfile` |

## Qué se decide en la fase 0

Llenar cada fila *Pendiente*, con versión, fuente y fecha. Si una versión no existe en su fuente
oficial en el momento de fijarla, no se usa.
