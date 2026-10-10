# Audit
> **Normative.** What is recorded, with which guarantees, and what is missing. Last verified: 2026-10-09.
> **Reconstruction note (2026-10-10).** Audit log rules in force; the paths are from the previous backend. The rules remain in force; the code paths it cites belong to the previous system and are remapped to the new code (see [plan/PLAN-IMPLEMENTACION.md](../plan/PLAN-IMPLEMENTACION.md)).

**Every change of state records who, what, on what, when, from where and with which
permission.** The table `audit_log` has the six dimensions, monthly partitions, a hash chain
and immutability triggers
(`backend/src/main/resources/db/migration/V2__audit_and_traceability.sql`).

## How it is written

The writer is `backend/src/main/kotlin/dev/axzy/stock/backend/infrastructure/outbound/persistence/audit/AuditWriter.kt`.
`record` receives **the caller's connection** and participates in its transaction.

**If the audit fails, the operation is reverted with it.** It is deliberate: if the inventory movement
is applied and its record is not, **the inventory changed without leaving a trace of who
changed it**, and that is not discovered until someone asks who authorized a difference. A
system that audits sometimes is more dangerous than one that does not audit, because it generates trust
without deserving it. The price, stated plainly: if the audit table is not available, the
warehouse stops being able to operate.

**Deliberate exception: access denials.** `recordDeniedAccessSafely` uses `runCatching` and its own
connection. If the audit cannot write, **access is still denied**: denying access is the security decision and it is already
taken; the record is information for later. If a failure to record **opened** the access, it would turn
an audit problem into a security hole.

The database enforces guarantees that do not depend on the code remembering them:

- `audit_human_has_actor`: a `WEB` action without an identified user is not auditable.
- `audit_critical_has_trace`: a `CRITICAL` record requires correlation, request or idempotency
  key. Without it, the `INSERT` fails.
- `audit_log_record` (migration `V7__audit_write_and_verify.sql`) maintains the **hash chain**
  and serializes the insertion with `pg_advisory_xact_lock(hashtext('audit_log_chain'))`
  so that it cannot fork.

## The audit log is append-only

There is no `UPDATE` nor `DELETE`: the triggers make them fail **on purpose**, and the application's
`GRANT` only gives `SELECT, INSERT` on `audit_log`
(`V2__audit_and_traceability.sql`). Corrections are new records.

## What is audited today

| Scope | Status |
|---|---|
| **Access denials** | **Recorded.** `AccessEnginePermissionChecker` calls `AuditWriter.recordDeniedAccessSafely` (`backend/src/main/kotlin/dev/axzy/stock/backend/security/PermissionChecker.kt`): every denied access leaves an entry with who, which permission, on which route, from which channel, with which IP and with which decision. The API's e2e test establishes it (`backend/src/test/kotlin/dev/axzy/stock/backend/ApiE2ETest.kt`, "the audit records denied accesses"): a denied permission leaves an entry with channel, permission and result |
| **Inventory and receiving** | Already write their audit |
| **Catalog, picking, transfers and labels** | **Missing.** They move inventory without leaving a trace in `audit_log` |
| **Read operations** | Not audited; they do not change state |

**Why denials were started with:** they are the security case. A system that denies without
leaving a record cannot answer the only thing that matters after an incident —how many
times it was attempted and from where—, and an attack against permissions is indistinguishable from a
user who went to the wrong screen.

The detail of what is missing is in
[lo-no-verificado.md](../historico/LO-NO-VERIFICADO-ANTERIOR.md). The permissions and the access decision
are in [roles-permisos.md](roles-permisos.md).
