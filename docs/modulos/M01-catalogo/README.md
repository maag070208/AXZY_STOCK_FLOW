# M01 — Catálogo: productos, ubicaciones y bodegas

> **Normativo.** Última verificación: 2026-10-09.

| | |
|---|---|
| **Código** | M01 |
| **Carpeta** | `docs/modulos/M01-catalogo/` |
| **Documentado** | Sí |
| **Terminado (backend)** | Sí |
| **Web** | `ProductsPage`, `LocationsPage` |
| **App** | `features/locations` |

## 1. Objetivo

Mantener el catálogo que todo lo demás referencia: productos con código único, bodegas y
ubicaciones. Es la fuente maestra de productos desde la importación inicial
([ADR-0011](../../adr/0011-catalogo-fuente-maestra.md)).

## 2. Alcance

| Dentro | Fuera |
|---|---|
| Alta, edición y baja lógica de productos, ubicaciones y bodegas | Existencias y movimientos (M02) |
| Importación inicial del Excel, con rechazo de filas inválidas | Sincronización periódica con otros sistemas |
| Ubicación de recepción protegida | Conteo físico (proyecto aparte, [ADR-0012](../../adr/0012-conciliar-stock-heredado-con-conteo.md)) |

## 3. Reglas de negocio

| # | Regla | Dónde vive |
|---|---|---|
| R1 | Un código de producto no se repite | `V3__inventory_domain.sql`; `DUPLICATE_PRODUCT_CODE` en `catalog/CatalogErrors.kt` |
| R2 | El código de producto es inmutable | `PRODUCT_CODE_IMMUTABLE` en `catalog/product/ProductRoutes.kt` |
| R3 | No se da de baja un producto con existencias | `PRODUCT_HAS_STOCK` en `catalog/product/ProductRoutes.kt` |
| R4 | No se da de baja una ubicación con existencias | `LOCATION_HAS_STOCK` en `catalog/location/LocationRoutes.kt` |
| R5 | La ubicación de recepción no se puede desactivar | `RECEIVING_LOCATION_PROTECTED` en `catalog/location/LocationRoutes.kt` ([ADR-0009](../../adr/0009-ubicacion-de-recepcion-real.md)) |
| R6 | La importación rechaza el archivo entero si una fila es inválida | `catalog/CatalogImportService.kt`; [migracion/catalogo-excel.md](../../migracion/catalogo-excel.md) |
| R7 | Las unidades de medida son un catálogo cerrado | `units_of_measure` en `V3` |

## 4. Modelo de datos

| Tabla | Migración | Qué guarda |
|---|---|---|
| `products` | `V3` | Productos, con código único y unidad |
| `warehouses` | `V3` | Bodegas; una por defecto |
| `locations` | `V3`, `V5`, `V6` | Ubicaciones y su `kind` |
| `units_of_measure` | `V3` | Unidades de medida |

## 5. API

Contrato completo en [api/catalogo.md](../../api/catalogo.md). Rutas en
`catalog/product/ProductRoutes.kt`, `catalog/location/LocationRoutes.kt` y
`catalog/warehouse/WarehouseRoutes.kt`.

## 6. Permisos

`product.view`, `product.create`, `product.edit`, `product.delete`, `product.import`,
`location.view`, `location.create`, `location.edit`, `location.delete`, `warehouse.view`,
`warehouse.create`, `warehouse.edit`, definidos en `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/PermissionCatalog.kt`.
Matriz en [seguridad/roles-permisos.md](../../seguridad/roles-permisos.md).

## 7. Bitácora

Toda alta, cambio y baja escribe en `audit_log` con estado anterior y nuevo
([seguridad/auditoria.md](../../seguridad/auditoria.md)).

## 8. Pruebas

Reglas en `stock-domain` (`catalog/`, con `CatalogImporterTest`). Rutas en
`backend/src/test/`. Estrategia en [pruebas/estrategia-pruebas.md](../../pruebas/estrategia-pruebas.md).

## 9. Criterios de aceptación

- [ ] La importación del Excel inicial carga 1.398 productos sin rechazos. El plan de 2026-10-07 la da por hecha; no se ha reverificado en el entorno local ([migracion/catalogo-excel.md](../../migracion/catalogo-excel.md)).
- [ ] Prueba de navegador de alta, edición y baja. Hoy no existe (`web/e2e/flow.spec.ts` no cubre catálogo).
