# M07 — Users and access: roles, permissions, sessions
> **Normative.** Last verified: 2026-10-10.

| | |
|---|---|
| **Code** | M07 |
| **Folder** | `docs/modulos/M07-usuarios-accesos/` |
| **Documented** | Yes |
| **Finished (backend)** | Yes: sessions and permissions, users with creation/lookup/edit/assignment/reset, and roles with their matrix (create, edit, activate and grant) |
| **Web** | `UsersPage` |
| **App** | `features/auth` |

## 1. Objective

Who enters, with which credentials and what they can do. It is the gate to everything else.

## 2. Scope

| Inside | Outside |
|---|---|
| Sign-in, refresh with rotation, sign-out and password change | Password recovery by email |
| Users and roles: lookup, creation, edit, assignment and permission matrix | Deleting roles (they are deactivated) and the ABAC policy interface |
| Warehouse scopes (`user_warehouse_scopes`) | ABAC policies with an edit interface (pending) |
| Lock after failed attempts | |

## 3. Business rules

| # | Rule | Where it lives |
|---|---|---|
| R1 | Deny by default; identity is checked before permissions | [ADR-0006](../../adr/0006-rbac-mas-abac-denegar-por-defecto.md); `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/` |
| R2 | Reusing a revoked refresh token invalidates the entire family | `TOKEN_REUSED`; `SessionModelTest` |
| R3 | A password change invalidates the open sessions | `SessionModelTest` |
| R4 | Passwords meet the policy | `PasswordPolicyTest` in `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/identity/` |
| R5 | Permissions are seeded from the catalog, not from the migration | `IdentitySeeder.kt` from `PermissionCatalog.kt` |
| R6 | Every permission has scope `NONE`, `OWN`, `AREA` or `ALL`; the greatest one is taken | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/` (`AccessEngineTest`) |

## 4. Data model

| Table | Migration | What it holds |
|---|---|---|
| `users`, `roles`, `permissions`, `role_permissions`, `user_roles`, `user_warehouse_scopes` | `V1` | Identity and RBAC |
| `refresh_tokens`, `login_attempts` | `V4` | Sessions and locks |

## 5. API

Contract in [api/autenticacion.md](../../api/autenticacion.md). Routes in
`identity/AuthRoutes.kt` and `identity/IdentityAdminRoutes.kt`.

## 6. Permissions

`user.view`, `user.create`, `user.edit`, `user.delete`, `user.reset_password`, `role.manage`,
`role.assign`, `permission.exception`. The full catalog is in
`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/PermissionCatalog.kt`; the matrix, in
[seguridad/roles-permisos.md](../../seguridad/roles-permisos.md).

## 7. Audit log

The identity routes that write (sign-in, refresh, password change) record in
`audit_log`. **It is not verified** that the creation, edit and role change of users write to the log: those routes
do not exist yet. See [seguridad/auditoria.md](../../seguridad/auditoria.md).

## 8. Tests

`AccessEngineTest`, `SessionModelTest`, `PasswordPolicyTest` in `stock-domain`.

## 9. Acceptance criteria

- [x] A reused refresh token revokes the family (`SessionModelTest`).
- [ ] Creation, edit, role assignment and password reset by an admin (no routes today).
- [x] Users screen in read-only mode, tested in the browser (`web/e2e/flow.spec.ts`).
