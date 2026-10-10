# Reports
> **Reference.** Stock with totals, ledger export, movement summary and
> reconciliation between the ledger and the projection.
> Last verified: 2026-10-10 (phase 5, M02 closed).

Implemented in layers, like the rest of the API (docs/arquitectura/api-spring.md §1):

| Layer | File |
|---|---|
| Routes | `api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/reports/ReportController.kt` |
| Use cases and ports | `api/application/src/main/kotlin/dev/axzy/stock/application/reports/` |
| Queries and export | `api/adapters-persistence/.../reports/JdbcReportQueries.kt` and `api/adapters-web/.../reports/KardexExportWriter.kt` |
| Wiring | `api/app/src/main/kotlin/dev/axzy/stock/app/config/ReportConfig.kt` |

Pagination is in [convenciones.md](convenciones.md); the codes, in
[errores.md](errores.md).

**Dates are operational days, not UTC days.** `from` and `to` are interpreted in the
business time zone (`stock.business-zone`, default `America/Mexico_City`), and the
summary groups by day, week and month in that same zone. A movement at 22:00 on day 9 in the warehouse
belongs to day 9 even though in UTC it is already the 10th; interpreting it in UTC leaves movements outside their own
day, and the symptom is an empty report that looks like "there were no movements".

The three listings require `report.view`; the export, `report.export`. **They are the
same permissions as the guards of the screens**: a different permission makes the
user enter the screen and receive a `403` when it loads.

## `GET /v1/reports/inventory`

Stock per product and location, with totals. Filters: `warehouseCode`,
`productCode` and `onlyWithStock` (`true` by default: a report that lists thousands of
rows at `0.000` is not read by anyone). Pagination by `page` and `size`.

```json
{
  "items": [ { "productCode": "ABRAA1B", "name": "...", "locationDisplayName": "04-B-C",
               "quantity": "100.000", "warehouseCode": "MAIN" } ],
  "totals": { "distinctProducts": 1398, "totalUnits": "10914130.000" },
  "page": 0, "size": 50, "totalItems": 1398, "totalPages": 28
}
```

`totals` is calculated over the **whole filter**, not over the page: a total that
ignores the filter is worse than not having it, because the user decides with it.
`distinctProducts` counts **distinct** products: one product on three shelves is one, not
three.

`locationDisplayName` is composed by the server (`04-B-C`, `RECEIVING`, `UNASSIGNED`).

## `GET /v1/reports/kardex/export?format=xlsx|csv`

Permission: `report.export`. It returns the file with `Content-Disposition:
attachment` and `Content-Type: text/csv` or the spreadsheet type according to `format`.
`format` is mandatory and only accepts `csv` or `xlsx` (`400 INVALID_PARAMETER` with
`details.allowed`).

Filters: `productCode`, `moveType`, `from`, `to`. **The filters are the same as
those of the listing**: if the user sees 200 filtered rows and exports, they must receive those
200. A file with another filter forces reconciling two views of the same data.

In XLSX the amounts go as a **numeric** cell with 3 decimals (in JSON they go as
text): a text column is not summed and Excel would count it as zero.

## `GET /v1/reports/movements-summary?from=&to=&groupBy=day|week|month|product|location`

Permission: `report.view`. `from` and `to` are mandatory (`YYYY-MM-DD`); `groupBy`
defaults to `day`. It does not use the pagination envelope.

```json
{
  "from": "2026-10-01", "to": "2026-10-31", "groupBy": "day",
  "totals": { "inUnits": "12500.000", "outUnits": "8300.000", "netUnits": "4200.000", "movements": 412 },
  "items": [
    { "key": "2026-10-07", "keyLabel": "2026-10-07",
      "inUnits": "1200.000", "outUnits": "300.000", "netUnits": "900.000", "movements": 18 }
  ]
}
```

`items[].key` is the value by which it is grouped —an ISO date, a `productCode` or a
`locationDisplayName`— and `keyLabel` its form for display. They are separated because a
date sorts as ISO text but is displayed formatted according to the language, and **the
client should not have to know which is which**.

`LOCATE` and `MOVE_WITHIN_WAREHOUSE` **do not add to any column**, and that is
correct: they move goods between locations, so the total does not change. Counting them as an outflow would inflate the figure and would not balance with the ledger. `PENDING_OUT` does not add either: it is an outflow intention.

`groupBy` invalid → `400 INVALID_PARAMETER` with the supported values in
`details.allowed`. An inverted range → `400 INVALID_FILTER_RANGE`; a malformed date →
`400 INVALID_FILTER_DATE`; `from` or `to` missing → `400 FIELD_REQUIRED`.

## `GET /v1/reports/kardex-vs-projection`

Permission: `report.view`. **The reconciliation report.** It compares what the
ledger says with what the projections say.

```json
{
  "checkedAt": "2026-10-08T03:24:02.709620Z", "balanced": true,
  "divergences": 0,
  "items": [ { "productId": 1, "productCode": "ABRAA1B",
               "projectedTotal": "5193.000", "ledgerTotal": "5193.000",
               "difference": "0.000" } ]
}
```

| Field | Type | Meaning |
|---|---|---|
| `balanced` | boolean | `true` when **there is no divergence at all**. It is the server's verdict |
| `divergences` | integer | How many products are out of balance |
| `items` | array | **Only the out-of-balance products**, not all the checked ones |
| `items[].projectedTotal` | decimal string | What the `product_stock` projection says (derived value) |
| `items[].ledgerTotal` | decimal string | What the ledger says, which is the **source of truth** |
| `items[].difference` | decimal string | `projectedTotal - ledgerTotal`. The sign matters |

**`items` empty with `balanced: true` is the healthy state**, and it is the normal response.
The verdict is given by the server and is not recalculated in the client: counting the array on
the screen would be a second calculation that can disagree with the first, and then the
imbalance would be the screen's and not the inventory's.

The field names are **in English** because they are fields of the public API
([guia/convenciones.md](../guia/convenciones.md) §1 and
[ADR-0004](../adr/0004-nombres-en-ingles-comentarios-en-espanol.md)). Until migration `V8` the route returned `cuadra`,
`totalEnBd`, `totalEsperado` and `diferencia` —the columns of the `RETURNS TABLE` of
`comprobar_proyecciones()`—, which did not match what the contract promised. The
same migration renamed the columns and aligned both sides. The English column names have to match the `RETURNS TABLE`; a mapper that
reads them by string fails silently if they do not match, and that is why the mapping is covered
by `DivergenceMapperTest.kt`.

The permission is **`report.view`**, not `inventory.reconcile`: it is the same as the guard
of the `/reports` screen, and that match is mandatory.

In the legacy this check did not exist, and the imbalance was only discovered with a
physical count.

## Verification

| File | What it covers |
|---|---|
| `api/app/src/test/kotlin/dev/axzy/stock/app/ReportFlowTest.kt` | the four routes over real HTTP: totals of the whole filter, transfers that do not add, reconciliation and export |
| `api/adapters-persistence/src/test/kotlin/dev/axzy/stock/adapters/persistence/ReportPersistenceTest.kt` | the query budget, the chunking of the export and the **operational day** (with movements dated by hand) |
| `api/adapters-web/src/test/kotlin/dev/axzy/stock/adapters/web/reports/KardexExportWriterTest.kt` | the CSV (quotes, commas, byte-order mark) and that in XLSX the quantities are **numeric cells** |
