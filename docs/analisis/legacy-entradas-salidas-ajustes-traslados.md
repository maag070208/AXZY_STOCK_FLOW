# Legacy analysis: receipts, outflows, adjustments and transfers
> **Reference.** Performance and integrity findings in the code of `BASE/backend` (Node + Prisma). Review of October 9, 2026. Each finding cites its file and line; **it was verified by reading the code, not by running the legacy**, except where indicated. Its purpose is to justify requirements of the [plan](../plan/PLAN-REHACER.md) (Q6–Q10).

## 1. Scope and method

The services that write inventory were read: `invoice.service.ts` (receipts), `order.service.ts`
(outflows), `kardex.service.ts` and `location.service.ts` (adjustments and locations), and the
import controller `kardo.controller.ts`. The patterns that do the most damage in an inventory system were searched for:
writes without a transaction, reads and writes separated (read-modify-write), `await` calls inside loops,
unawaited promises and history modifications.

**The legacy has no transfers between warehouses.** The closest are the location movements
(`LOCATION`, `PENDING-TO-LOCATE`, `ADJUSTMENT`). `getDataTransfers` (`kardo.service.ts`) imports invoices
from SQL Server; it is not a transfer. Both are treated here.

## 2. Summary by severity

| Severity | What it means | Count |
|---|---|---|
| **Critical** | Can leave the inventory unbalanced without anyone noticing | 6 |
| **High** | Performance that gets worse over time or with each operation | 4 |
| **Medium** | Fragile or confusing; does not break today, causes problems when changed | 3 |

## 3. Critical findings

### C1 — The ledger is modified instead of growing (adjustments and outflows)

- `updateKardex` updates `units` of the existing row (`kardex.service.ts:183-215`).
- When taking out in `addKardex` of type OUT, units are subtracted from the `INITIAL` rows (`kardex.service.ts:296-310`)
  and then `updateMany` changes the `moveType` to `OUT` and the `moveDate` to the current time
  (`kardex.service.ts:313-325`). **The real date of the movement is lost.**
- Locations are moved the same way: `removeUnitsFromLocation` restricts the `INITIAL` row
  (`location.service.ts:656-680`).
- The deletion is logical and on the ledger (`order.service.ts:236` and `:245` with `deleted: true`).

**Effect.** The movement book does not reconstruct the history: today's balance cannot be explained with
the rows that exist. It is the opposite of what justified the previous design.

**In the new design.** The ledger is only inserted; correcting means adding a compensating movement
(Q6). See [existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) §3 and test A9.

### C2 — The stock is calculated with read-modify-write and without a transaction

- `updateStoreStock` reads `store.stock`, calculates the new value in JavaScript and writes the absolute value
  (`kardex.service.ts:552-585`). It does not validate that the result is not negative.
- It is called from `addKardex` after creating the ledger row (`kardex.service.ts:241-258`): two
  writes without a transaction between them.

**Effect.** Two concurrent operations on the same product overwrite each other: it is the same
lost update reproduced in the new engine before it was corrected (see
[existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) §5).

**In the new design.** The balance is changed by the database with a lock, never by the application with an
absolute value (Q1, Q7).

### C3 — Outflow without awaiting the writes, and lines marked as picked without being picked

`updateOrderDetails` (`order.service.ts:324-365`) does two serious things:

1. It marks **all** the lines of the order as `verified` with `updateMany` (`order.service.ts:330-335`),
   picked or not.
2. It records the outflow with `orderDetail.map((item) => addKardexOut(...))` (`order.service.ts:350-358`)
   **without `await`**. The function returns before the ledger is written, and the errors of those promises
   are not caught.
3. The outflow uses `Number(item.quantity)` (`order.service.ts:354`): the **requested** quantity, not the
   picked one. A line of 10 with 7 packed deducts 10.

**Effect.** The inventory goes down by what was requested and not by what was delivered; the imbalance only appears when counting.
A failure when writing the ledger is seen by nobody.

**In the new design.** The outflow is `quantity − supplied_quantity` inside a transaction that
locks the order (Q8). See [surtido-y-erp.md](../flujos/surtido-y-erp.md) §4 and test S2.

### C4 — Packing checks availability with a snapshot and writes afterwards

`updateStoreLocationWithOrder` (`order.service.ts:367-500`):

- It validates `productExistence.total < units` with data read before writing (`order.service.ts:410`).
- Then, in separate steps and without a transaction: `addKardex` in `order.service.ts:458` (ledger and stock), `physicalAssignItems` as
  read and write (`order.service.ts:475`) and the state change (`order.service.ts:489`).
- It calls `getKardexByProductCode` on each packing (`order.service.ts:385`; see A1 in §4).

**Effect.** Two operators packing at the same time pass the same validation and can take out more than there is.
If one of the intermediate writes fails, a half-done state is left.

**In the new design.** Availability is checked inside the transaction, with the balance locked
by the database (test A3 of [existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) §7).

### C5 — Location adjustment with read-modify-write

`removeUnitsFromLocation` reads the `INITIAL` row, subtracts in JavaScript and writes the absolute value
(`location.service.ts:656-680`), without a lock or a transaction. The same defect as C2, on the path of the
location adjustments.

### C6 — Confirming an order checks and moves nothing

`confirmOrder` (`order.service.ts:255-296`) finds the order, verifies that it has lines and changes its state to
`verified`. **It does not check quantities, it does not check stock and it does not deduct.** The deduction happens
later, in `updateOrderDetails` (see C3). Two "confirm" routes with different rules: an operator who
uses the wrong one believes they confirmed something that was not deducted.

## 4. Performance findings

### A1 — Complete history of the product on each operation

`getKardexByProductCode` (`kardex.service.ts:408-`) loads **all** the ledger rows of the product
(`findMany` with `where: { productCode, deleted: false }`, without a limit) and aggregates them in memory. It is called on each
packing (`order.service.ts:385`). The cost grows with each movement, even though the query only asks for
the balance.

**In the new design.** The balance comes from the projection (`stock_by_location`, `product_stock`), which is kept
in the database; no operation walks the history.

### A2 — Full load of the stock table

`getStoreByIdRange` (`kardex.service.ts:587-595`) does `store.findMany()` without a filter and discards rows in
JavaScript. It brings the entire catalog to return a range.

### A3 — Query per line (N+1) in lists of invoices and orders

- `getInvoiceDetail` (`invoice.service.ts:178-186`) does a `user.findUnique` for each line.
- `getOrderDetail` (`order.service.ts:92-115`) does the same.

With an invoice of 200 lines there are 201 queries for each screen opening.

### A4 — Product lookup inside loops in the location

`location.service.ts:276` and `:298` call `getProductByCode` inside a `for` loop for each element of the
shelf. Each call is one more query.

**Additionally**, the import of invoices (`kardo.controller.ts:22-70`) looks up each invoice with `findFirst` in
a `map` and then marks `Estatus = 1` with a sequential loop of `await` per record.

## 5. Medium findings

| # | Where | What happens | Why it matters |
|---|---|---|---|
| M1 | `invoice.service.ts:322` | `item.quantity === item.physicalQuantity` compares whatever arrives in the JSON | If the client sends `"5"` and not `5`, the line moves to `discrepancy` without having been one. Not reproduced; the comparison depends on the type |
| M2 | `order.service.ts:396` and `:449` | `console.log` with the complete stock state of the product | Writes inventory data to the production log on each operation |
| M3 | `kardo.controller.ts:22-70` | Import and processed-mark in separate steps | If the process is cut halfway, the next run reads again the ones that are missing; the protection depends on a `findFirst`, not on a unique key |

## 6. How it is corrected in the new design

| Finding | Requirement of the plan | Where it is tested |
|---|---|---|
| C1 modified ledger | Q6: the ledger only grows; correcting is a new movement | `existencias-y-ajustes.md` A9 |
| C2, C5 read-modify-write | Q7: the balance is changed by the database with a lock | `existencias-y-ajustes.md` A3, A4 |
| C3 outflow without await and requested quantity | Q8: outflow = pending to pick, in a transaction | `surtido-y-erp.md` S2 |
| C4 packing with a prior snapshot | Q9: availability inside the transaction | `existencias-y-ajustes.md` A3 |
| C6 two confirm routes | Q8: a single confirmation operation | `surtido-y-erp.md` §4 |
| A1 complete history | Q10: the balance comes from the projection | Performance test with 10,000 movements (phase 5) |
| A2, A3, A4 queries in a loop | Q10: a listing is one query with the data already joined | Test of the number of queries per listing (phase 5) |
| M1 fragile comparison | Type validation on input (the API rejects incorrect types) | Contract test (phase 2) |
| M2 data in the log | No log contains complete stock or quantities | Review in phase 5 |
| M3 non-idempotent import | Unique key per imported record | `catalogo.md` (import) |

## 7. What is not claimed

- The failures of C2 and C4 under real concurrency were not reproduced by running the legacy. The lost update
  was reproduced on the PostgreSQL engine of the new design ([existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) §5), which has the same pattern.
- The response time of A1–A4 was not measured. The figures of "200 lines" are illustrative.
- The legacy has real data in production; any correction to its data is decided separately.
