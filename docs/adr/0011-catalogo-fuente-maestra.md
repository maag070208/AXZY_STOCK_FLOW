# ADR-0011 — The catalog is managed by the API; the Excel only seeds the initial load
> **Normative.** Accepted decision. Last verified: 2026-10-09.

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-09 (decisions B5, D11 and R-71 of the original plan) |
| **Replaces** | — |
| **Replaced by** | — |

## Context

The catalog reached the legacy through an Excel file and through the synchronization system with SQL
Server. Two sources for the same data produce products that do not match each other.

## Decision

- **The API is the master source** (B5). `products` is sovereign in the API's database. The initial
  Excel is imported once; from then on, the catalog is changed through the API.
  There is no periodic synchronization.
- **Strict import.** An invalid row rejects the entire file; it is not corrected by guessing
  (`catalog/CatalogImportService.kt`; [migracion/catalogo-excel.md](../migracion/catalogo-excel.md)).
- **Two codes are two products.** There are 201 pairs of codes `X` and `XN`, 180 with the same
  name. The 1,398 are imported as they are, without merging (R-71). It is a business decision with
  an accepted risk: if they turned out to be one product split in two, the stock would end up duplicated.
- The product code is unique (`V3__inventory_domain.sql`) and is not changed
  (`PRODUCT_CODE_IMMUTABLE`, `catalog/product/ProductRoutes.kt`).

## Consequences

- Any product data that comes from outside the API is treated as untrusted input.
- Whoever wants to change a product does it in the API; the Excel is no longer a channel for changes.
- For the risk of R-71 there is a cheap check in the migration: if both codes of a
  pair have high stock at the same time, it has to be suspected. See [migracion/catalogo-excel.md](../migracion/catalogo-excel.md).

## Discarded alternatives

- **Periodic synchronization with the previous system.** Two sources of truth: it is the cause
  of the discrepancy that is meant to be corrected.
- **Merging the `X`/`XN` pairs.** It would be a business decision taken by a name heuristic.
