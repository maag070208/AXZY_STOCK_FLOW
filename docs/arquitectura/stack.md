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
| JDK | Runtime y toolchain | *Pendiente* (LTS vigente) | — |
| Kotlin | Lenguaje | *Pendiente* | — |
| Spring Boot | Framework de la API | *Pendiente* (3.x LTS vigente) | — |
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
| AXZY UI System (`@axzydev/axzy_ui_system`) | Componentes y tema | *Pendiente* (última publicada) | — |
| Tailwind CSS | Utilidades; se usa en capas (ver [web-fsd.md](web-fsd.md)) | *Pendiente* | — |
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
