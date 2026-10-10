# M01 — Catalog: products, locations and warehouses
> **Normative.** Last verified: 2026-10-10 (API finished, phase 4).

| | |
|---|---|
| **Code** | M01 |
| **Folder** | `docs/modulos/M01-catalogo/` |
| **Documented** | Yes |
| **Finished (backend)** | Yes: phase 4, except the XLSX reading (M10) |
| **Web** | `ProductsPage`, `LocationsPage` |
| **App** | `features/locations` |

## 1. Objective

Maintain the catalog that everything else references: products with a unique code, warehouses and
locations. It is the master source of products since the initial import
([ADR-0011](../../adr/0011-catalogo-fuente-maestra.md)).

## 2. Scope

| Inside | Outside |
|---|---|
| Creation, editing and logical deactivation of products, locations and warehouses | Stock and movements (M02) |
| Initial import of the Excel, with rejection of invalid rows | Periodic synchronization with other systems |
| Protected receiving location | Physical count (separate project, [ADR-0012](../../adr/0012-conciliar-stock-heredado-con-conteo.md)) |

## 3. Business rules

| # | Rule | Where it lives |
|---|---|---|
| R1 | A product code is not repeated | `V3__inventory_domain.sql`; `DomainError.DuplicateProductCode` |
| R2 | The product code is immutable | `Product.withUpdated` in `domain/catalog/CatalogModel.kt` |
| R3 | A product with stock is not deactivated | `DeactivateProduct` reads the `product_stock` projection |
| R4 | A location with stock is not deactivated | `DeactivateLocation` in `application/catalog/LocationUseCases.kt` |
| R5 | The receiving location cannot be deactivated | `Location.deactivate` ([ADR-0009](../../adr/0009-ubicacion-de-recepcion-real.md)) |
| R6 | The import rejects the entire file if a row is invalid | `CatalogImporter` + `ImportCatalog`; [migracion/catalogo-excel.md](../../migracion/catalogo-excel.md) |
| R7 | Units of measure are a closed catalog | `units_of_measure` in `V3` |

## 4. Data model

| Table | Migration | What it holds |
|---|---|---|
| `products` | `V3` | Products, with unique code and unit |
| `warehouses` | `V3` | Warehouses; one by default |
| `locations` | `V3`, `V5`, `V6` | Locations and their `kind` |
| `units_of_measure` | `V3` | Units of measure |

## 5. API

Full contract in [api/catalogo.md](../../api/catalogo.md). Routes in
`api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/catalog/`.

## 6. Permissions

`product.view`, `product.create`, `product.edit`, `product.delete`, `product.import`,
`location.view`, `location.create`, `location.edit`, `location.delete`, `warehouse.view`,
`warehouse.create`, `warehouse.edit`, defined in `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/PermissionCatalog.kt`.
Each route declares them with `@RequiresPermission` and `RoutePermissionAudit` prevents startup if
any is missing (I5). Matrix in [seguridad/roles-permisos.md](../../seguridad/roles-permisos.md).

## 7. Audit log

Every creation, change and deactivation writes to `audit_log` with the previous and the new state
([seguridad/auditoria.md](../../seguridad/auditoria.md)).

## 8. Tests

Rules in `stock-domain/src/commonTest/.../catalog/` (with `CatalogImporterTest`); SQL and pagination
in `api/adapters-persistence/src/test/.../CatalogPersistenceTest.kt`; flow over HTTP in
`api/app/src/test/.../CatalogFlowTest.kt` (K1–K6, I5 and I8). Strategy in
[pruebas/estrategia-pruebas.md](../../historico/ESTRATEGIA-PRUEBAS-ANTERIOR.md).

## 9. Acceptance criteria

- [x] The import is strict and idempotent, with K1 and K2 green. **The real load of the
  1,398 products is missing**: it needs the cutover file and the XLSX reading or its export to CSV
  ([migracion/catalogo-excel.md](../../migracion/catalogo-excel.md)).
- [ ] Browser test of creation, editing and deactivation. It arrives with the web (phase 11).
