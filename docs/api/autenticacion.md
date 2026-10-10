# Authentication
> **Reference.** The session routes: sign-in, renewal with rotation, sign-out, current user and password change. Last verified: 2026-10-09.

The routes are in
`api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/identity/AuthController.kt`, and each
one calls a use case in `api/application/.../identity/`: `Login`, `RefreshSession`, `Logout`,
`ChangePassword` and `ResolveActor`. The error body and its catalog are in
[errores.md](errores.md); the header and date conventions, in [convenciones.md](convenciones.md).

**The access token only identifies.** It is an HMAC-SHA256-signed JWT that carries the user's id, the session
family and a credentials stamp. **It carries no roles or permissions**: they are read
from the database on each request, so removing a role or deactivating an account takes effect on the
next request and not when the token expires.

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

**Errors:**

| `code` | Status | Situation |
|---|---|---|
| `FIELD_REQUIRED` | 400 | `username` or `password` is missing |
| `INVALID_CREDENTIALS` | 401 | wrong user or password — **the same code for both**, so as not to allow enumerating users |
| `ACCOUNT_DISABLED` | 401 | the account is deactivated. **It is only returned if the password is correct** |
| `ACCOUNT_LOCKED` | 401 | five credential failures with that name in 15 minutes |
| `RATE_LIMITED` | 429 | too many attempts from this IP. **Declared; there is no rate limiter yet** |

**It cannot be found out whether a user exists.** Three rules guarantee it (`Login.kt`):

1. A nonexistent user and a wrong password return the same body. When there is no account, the password is
   verified anyway against a decoy hash, so the response takes the same time.
2. The lock is decided by **user name**, whether or not the account exists: failures are counted in
   `login_attempts`. If only real accounts were locked, five attempts would be enough to find out
   which ones are real.
3. "Account disabled" is only told to whoever guessed the password.

The lock is temporary and **the attempts made during the lock do not extend it**: if they did,
a single request every so often would leave an account locked forever.

Login attempts go to `login_attempts`, not to the audit log: they are written far more often, and an attacker
must not be able to flood a book that is written in series.

`expiresIn` in **seconds**, which is what client libraries expect. The
`refreshToken` is stored **hashed** (SHA-256): the plain value only exists in
this response.

The error message is **the same for a nonexistent user and a wrong password**: distinguishing them allows
enumerating which user names are real and, with the list of names, an attacker only needs to guess
passwords. The password is verified with Argon2id against the stored hash; if the hash uses
obsolete parameters it is recorded so that it is regenerated.

## `POST /v1/auth/refresh`

```json
{ "refreshToken": "8Kd3..." }
```

**200:** the same body as login, with **new** tokens (rotation). The presented token
is marked as rotated, not as revoked: it remains a valid record of the chain.

**401 with `code = TOKEN_REUSED`:** reuse was detected and **the entire family was revoked**. The client must discard its tokens and ask for credentials. **It is a
code different from `INVALID_CREDENTIALS`** because the interface must say something
different: "your session was closed for security reasons".

Other codes: `INVALID_CREDENTIALS` (unknown, revoked or expired token),
`ACCOUNT_DISABLED` and `UNAUTHENTICATED` (the user no longer exists). A password change after the token was issued also invalidates it.

The decision to rotate, expire or detect reuse is made by the domain
(`decideRefresh`), and the route only translates the result to HTTP.

## `POST /v1/auth/logout`

**204.** Revokes the presented refresh token and its family.

**It is idempotent and does not require a valid token**: the body is optional. If the client
wants to sign out, it must be left to do so; answering `401` would leave the session alive on the
server exactly when the user asks for the opposite.

## `GET /v1/auth/me`

Returns the `user` object of the login. It is what the web uses when reloading to rebuild the permissions
without authenticating again. The route **re-reads from the database**: the permissions may have changed since the
token was issued.

**401** with `code = UNAUTHENTICATED` if the token is missing or the user no longer exists;
`ACCOUNT_DISABLED` if the account is deactivated.

It can be called with a pending password change, the same as `change-password` and `logout`.
Any other route then answers `403 PASSWORD_CHANGE_REQUIRED`.

## `POST /v1/auth/change-password`

```json
{ "currentPassword": "...", "newPassword": "..." }
```

**204.** **It invalidates all sessions**, including the current one: the client must
sign in again.

The new password is validated against the domain policy (`PasswordPolicy`).
Errors:

| `code` | Status | Situation |
|---|---|---|
| `UNAUTHENTICATED` | 401 | the token is missing |
| `INVALID_CREDENTIALS` | 401 | the current password is not correct |
| `WEAK_PASSWORD` | 422 | the new password does not meet the policy |
| `SAME_PASSWORD` | 422 | the new password is the same as the current one |

Changing the password closes **all** sessions: whoever changes it usually suspects it has been
compromised, and leaving the others alive would keep the attacker's access exactly when the user
tries to close it.

The closing is **immediate and exact**. The change and the revocation of the refresh tokens are in the same
transaction, and the access tokens already issued stop working on the next request because their
credentials stamp no longer matches the account's. It does not depend on comparing dates.

## Verification

| File | What it covers |
|---|---|
| `api/app/src/test/kotlin/dev/axzy/stock/app/AuthFlowTest.kt` | All the routes over real HTTP: login, lock, refresh with reuse, logout, password change |
| `api/adapters-security/src/test/.../SecurityAdaptersTest.kt` | Argon2id, token signing and verification, refresh tokens |
| `api/adapters-persistence/src/test/.../SessionPersistenceTest.kt` | Session persistence and two simultaneous renewals |
| `stock-domain/src/commonTest/.../identity/SessionModelTest.kt` | The refresh decision, as a pure function |
