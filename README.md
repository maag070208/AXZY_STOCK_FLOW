# Almacén Integral

Sistema de almacén: catálogo de productos y ubicaciones, existencias y kardex,
recepción de facturas con captura física, surtido de órdenes, traslados entre
ubicaciones y bodegas, etiquetas y reportes.

El sistema se está reconstruyendo. Lo que hay hoy es la documentación, el dominio compartido y la
API con su infraestructura; la app y la web vienen después.

## Arrancar

Un comando: **[ARRANQUE.md](ARRANQUE.md)**.

## Las partes

| Parte | Carpeta | Qué es | Estado |
|---|---|---|---|
| Dominio | `stock-domain/` | Reglas de negocio puras, Kotlin Multiplatform, sin frameworks | Construido |
| API | `api/` | Kotlin + Spring Boot sobre PostgreSQL, en capas | Identidad y catálogo construidos; negocio en curso |
| Base de datos | `db/` | Migraciones Flyway `V1`–`V12`, y el rol de la API | Construida |
| Entorno | `docker-compose.yml` | PostgreSQL, migraciones y API | Construido |
| Web | `web/` | React 19 + Vite + Tailwind + AXZY UI System, en capas FSD | Entrada y catálogo; crece con cada módulo |
| Marca | `brand/` | Iconos de la app (Android e iOS) y el manifiesto del paquete de arte | Versionado; la web ya lo usa ([identidad visual](docs/guia/identidad-visual.md)) |
| App | — | Kotlin Multiplatform (Android e iOS) | Pendiente |
| Legacy | `BASE/` | El sistema anterior (Node + React + Expo). **Solo referencia: no se modifica** | — |

## Documentación

**El índice está en [docs/README.md](docs/README.md).** Por dónde empezar:

1. [docs/plan/PLAN-REHACER.md](docs/plan/PLAN-REHACER.md): qué se construye y por qué.
2. [docs/plan/PLAN-IMPLEMENTACION.md](docs/plan/PLAN-IMPLEMENTACION.md): módulos, fases y criterio de terminado.
3. [docs/arquitectura/api-spring.md](docs/arquitectura/api-spring.md): cómo está organizada la API.
4. [docs/flujos/](docs/flujos/README.md): los procesos que el sistema debe cumplir.

Tres reglas resumen cómo está escrita:

1. Cada documento declara su tipo en la línea 2: **Normativo**, **Plan**, **Referencia** o **Histórico**.
2. Cada regla vive en **un solo lugar**; los demás documentos enlazan.
3. Cada afirmación técnica cita el archivo de código que la sostiene.

Las cantidades concretas (pruebas, versiones) viven en los documentos de `docs/` y en
[ARRANQUE.md](ARRANQUE.md), no aquí: un número en la portada caduca y nadie lo actualiza.
