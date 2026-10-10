# Glossary
> **Reference.** Domain terms as the code uses them. Last verified: 2026-10-09.

| Term | Meaning | Where it is defined |
|---|---|---|
| **Warehouse** (`warehouse`) | Physical warehouse. One is the default in the current operation. | `V3__inventory_domain.sql` |
| **Location** (`location`) | Place inside a warehouse: shelf, receiving or `SIN-UBICAR`. | `V3__inventory_domain.sql`, `V5__unassigned_location_kind.sql` |
| **Receiving location** (`kind = RECEIVING`) | The temporary location where what was received and not yet located stays. It replaces the old `ADMIN` pseudo-location. | [ADR-0009](../adr/0009-ubicacion-de-recepcion-real.md) |
| **Product** (`product`) | Item with a unique code (`product_code`). `XN` and `X` are different SKUs. | [ADR-0011](../adr/0011-catalogo-fuente-maestra.md) |
| **Stock** (`stock`) | Quantity of a product in a location. It is a projection, not a number that is edited. | `V3__inventory_domain.sql` (`stock_by_location`) |
| **Ledger** (`kardex`) | Movement ledger, append-only. Correcting means adding a new movement. | `V3__inventory_domain.sql` (`kardex`) |
| **Transit** (`stock_in_transit`) | Goods dispatched in a transfer that have not yet been received. | `V3__inventory_domain.sql`, `V9__transfer_lines.sql` |
| **Adjustment** (`adjustment`) | Stock difference with a mandatory reason. A single rule (`AdjustStock`) with four origins. | [ADR-0008](../adr/0008-discrepancias-y-merma.md) |
| **Discrepancy** | Invoice line whose physical quantity does not match the invoiced one. It blocks locating until approved. | [ADR-0008](../adr/0008-discrepancias-y-merma.md) |
| **Invoice** (`invoice`) | Receiving document with lines. It is closed when no line remains pending. | `V3__inventory_domain.sql` |
| **Line** (`invoice_line`) | A product inside an invoice, with its state (`PENDING`, `CAPTURED`, `DISCREPANCY`, `APPROVED`, `LOCATED`). | `V3__inventory_domain.sql` |
| **Order** (`order`) | Request to pick. Its state goes from `PENDING` to `COMPLETED`. | `V3__inventory_domain.sql` |
| **Picking** | Packing of an order line by line. | [api/surtido.md](../api/surtido.md) |
| **Transfer** (`transfer`) | Movement of stock between locations or warehouses, with states `DRAFT` → `DISPATCHED` → `RECEIVED`. | `V3__inventory_domain.sql`, `V9__transfer_lines.sql` |
| **Label template** (`label_template`) | Label format by type. | `V3__inventory_domain.sql` |
| **Print job** (`print_job`) | Label sent to print. It stays `PENDING` until the printer confirms. | [ADR-0010](../adr/0010-rutas-de-servicio-con-api-key.md) |
| **Scope** (`scope`) | How far a permission reaches: `NONE`, `OWN`, `AREA` (their warehouse) or `ALL`. | [seguridad/roles-permisos.md](../seguridad/roles-permisos.md) |
| **Segregation of duties** | Whoever captures a line cannot approve its discrepancy. | [ADR-0008](../adr/0008-discrepancias-y-merma.md) |
| **Legacy** | The previous system in `BASE/`. | [migracion/legacy-inventario.md](../migracion/legacy-inventario.md) |
| **Physical count** | Real recount of stock, before the cutover. | [ADR-0012](../adr/0012-conciliar-stock-heredado-con-conteo.md) |

## The product name

The product is called **StockFlow**. The technical identifiers are still `stock`, and they are **not renamed**:
the Kotlin package is `dev.axzy.stock`, the rules module `stock-domain`, the database
`stock`, the images `stockflow-*` and the Compose project `stockflow` (it was named that way so as not to overwrite
the containers of the previous system). Renaming them would touch migrations, deployments and the git history in exchange for nothing.

The rule, then: **in the interface and in the documents, StockFlow; in the code and the infrastructure, `stock`/`stockflow`**.
The brand artwork says StockFlow from the start, and that is what this decision fixed (2026-10-10).
