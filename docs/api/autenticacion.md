# Autenticación
> **Referencia.** Las rutas de sesión: inicio, renovación con rotación, cierre,
> usuario actual y cambio de contraseña. Última verificación: 2026-10-09.

Implementadas en
`backend/src/main/kotlin/dev/axzy/stock/backend/identity/AuthRoutes.kt`. El cuerpo
de error y su catálogo están en [errores.md](errores.md); las convenciones de
cabeceras y fechas, en [convenciones.md](convenciones.md).

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
| `ACCOUNT_DISABLED` | 401 | la cuenta está desactivada |
| `ACCOUNT_LOCKED` | 401 | bloqueada por intentos fallidos; el instante del desbloqueo se conserva en `locked_until` |
| `RATE_LIMITED` | 429 | demasiados intentos desde esta IP. **Declarado; la implementación aún no tiene limitador de tasa** |

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

Devuelve el objeto `user` del login. Es lo que la web usa al recargar para
reconstruir los permisos sin volver a autenticar. La ruta **relee de la base y no
devuelve el contenido del token**: los permisos pueden haber cambiado desde que se
emitió.

**401** con `code = UNAUTHENTICATED` si falta el token o el usuario ya no existe;
`ACCOUNT_DISABLED` si la cuenta está desactivada.

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

Cambiar la contraseña cierra **todas** las sesiones: quien la cambia suele
sospechar que está comprometida, y dejar vivas las demás mantendría el acceso del
atacante justo cuando el usuario intenta cerrarlo.

## Verificación

| Archivo | Qué cubre |
|---|---|
| `backend/src/test/kotlin/dev/axzy/stock/backend/identity/TokenServiceTest.kt` | emisión y verificación de tokens |
| `backend/src/test/kotlin/dev/axzy/stock/backend/identity/NativeArgon2PasswordHasherTest.kt` | hash y verificación de contraseñas |
| `backend/src/test/kotlin/dev/axzy/stock/backend/ApiE2ETest.kt` | login y `me` contra la API montada |
