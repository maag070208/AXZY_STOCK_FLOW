# Initial Excel catalog
> **Reference.** What the real inventory that is imported into the new system contains and how it is imported. Last verified: 2026-10-09.
> **Reconstruction note (2026-10-10).** The data and the import rules are in force; the `scripts/` are from the previous system. The rules remain in force; the code paths it cites belong to the previous system and are remapped to the new code (see [plan/PLAN-IMPLEMENTACION.md](../plan/PLAN-IMPLEMENTACION.md)).

## Source

Inventory file dated June 10, 2024, analyzed in
[historico/PLAN_ALMACEN_INTEGRAL.md](../historico/PLAN_ALMACEN_INTEGRAL.md) §3.5. Working copy in
`scripts/catalogo-existencias.tsv`; the load is done by `scripts/importar-catalogo.sh`.

## Data profile

| Measure | Value |
|---|---|
| Products | 1,398 |
| Product code | unique |
| Unit of measure | `PZA` in 100% |
| Total units | 10,914,130 |
| Nulls | none |

The total is reliable. The breakdown by location is not in the file: see
[ADR-0012](../adr/0012-conciliar-stock-heredado-con-conteo.md).

## Import

- **Strict.** An invalid row rejects the entire file (`catalog/CatalogImportService.kt`).
  Nothing is corrected by guessing.
- **Initial stock to `UNASSIGNED`** ([ADR-0009](../adr/0009-ubicacion-de-recepcion-real.md)),
  already implemented: each product **without stock** receives its inherited total with an
  `INITIAL` movement in the ledger, with the user who imported it and its date. A product that
  already has a balance is skipped —re-importing cannot duplicate the inventory— and the report says
  how many were skipped.
- **Accepts CSV, TSV and XLSX.** The Excel is read with Apache POI and converted to CSV before
  parsing, so both paths validate the same way.
- **Codes `X` and `XN` are two products** (R-71; [ADR-0011](../adr/0011-catalogo-fuente-maestra.md)).
  There are 201 pairs, 180 with the same name. Before the cutover, compare in each pair whether
  both have high stock: if so, the decision has to be reviewed.

## What the file does not resolve

- The breakdown by location: it is resolved with a physical count before the cutover.
- Products with no known physical location: they stay in `UNASSIGNED` until counted.
