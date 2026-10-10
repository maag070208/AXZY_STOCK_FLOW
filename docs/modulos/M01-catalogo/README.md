# M01 — Catálogo: productos, ubicaciones y bodegas
> **Normativo.** Última verificación: 2026-10-10 (API terminada, fase 4).

| | |
|---|---|
| **Código** | M01 |
| **Carpeta** | `docs/modulos/M01-catalogo/` |
| **Documentado** | Sí |
| **Terminado (backend)** | Sí: fase 4, salvo la lectura de XLSX (M10) |
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
| R1 | Un código de producto no se repite | `V3__inventory_domain.sql`; `DomainError.DuplicateProductCode` |
| R2 | El código de producto es inmutable | `Product.withUpdated` en `domain/catalog/CatalogModel.kt` |
| R3 | No se da de baja un producto con existencias | `DeactivateProduct` lee la proyección `product_stock` |
| R4 | No se da de baja una ubicación con existencias | `DeactivateLocation` en `application/catalog/LocationUseCases.kt` |
| R5 | La ubicación de recepción no se puede desactivar | `Location.deactivate` ([ADR-0009](../../adr/0009-ubicacion-de-recepcion-real.md)) |
| R6 | La importación rechaza el archivo entero si una fila es inválida | `CatalogImporter` + `ImportCatalog`; [migracion/catalogo-excel.md](../../migracion/catalogo-excel.md) |
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
`api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/catalog/`.

## 6. Permisos

`product.view`, `product.create`, `product.edit`, `product.delete`, `product.import`,
`location.view`, `location.create`, `location.edit`, `location.delete`, `warehouse.view`,
`warehouse.create`, `warehouse.edit`, definidos en `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/PermissionCatalog.kt`.
Cada ruta los declara con `@RequiresPermission` y `RoutePermissionAudit` impide el arranque si
falta alguno (I5). Matriz en [seguridad/roles-permisos.md](../../seguridad/roles-permisos.md).

## 7. Bitácora

Toda alta, cambio y baja escribe en `audit_log` con estado anterior y nuevo
([seguridad/auditoria.md](../../seguridad/auditoria.md)).

## 8. Pruebas

Reglas en `stock-domain/src/commonTest/.../catalog/` (con `CatalogImporterTest`); SQL y paginación
en `api/adapters-persistence/src/test/.../CatalogPersistenceTest.kt`; flujo por HTTP en
`api/app/src/test/.../CatalogFlowTest.kt` (K1–K6, I5 e I8). Estrategia en
[pruebas/estrategia-pruebas.md](../../historico/ESTRATEGIA-PRUEBAS-ANTERIOR.md).

## 9. Criterios de aceptación

- [x] La importación es estricta e idempotente, con K1 y K2 en verde. **Falta la carga real de los
  1.398 productos**: necesita el archivo del corte y la lectura de XLSX o su exportación a CSV
  ([migracion/catalogo-excel.md](../../migracion/catalogo-excel.md)).
- [ ] Prueba de navegador de alta, edición y baja. Llega con la web (fase 11).
