# ADR-0011 — El catálogo lo maneja la API; el Excel solo siembra la carga inicial
> **Normativo.** Decisión aceptada. Última verificación: 2026-10-09.

| | |
|---|---|
| **Estado** | Aceptada |
| **Fecha** | 2026-10-09 (decisiones B5, D11 y R-71 del plan original) |
| **Reemplaza a** | — |
| **Reemplazada por** | — |

## Contexto

El catálogo llegaba al legacy por un Excel y por el sistema de sincronización con SQL
Server. Dos fuentes para el mismo dato producen productos que no coinciden entre sí.

## Decisión

- **La API es la fuente maestra** (B5). `products` es soberano en la base de la API. Se
  importa el Excel inicial una vez; a partir de ahí, el catálogo se cambia por la API.
  No hay sincronización periódica.
- **Importación estricta.** Una fila inválida rechaza el archivo entero, no se corrige
  adivinando (`catalog/CatalogImportService.kt`; [migracion/catalogo-excel.md](../migracion/catalogo-excel.md)).
- **Dos códigos son dos productos.** Hay 201 pares de códigos `X` y `XN`, 180 con el mismo
  nombre. Se importan los 1.398 tal cual, sin fusionar (R-71). Es una decisión de negocio con
  riesgo aceptado: si resultaran ser un producto partido, el stock quedaría duplicado.
- El código de producto es único (`V3__inventory_domain.sql`) y no se cambia
  (`PRODUCT_CODE_IMMUTABLE`, `catalog/product/ProductRoutes.kt`).

## Consecuencias

- Cualquier dato de producto que venga de fuera de la API se trata como entrada no confiable.
- Quien quiera cambiar un producto lo hace en la API; el Excel ya no es un canal de cambios.
- Para el riesgo de R-71 hay una verificación barata en la migración: si ambos códigos de un
  par tienen stock alto a la vez, hay que sospechar. Ver [migracion/catalogo-excel.md](../migracion/catalogo-excel.md).

## Alternativas descartadas

- **Sincronización periódica con el sistema anterior.** Dos fuentes de verdad: es la causa
  del descuadre que se quiere corregir.
- **Fusionar los pares `X`/`XN`.** Sería una decisión de negocio tomada por una heurística de
  nombres.
