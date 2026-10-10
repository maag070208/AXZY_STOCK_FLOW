# Users and roles
> **Reference.** User administration: creation, lookup, editing, role assignment and password reset. Last verified: 2026-10-10 (phase 9).

Implemented in layers: routes in `api/adapters-web/.../identity/UserAdminController.kt`,
use cases in `api/application/.../identity/UserAdminUseCases.kt`, persistence in
`api/adapters-persistence/.../identity/JdbcUserAdmin.kt` and wiring in
`api/app/.../config/UserAdminConfig.kt`.

It is the gate to everything else: **whoever can assign roles can grant any
permission**. That is why everything written here is recorded in the audit log with level `CRITICAL`.

## Routes

| Route | Permission | What it does |
|---|---|---|
| `GET /v1/users` | `user.view` | Paginated listing, filters `q`, `role` and `active` |
| `POST /v1/users` | `user.create` | Creation, with its roles. **201** |
| `GET /v1/users/{id}` | `user.view` | Detail: roles, warehouses and when the password last changed |
| `PUT /v1/users/{id}` | `user.edit` / `role.assign` | Partial edit; what does not come stays the same. **Sending `roles` requires `role.assign`**: deciding what someone can do is not the same as correcting their name |
| `POST /v1/users/{id}/reset-password` | `user.reset_password` | New password and, by default, mandatory change |
| `POST /v1/users/{id}/revoke-sessions` | `session.revoke` | Closes **all** the open sessions of that person |
| `GET /v1/roles` | `role.manage` | The roles with how many permissions each one has |
| `POST /v1/roles` | `role.manage` | Creation of a role with its matrix. **201** |
| `GET /v1/roles/{key}` | `role.manage` | The role with **its matrix** and how many people have it |
| `PUT /v1/roles/{key}` | `role.manage` | Name, description and `active`; what does not come stays the same |
| `PUT /v1/roles/{key}/permissions` | `role.manage` | Replaces the matrix: **whatever does not come stops being granted** |
| `GET /v1/permissions` | `role.manage` | The catalog, which is **code**, not an editable table |

## What the implementation fixes

- **The password is never returned**, and the audit log does not record it: it says that it was changed, not
  what it is.
- **A new user is born required to change the password** unless stated otherwise: whoever
  created it knows it.
- **Resetting invalidates the open sessions.** The token's credentials stamp is
  `password_changed_at`, so when it is changed the live tokens stop matching (R3). If
  someone is reset because it is suspected their password was stolen, leaving the thief's token alive would mean having done nothing.
- **A user without roles is not admitted**: they could not do anything and would discover it on entering.
- The password is validated with the **same policy** as sign-in (NIST SP
  800-63B): `422 WEAK_PASSWORD`.

## Revoking another person's sessions

`POST /v1/users/{id}/revoke-sessions` answers `200` with `revokedSessions` (how many refresh
tokens were revoked). Zero **is not an error**: the person did not have a session open, which is
exactly what was wanted. `404 USER_NOT_FOUND` if the user does not exist.

It is the answer to "they stole the laptop" or "they leave today" without having to change their password,
which is a much bigger decision: you tell them, they change it, and with that you also invalidate what they were doing.

**What it cuts is the renewal, and it is worth saying so precisely.** The access token already
issued is a signed JWT that is not checked against the database on each request, so it lives until it
expires on its own. What is revoked are the refresh tokens, which are the ones that keep the
session alive. There are two nuances, both by design:

- A refresh token **already rotated** is still accepted during its **30-second grace window**,
  designed for the retry of a client whose network dropped. Revoking does not change it: the window belongs to the rotation, not to the session.
- To cut access **immediately**, there is password reset, which does move the credentials stamp
  (`password_changed_at`) and invalidates everything issued.

## A role's matrix

```json
PUT /v1/roles/SUPERVISOR_BODEGA/permissions
{ "permissions": [ { "permissionKey": "inventory.view", "scope": "ALL" },
                   { "permissionKey": "kardex.view", "scope": "AREA" } ] }
```

- **The permission catalog is code, not an editable table.** A matrix cannot grant
  a permission that does not exist in `PermissionCatalog`: it would be a permission nobody checks and
  would give a false sense of control (`422 UNKNOWN_PERMISSION`).
- **The scope has to be one of those the permission allows** (`422 SCOPE_NOT_ALLOWED` with
  `details.allowed`): granting `OWN` on a permission that only allows `ALL` is a cell that
  the engine does not know how to evaluate.
- **`NONE` is not granted**: to remove a cell, it is not sent.
- **A repeated permission is rejected**: two cells of the same permission with different scopes
  would leave the decision in the hands of the reading order.
- **`active: false` on the role removes the permissions from whoever has it**, without touching the matrix:
  it is the way to withdraw a role without losing what it granted.
- The role key is normalized to uppercase and only accepts letters, digits and underscore:
  it travels in the token and in the audit log, and with spaces it would end up being two different things
  depending on who types it.
- Every matrix change is recorded in the audit log **with the previous and the new matrix**, permission by
  permission, and with a note of how many people it affects.

> **The matrix version does not move.** `config_versions` stores the version that was seeded
> at startup, and it is the one the engine keeps in memory for the audit log trace: an
> edit through the API changes the permissions **immediately** —the actor is read from the database on each
> request— but the version that appears in the trace remains the process's until the next startup.

## An intermittent failure that was investigated

During phase 9, sending `roles` in the first `PUT` answered **`403` with an empty body** in
some runs and `200` in others. It was investigated: the rejection did not come from the access engine —the
matrix has `role.assign` with scope `ALL` for the ADMIN role and the same actor edits the profile without
problem— and it **was not reproduced again** after fixing the wiring of the password policy,
which in those runs left the context half-done. With the route enabled, role assignment
passed **five consecutive runs** of the complete module, and the test also checks that the roles
are written in the database.

The empty body of the original `403` **was left unexplained**, and it is stated so: no cause was made up.
If it appears again, what is known is in `PLAN-IMPLEMENTACION §6`.

## Errors

| Code | HTTP | When |
|---|---|---|
| `DUPLICATE_USERNAME` | 409 | the name already exists |
| `UNKNOWN_ROLE` | 422 | the role does not exist; `details.allowed` lists the ones that do |
| `WEAK_PASSWORD` | 422 | does not meet the policy |
| `FIELD_REQUIRED` | 422 | `username`, `name`, `password` or `roles` is missing |
| `USER_NOT_FOUND` | 404 | the user does not exist |
| `ROLE_NOT_FOUND` | 404 | the role does not exist |
| `DUPLICATE_ROLE` | 409 | a role with that key already exists |
| `UNKNOWN_PERMISSION` | 422 | a permission in the matrix is not in the catalog |
| `SCOPE_NOT_ALLOWED` | 422 | the permission does not allow that scope; `details.allowed` lists the ones that do |
| `PERMISSION_DENIED` | 403 | the route's permission is missing |

## Verification

`api/app/src/test/kotlin/dev/axzy/stock/app/RoleAdminFlowTest.kt`: that a new role grants
**what its matrix says and nothing more**, that removing a cell or deactivating the role removes
access for real, the detail with its matrix, the validation of permissions and scopes, the shape
of the key, and the per-route permissions.

`api/app/src/test/kotlin/dev/axzy/stock/app/UserAdminFlowTest.kt`: the listing with filters and
roles, the creation with its rejections, that a new user enters required to change the
password, the partial edit, the **role assignment** (with the roles written to the
database), the deactivation (which cuts access with `ACCOUNT_DISABLED`), that resetting leaves the open session out, and the per-route permissions.
