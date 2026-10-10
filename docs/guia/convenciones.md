# Cross-cutting conventions: language, comments and standards
> **Normative.** Mandatory in the four parts of the monorepo. Last verified: 2026-10-09.
> **Reconstruction note (2026-10-10).** Conventions in force; the paths are from the previous backend. The rules remain in force; the code paths it cites belong to the previous system and are remapped to the new code (see [plan/PLAN-IMPLEMENTACION.md](../plan/PLAN-IMPLEMENTACION.md)).
> **Update (2026-10-10).** Section 1 is updated to [ADR 0018](../adr/0018-todo-en-ingles.md), which replaces ADR 0004 and ADR 0017: everything in the project is in English.

The decision about the language is in
[ADR 0018](../adr/0018-todo-en-ingles.md) (which replaces [ADR 0004](../adr/0004-nombres-en-ingles-comentarios-en-espanol.md)).

## 1. Language

> **Everything in English: identifiers, comments, test names, error messages, interface text,
> data codes and documentation.**

```kotlin
// ✅
fun approveInvoice(invoiceId: Long, reason: AdjustmentReason)

// ❌
fun aprobarFactura(facturaId: Long, motivo: AdjustmentReason)
```

**The only exceptions, because they are data and not text (ADR 0018):**

| What | Why |
|---|---|
| The unit of measure `PZA` of the legacy catalog | It is a data value that comes from the legacy catalog |
| The letter `Ñ` of the product code alphabet | It is part of the code alphabet, not translatable text |

**The table and column names are already in English** and are not touched. The SQL engine functions
(`aplicar_movimiento_a_ubicacion`, `recalcular_total_producto`, `registrar_movimiento`,
`comprobar_proyecciones`) are still in Spanish: that is a pending step of migration V17 (ADR 0018),
and they are an internal detail of the database that does not leak into the HTTP contract
(`backend/src/main/resources/db/migration/`).

## 2. Comments: the house style

**They explain the why, not the what.** The what is already said by the code.

```kotlin
// ❌ Adds nothing
// Adds the quantity to the balance
quantity = quantity + delta

// ✅ Explains the decision and the pitfall
// The qualification is MANDATORY: in `DO UPDATE`, an unqualified name resolves to the
// PROPOSED row, so `quantity + delta` computed `delta + delta` and the balance
// NEVER accumulated.
quantity = stock_by_location.quantity + delta
```

**Pitfalls are documented.** A comment that says "this was tried and it broke X" is worth more than ten that describe the obvious: it keeps the next person from repeating it.

## 3. Code standards — the mandatory checklist

**Each point is checked before a task is considered finished.** It is not a style guide: it is what is verified.

| # | Rule | The failure it prevents |
|---|---|---|
| 1 | **Explicit visibility** (`private`, `internal`, `public`) | Everything is `public` by default and any file can touch anything |
| 2 | **Immutability by default**: `val`, **read-only** collections | A shared `MutableList` changes "by itself" from another place |
| 3 | **Explicit return type in public API** | Changing the return type of a public `fun` **without touching the signature** breaks its callers |
| 4 | **Null safety**: `?.`, `?:`, smart casts | The `NullPointerException` in production |
| 5 | **`!!` forbidden** | A `!!` is an **assertion without proof**: it turns a missing datum into a crash |
| 6 | **Extension functions** for utilities | Loose utilities in `Utils` classes that nobody knows where they live |
| 7 | **Constructor injection** ([api-ktor.md](../historico/API-KTOR.md) §1.8) | Hidden dependencies and classes that cannot be tested |
| 8 | **Everything has to be testable** | Code that can only be tested by starting the server |

```kotlin
// ✅ The eight rules, in one function: `internal`, `val`, explicit return,
//    null-safe, no `!!`, and as an extension function.
internal fun ResultSet.toStockByLocationDto(): StockByLocationDto? {
    val code: String = getString("product_code") ?: return null
    val quantity: String = getBigDecimal("quantity")?.toPlainString() ?: "0.000"
    return StockByLocationDto(productCode = code, quantity = quantity)
}

// ❌ Three rules broken on one line: public without return type,
//    a `var` that is not needed and `!!` instead of `?:`.
fun toCode(rs: ResultSet) { var code = rs.getString("product_code")!! }
```
