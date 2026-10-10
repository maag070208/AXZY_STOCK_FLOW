# Usuarios y roles
> **Referencia.** Administración de usuarios: alta, consulta, edición, asignación de roles y
> restablecimiento de contraseña. Última verificación: 2026-10-10 (fase 9).

Implementado en capas: rutas en `api/adapters-web/.../identity/UserAdminController.kt`,
casos de uso en `api/application/.../identity/UserAdminUseCases.kt`, persistencia en
`api/adapters-persistence/.../identity/JdbcUserAdmin.kt` y cableado en
`api/app/.../config/UserAdminConfig.kt`.

Es la puerta de todo lo demás: **quien puede asignar roles puede darse cualquier
permiso**. Por eso todo lo que se escribe aquí queda en la bitácora con nivel `CRITICAL`.

## Rutas

| Ruta | Permiso | Qué hace |
|---|---|---|
| `GET /v1/users` | `user.view` | Listado paginado, filtros `q`, `role` y `active` |
| `POST /v1/users` | `user.create` | Alta, con sus roles. **201** |
| `GET /v1/users/{id}` | `user.view` | Detalle: roles, bodegas y cuándo cambió la contraseña |
| `PUT /v1/users/{id}` | `user.edit` / `role.assign` | Edición parcial; lo que no viene queda igual. **Mandar `roles` exige `role.assign`**: decidir qué puede hacer alguien no es lo mismo que corregir su nombre |
| `POST /v1/users/{id}/reset-password` | `user.reset_password` | Contraseña nueva y, por defecto, cambio obligatorio |
| `POST /v1/users/{id}/revoke-sessions` | `session.revoke` | Cierra **todas** las sesiones abiertas de esa persona |
| `GET /v1/roles` | `role.manage` | Los roles con cuántos permisos tiene cada uno |
| `POST /v1/roles` | `role.manage` | Alta de un rol con su matriz. **201** |
| `GET /v1/roles/{key}` | `role.manage` | El rol con **su matriz** y cuántas personas lo tienen |
| `PUT /v1/roles/{key}` | `role.manage` | Nombre, descripción y `active`; lo que no viene queda igual |
| `PUT /v1/roles/{key}/permissions` | `role.manage` | Reemplaza la matriz: **lo que no venga, deja de estar concedido** |
| `GET /v1/permissions` | `role.manage` | El catálogo, que es **código**, no una tabla editable |

## Lo que la implementación fija

- **La contraseña nunca se devuelve**, y la bitácora no la registra: dice que se cambió, no
  cuál es.
- **Un usuario nuevo nace obligado a cambiar la contraseña** salvo que se diga lo
  contrario: quien la creó la conoce.
- **Restablecer invalida las sesiones abiertas.** El sello de credenciales del token es
  `password_changed_at`, así que al cambiarla los tokens vivos dejan de coincidir (R3). Si
  a alguien se le restablece porque sospecha que se la robaron, dejar vivo el token del
  ladrón sería no haber hecho nada.
- **Un usuario sin roles no se admite**: no podría hacer nada y lo descubriría al entrar.
- La contraseña se valida con la **misma política** que el inicio de sesión (NIST SP
  800-63B): `422 WEAK_PASSWORD`.

## Revocar las sesiones de otro

`POST /v1/users/{id}/revoke-sessions` responde `200` con `revokedSessions` (cuántos tokens de
refresco se revocaron). Cero **no es un error**: la persona no tenía la sesión abierta, que es
justo lo que se quería. `404 USER_NOT_FOUND` si el usuario no existe.

Es la respuesta a «le robaron el portátil» o «se va hoy» sin tener que cambiarle la contraseña,
que es una decisión mucho más grande: se la comunicas, la cambia, y con eso invalidas también lo
que estaba haciendo.

**Lo que corta es la renovación, y conviene decirlo con precisión.** El token de acceso ya
emitido es un JWT firmado que no se consulta en la base en cada petición, así que vive hasta que
expira por su cuenta. Lo que se revoca son los tokens de refresco, que son los que mantienen la
sesión viva. Quedan dos matices, ambos por diseño:

- Un token de refresco **ya rotado** sigue siendo aceptado durante su **ventana de gracia de 30
  segundos**, pensada para el reintento de un cliente al que se le cayó la red. Revocar no la
  cambia: la ventana es de la rotación, no de la sesión.
- Para cortar el acceso **de inmediato** está restablecer la contraseña, que sí mueve el sello de
  credenciales (`password_changed_at`) e invalida todo lo emitido.

## La matriz de un rol

```json
PUT /v1/roles/SUPERVISOR_BODEGA/permissions
{ "permissions": [ { "permissionKey": "inventory.view", "scope": "ALL" },
                   { "permissionKey": "kardex.view", "scope": "AREA" } ] }
```

- **El catálogo de permisos es código, no una tabla editable.** Una matriz no puede conceder
  un permiso que no exista en `PermissionCatalog`: sería un permiso que nadie comprueba y
  daría una falsa sensación de control (`422 UNKNOWN_PERMISSION`).
- **El alcance tiene que ser uno de los que el permiso admite** (`422 SCOPE_NOT_ALLOWED` con
  `details.allowed`): conceder `OWN` sobre un permiso que solo admite `ALL` es una celda que
  el motor no sabe evaluar.
- **`NONE` no se concede**: para quitar una celda, no se manda.
- **Un permiso repetido se rechaza**: dos celdas del mismo permiso con alcances distintos
  dejarían la decisión en manos del orden de lectura.
- **`active: false` en el rol le quita los permisos a quien lo tenga**, sin tocar la matriz:
  es la forma de retirar un rol sin perder lo que concedía.
- La clave del rol se normaliza a mayúsculas y solo admite letras, dígitos y guion bajo:
  viaja en el token y en la bitácora, y con espacios acabaría siendo dos cosas distintas
  según quién la escriba.
- Todo cambio de matriz queda en la bitácora **con la matriz anterior y la nueva**, permiso a
  permiso, y con una nota de a cuántas personas afecta.

> **La versión de la matriz no se mueve.** `config_versions` guarda la versión que se sembró
> al arrancar, y es la que el motor lleva en memoria para la traza de la bitácora: una
> edición por API cambia los permisos **de inmediato** —el actor se lee de la base en cada
> petición— pero la versión que aparece en la traza sigue siendo la del proceso hasta el
> siguiente arranque.

## Una intermitencia que se investigó

Durante la fase 9, mandar `roles` en el primer `PUT` respondió **`403` con el cuerpo vacío** en
algunas ejecuciones y `200` en otras. Se investigó: el rechazo no salía del motor de acceso —la
matriz tiene `role.assign` con alcance `ALL` para el rol ADMIN y el mismo actor edita el perfil sin
problema—, y **no se volvió a reproducir** tras arreglar el cableado de la política de contraseñas,
que en esas ejecuciones dejaba el contexto a medias. Con la ruta activada, la asignación de roles
pasó **cinco ejecuciones seguidas** del módulo completo, y la prueba comprueba además que los roles
quedan escritos en la base.

El cuerpo vacío del `403` original **quedó sin explicación**, y así se dice: no se inventó una causa.
Si vuelve a aparecer, lo que se sabe está en `PLAN-IMPLEMENTACION §6`.

## Errores

| Código | HTTP | Cuándo |
|---|---|---|
| `DUPLICATE_USERNAME` | 409 | el nombre ya existe |
| `UNKNOWN_ROLE` | 422 | el rol no existe; `details.allowed` trae los que hay |
| `WEAK_PASSWORD` | 422 | no cumple la política |
| `FIELD_REQUIRED` | 422 | falta `username`, `name`, `password` o `roles` |
| `USER_NOT_FOUND` | 404 | el usuario no existe |
| `ROLE_NOT_FOUND` | 404 | el rol no existe |
| `DUPLICATE_ROLE` | 409 | ya existe un rol con esa clave |
| `UNKNOWN_PERMISSION` | 422 | un permiso de la matriz no está en el catálogo |
| `SCOPE_NOT_ALLOWED` | 422 | el permiso no admite ese alcance; `details.allowed` trae los que sí |
| `PERMISSION_DENIED` | 403 | falta el permiso de la ruta |

## Verificación

`api/app/src/test/kotlin/dev/axzy/stock/app/RoleAdminFlowTest.kt`: que un rol nuevo conceda
**lo que dice su matriz y nada más**, que quitar una celda o desactivar el rol quite el
acceso de verdad, el detalle con su matriz, la validación de permisos y alcances, la forma
de la clave, y los permisos por ruta.

`api/app/src/test/kotlin/dev/axzy/stock/app/UserAdminFlowTest.kt`: el listado con filtros y
roles, el alta con sus rechazos, que un usuario nuevo entra obligado a cambiar la
contraseña, la edición parcial, la **asignación de roles** (con los roles escritos en la
base), la desactivación (que corta el acceso con `ACCOUNT_DISABLED`), que restablecer deja
fuera la sesión abierta, y los permisos por ruta.
