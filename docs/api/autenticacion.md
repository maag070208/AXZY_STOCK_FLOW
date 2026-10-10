# Autenticación
> **Referencia.** Las rutas de sesión: inicio, renovación con rotación, cierre,
> usuario actual y cambio de contraseña. Última verificación: 2026-10-09.

Las rutas están en
`api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/identity/AuthController.kt`, y cada
una llama a un caso de uso de `api/application/.../identity/`: `Login`, `RefreshSession`, `Logout`,
`ChangePassword` y `ResolveActor`. El cuerpo de error y su catálogo están en
[errores.md](errores.md); las convenciones de cabeceras y fechas, en [convenciones.md](convenciones.md).

**El token de acceso solo identifica.** Es un JWT firmado con HMAC-SHA256 que lleva el id del
usuario, la familia de la sesión y un sello de credenciales. **No lleva roles ni permisos**: se leen
de la base en cada petición, así que quitar un rol o desactivar una cuenta surte efecto en la
petición siguiente y no cuando el token caduca.

## `POST /v1/auth/login`

```json
{ "username": "jperez", "password": "..." }
```

**200:**

```json
{
  "accessToken": "eyJ...",
  "refreshToken": "8Kd3...",
  "expiresIn": 900,
  "user": {
    "id": 7, "username": "jperez", "name": "Juan Pérez",
    "roles": ["JEFE_BODEGA"],
    "permissions": ["invoice.capture", "invoice.approve", "inventory.move"],
    "scopes": { "invoice.approve": "AREA", "inventory.move": "AREA" },
    "warehouses": [1],
    "mustChangePassword": false
  }
}
```

**Errores:**

| `code` | Estado | Situación |
|---|---|---|
| `FIELD_REQUIRED` | 400 | falta `username` o `password` |
| `INVALID_CREDENTIALS` | 401 | usuario o contraseña incorrectos — **el mismo código para ambos**, para no permitir enumerar usuarios |
| `ACCOUNT_DISABLED` | 401 | la cuenta está desactivada. **Solo se devuelve si la contraseña es correcta** |
| `ACCOUNT_LOCKED` | 401 | cinco fallos de credenciales con ese nombre en 15 minutos |
| `RATE_LIMITED` | 429 | demasiados intentos desde esta IP. **Declarado; todavía no hay limitador de tasa** |

**No se puede averiguar si un usuario existe.** Tres reglas lo garantizan (`Login.kt`):

1. Usuario inexistente y contraseña incorrecta devuelven el mismo cuerpo. Cuando no hay cuenta se
   verifica igualmente contra un hash señuelo, para que la respuesta tarde lo mismo.
2. El bloqueo se decide por **nombre de usuario**, exista o no la cuenta: se cuentan los fallos en
   `login_attempts`. Si solo se bloquearan las cuentas reales, cinco intentos bastarían para saber
   cuáles lo son.
3. «Cuenta desactivada» solo se dice a quien acertó la contraseña.

El bloqueo es temporal y **los intentos hechos durante el bloqueo no lo prolongan**: si lo hicieran,
bastaría una petición cada cierto tiempo para dejar una cuenta bloqueada para siempre.

Los intentos de login van a `login_attempts`, no a la bitácora: se escriben mucho más, y un atacante
no debe poder inundar un libro que se escribe en serie.

`expiresIn` en **segundos**, que es lo que esperan las bibliotecas de cliente. El
`refreshToken` se guarda **hasheado** (SHA-256): el valor en claro solo existe en
esta respuesta.

El mensaje de error es **el mismo para usuario inexistente y contraseña
incorrecta**: distinguirlos permite enumerar qué nombres de usuario son reales y,
con la lista de nombres, un atacante solo necesita acertar contraseñas. La
contraseña se verifica con Argon2id contra el hash guardado; si el hash usa
parámetros obsoletos se registra para regenerarlo.

## `POST /v1/auth/refresh`

```json
{ "refreshToken": "8Kd3..." }
```

**200:** el mismo cuerpo que el login, con tokens **nuevos** (rotación). El token
presentado queda marcado como rotado, no como revocado: sigue siendo un registro
válido de la cadena.

**401 con `code = TOKEN_REUSED`:** se detectó reuso y **se revocó toda la
familia**. El cliente debe descartar sus tokens y pedir credenciales. **Es un
código distinto de `INVALID_CREDENTIALS`** porque la interfaz debe decir algo
diferente: «tu sesión se cerró por seguridad».

Otros códigos: `INVALID_CREDENTIALS` (token desconocido, revocado o vencido),
`ACCOUNT_DISABLED` y `UNAUTHENTICATED` (el usuario ya no existe). Un cambio de
contraseña posterior a la emisión del token también lo invalida.

La decisión de rotar, vencer o detectar reuso la toma el dominio
(`decideRefresh`), y la ruta solo traduce el resultado a HTTP.

## `POST /v1/auth/logout`

**204.** Revoca el refresh token presentado y su familia.

**Es idempotente y no exige token válido**: el cuerpo es opcional. Si el cliente
quiere cerrar sesión, hay que dejarlo; responder `401` dejaría la sesión viva en el
servidor justo cuando el usuario pide lo contrario.

## `GET /v1/auth/me`

Devuelve el objeto `user` del login. Es lo que la web usa al recargar para reconstruir los permisos
sin volver a autenticar. La ruta **relee de la base**: los permisos pueden haber cambiado desde que se
emitió el token.

**401** con `code = UNAUTHENTICATED` si falta el token o el usuario ya no existe;
`ACCOUNT_DISABLED` si la cuenta está desactivada.

Se puede llamar con el cambio de contraseña pendiente, igual que `change-password` y `logout`.
Cualquier otra ruta responde entonces `403 PASSWORD_CHANGE_REQUIRED`.

## `POST /v1/auth/change-password`

```json
{ "currentPassword": "...", "newPassword": "..." }
```

**204.** **Invalida todas las sesiones**, incluida la actual: el cliente debe
volver a entrar.

La contraseña nueva se valida contra la política del dominio (`PasswordPolicy`).
Errores:

| `code` | Estado | Situación |
|---|---|---|
| `UNAUTHENTICATED` | 401 | falta el token |
| `INVALID_CREDENTIALS` | 401 | la contraseña actual no es correcta |
| `WEAK_PASSWORD` | 422 | la contraseña nueva no cumple la política |
| `SAME_PASSWORD` | 422 | la contraseña nueva es igual a la actual |

Cambiar la contraseña cierra **todas** las sesiones: quien la cambia suele sospechar que está
comprometida, y dejar vivas las demás mantendría el acceso del atacante justo cuando el usuario
intenta cerrarlo.

El cierre es **inmediato y exacto**. El cambio y la revocación de los refresh tokens van en la misma
transacción, y los tokens de acceso ya emitidos dejan de servir en la petición siguiente porque su
sello de credenciales ya no coincide con el de la cuenta. No depende de comparar fechas.

## Verificación

| Archivo | Qué cubre |
|---|---|
| `api/app/src/test/kotlin/dev/axzy/stock/app/AuthFlowTest.kt` | Todas las rutas por HTTP real: login, bloqueo, refresh con reuso, logout, cambio de contraseña |
| `api/adapters-security/src/test/.../SecurityAdaptersTest.kt` | Argon2id, firma y verificación de tokens, refresh tokens |
| `api/adapters-persistence/src/test/.../SessionPersistenceTest.kt` | Persistencia de sesiones y dos renovaciones simultáneas |
| `stock-domain/src/commonTest/.../identity/SessionModelTest.kt` | La decisión de refresh, como función pura |
