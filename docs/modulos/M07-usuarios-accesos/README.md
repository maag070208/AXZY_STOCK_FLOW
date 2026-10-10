# M07 — Usuarios y accesos: roles, permisos, sesiones
> **Normativo.** Última verificación: 2026-10-10.

| | |
|---|---|
| **Código** | M07 |
| **Carpeta** | `docs/modulos/M07-usuarios-accesos/` |
| **Documentado** | Sí |
| **Terminado (backend)** | Sí: sesiones y permisos, usuarios con alta/consulta/edición/asignación/restablecimiento, y roles con su matriz (crear, editar, activar y conceder) |
| **Web** | `UsersPage` |
| **App** | `features/auth` |

## 1. Objetivo

Quién entra, con qué credenciales y qué puede hacer. Es la puerta de todo lo demás.

## 2. Alcance

| Dentro | Fuera |
|---|---|
| Login, refresh con rotación, logout y cambio de contraseña | Recuperación por correo |
| Usuarios y roles: consulta, altas, edición, asignación y matriz de permisos | Eliminar roles (se desactivan) y la interfaz de políticas ABAC |
| Alcances por bodega (`user_warehouse_scopes`) | Políticas ABAC con interfaz de edición (pendiente) |
| Bloqueo por intentos fallidos | |

## 3. Reglas de negocio

| # | Regla | Dónde vive |
|---|---|---|
| R1 | Denegar por defecto; la identidad se comprueba antes que los permisos | [ADR-0006](../../adr/0006-rbac-mas-abac-denegar-por-defecto.md); `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/` |
| R2 | Reusar un refresh token revocado invalida la familia completa | `TOKEN_REUSED`; `SessionModelTest` |
| R3 | Un cambio de contraseña invalida las sesiones abiertas | `SessionModelTest` |
| R4 | Las contraseñas cumplen la política | `PasswordPolicyTest` en `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/identity/` |
| R5 | Los permisos se siembran desde el catálogo, no desde la migración | `IdentitySeeder.kt` desde `PermissionCatalog.kt` |
| R6 | Todo permiso tiene alcance `NONE`, `OWN`, `AREA` o `ALL`; se toma el mayor | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/` (`AccessEngineTest`) |

## 4. Modelo de datos

| Tabla | Migración | Qué guarda |
|---|---|---|
| `users`, `roles`, `permissions`, `role_permissions`, `user_roles`, `user_warehouse_scopes` | `V1` | Identidad y RBAC |
| `refresh_tokens`, `login_attempts` | `V4` | Sesiones y bloqueos |

## 5. API

Contrato en [api/autenticacion.md](../../api/autenticacion.md). Rutas en
`identity/AuthRoutes.kt` e `identity/IdentityAdminRoutes.kt`.

## 6. Permisos

`user.view`, `user.create`, `user.edit`, `user.delete`, `user.reset_password`, `role.manage`,
`role.assign`, `permission.exception`. El catálogo completo está en
`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/PermissionCatalog.kt`; la matriz, en
[seguridad/roles-permisos.md](../../seguridad/roles-permisos.md).

## 7. Bitácora

Las rutas de identidad que escriben (login, refresh, cambio de contraseña) registran en
`audit_log`. **No está verificado** que el alta, la edición y el cambio de rol de usuarios
escriban en la bitácora: esas rutas todavía no existen. Ver
[seguridad/auditoria.md](../../seguridad/auditoria.md).

## 8. Pruebas

`AccessEngineTest`, `SessionModelTest`, `PasswordPolicyTest` en `stock-domain`.

## 9. Criterios de aceptación

- [x] Un refresh token reusado revoca la familia (`SessionModelTest`).
- [ ] Altas, edición, asignación de roles y restablecimiento de contraseña por admin (sin rutas hoy).
- [x] Pantalla de usuarios en modo consulta, probada en navegador (`web/e2e/flow.spec.ts`).
