# Almacén Integral

Sistema de almacén: catálogo de productos y ubicaciones, existencias y kardex,
recepción de facturas con captura física, surtido de órdenes, traslados entre
ubicaciones y bodegas, etiquetas y reportes.

Monorepo con backend Kotlin, dominio compartido entre las tres partes, web en
React y app móvil en Kotlin Multiplatform.

## Arrancar

Tres comandos, en tres terminales, sin configurar nada a mano:
**[ARRANQUE.md](ARRANQUE.md)**.

## Las partes

| Parte | Carpeta | Qué es | Arquitectura |
|---|---|---|---|
| API | `backend/` | Ktor 3 sobre PostgreSQL | Clean Architecture por feature |
| Dominio | `stock-domain/` | Reglas de negocio puras, KMP | KMP sin dependencias |
| Web | `web/` | React 19 + Vite + TypeScript | FSD (Feature-Sliced Design) |
| App | `mobile-app/` | Android e iOS, KMP + Compose | Vertical slice |
| Legacy | `BASE/` | El sistema anterior (Node + React + Expo) | — |

`BASE/` es el sistema anterior y no forma parte de los builds del monorepo: es la
fuente de la migración. Lo que hay que traer de ahí está en `docs/migracion/`.

## Documentación

**El índice está en [docs/README.md](docs/README.md)**, con una tabla por área y
el estado de cada documento.

Tres reglas que resumen cómo está escrita:

1. Cada documento declara su tipo en la línea 2: **Normativo**, **Plan**,
   **Referencia** o **Histórico**. Un normativo gana sobre cualquier plan.
2. Cada regla vive en **un solo lugar**; los demás documentos enlazan.
3. Cada afirmación técnica cita el archivo de código que la sostiene.

El plan con el que se reorganizó esta documentación está archivado en
[docs/historico/PLAN_DOCUMENTACION.md](docs/historico/PLAN_DOCUMENTACION.md).

## Estado, en corto

- La carga inicial del **catálogo** (el Excel de 1.398 productos) no está
  verificada en el entorno local. Ver [ARRANQUE.md](ARRANQUE.md) y
  [docs/migracion/catalogo-excel.md](docs/migracion/catalogo-excel.md). El stock
  del archivo entra a `SIN-UBICAR`, no a una ubicación física, porque el archivo
  no trae el desglose ([ADR-0012](docs/adr/0012-conciliar-stock-heredado-con-conteo.md)).
- La **API y el dominio** están cubiertos por tests que se ejecutan con
  `GRADLE_USER_HOME=.gradle-home ./gradlew :backend:test :stock-domain:jvmTest`.
- Lo que **no** está verificado en dispositivo (cámara, proyecto de iOS) y lo que
  está construido pero sin conectar está listado en
  [docs/guia/lo-no-verificado.md](docs/guia/lo-no-verificado.md).

Las cantidades concretas viven en las tablas de estado de `docs/`, no aquí: un
número escrito en la portada caduca y nadie lo actualiza.
