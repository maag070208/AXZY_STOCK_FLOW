# Cómo arrancar el proyecto

**Tres comandos, en tres terminales.** No hace falta configurar nada a mano: los
scripts derivan la contraseña de `infra/.env` y generan el secreto de firma.

```bash
# 1. La base de datos (una sola vez, se queda corriendo)
cd infra && docker compose up -d

# 2. El backend                              → http://127.0.0.1:8082
./scripts/api.sh

# 3. La web                                  → http://127.0.0.1:5173
./scripts/web.sh
```

Y abres **http://127.0.0.1:5173**.

---

## Credenciales

| | |
|---|---|
| Usuario | `admin` |
| Contraseña | `PruebaManual2026xyz` |

**La primera vez que entres te obliga a cambiarla** (`mustChangePassword`). Es
deliberado: una contraseña que se imprime por consola es un secreto compartido
hasta que se cambia.

### Crear otro administrador

Si borras el usuario y siembras de nuevo, **la contraseña se genera y se imprime
una sola vez**:

```bash
docker exec stock-postgres psql -U stock_app -d stock -c \
  "DELETE FROM refresh_tokens; DELETE FROM user_roles; DELETE FROM users;"

STOCK_SEED=true ./scripts/api.sh
# La contraseña aparece en la salida, en un recuadro, y NO se vuelve a mostrar.
```

O la fijas tú, que es más cómodo para desarrollo:

```bash
STOCK_SEED=true STOCK_ADMIN_PASSWORD="MiClaveDePrueba2026" ./scripts/api.sh
```

---

## Los scripts

| Script | Qué hace | Puerto |
|---|---|---|
| `./scripts/api.sh` | Compila y arranca el backend | 8082 |
| `./scripts/web.sh` | Arranca Vite con el proxy al backend | 5173 |
| `./scripts/movil-android.sh` | Compila el APK | — |
| `./scripts/movil-android.sh instalar` | Compila e instala en el dispositivo | — |
| `./scripts/ios.sh` | Compila el framework de iOS | — |

### Opciones útiles

```bash
PORT=9000 ./scripts/api.sh              # otro puerto de backend
API_PORT=9000 ./scripts/web.sh          # la web apunta a ese puerto
STOCK_SEED=true ./scripts/api.sh        # siembra roles, permisos y admin
STOCK_DB_RUN_MIGRATIONS=true ./scripts/api.sh   # aplica las migraciones al arrancar
```

**El backend falla si falta una variable de entorno, y es lo correcto**: un
despliegue que arranca con credenciales por defecto es un despliegue vulnerable
que nadie nota. El script deriva lo que puede del `infra/.env` que ya existe.

**El secreto de firma se genera nuevo en cada arranque**, así que **los tokens
anteriores dejan de servir al reiniciar**. Para que sobrevivan:

```bash
export STOCK_JWT_SECRET="$(openssl rand -base64 48)"
./scripts/api.sh
```

---

## La app móvil

El APK se compila y se instala con:

```bash
./scripts/movil-android.sh instalar
```

**`mobile-app/androidApp` es un build de Gradle independiente y no un submódulo
del monorepo.** Con AGP 9, `com.android.application` y el plugin de KMP no pueden
coexistir, y el fallo **tumba la configuración de todo el proyecto**. Por eso el
script entra en esa carpeta. Ver `PLAN_ALMACEN_INTEGRAL.md` §10-bis.

Consecuencia que hay que tener presente: **el APK no participa en
`./gradlew build` de la raíz.** El CI tiene un job aparte para compilarlo, porque
la omisión sería silenciosa: todo pasaría y el APK quedaría sin construir.

### iOS

```bash
./scripts/ios.sh      # genera MobileApp.framework
```

Luego se abre `mobile-app/iosApp/iosApp.xcodeproj` en Xcode.

**Pendiente y sin verificar, y conviene saberlo:** el `pbxproj` se escribió a mano
sin abrirlo nunca en Xcode, y **la cámara no se ha probado en un iPhone real**. El
framework de Kotlin sí compila y enlaza, que es lo que valida el código compartido
y el `actual` de AVFoundation, pero la conversión de coordenadas del escáner está
razonada y comentada, **no ejecutada**. Es lo primero que hay que probar en
dispositivo.

---

## Qué está verificado y qué no

### Verificado ejecutándolo

```
POST /v1/auth/login              → 200, admin, rol ADMIN, 76 permisos
POST /v1/auth/refresh            → 200, token rotado
POST /v1/auth/refresh (viejo)    → 401 TOKEN_REUSED, familia revocada
GET  /v1/auth/me                 → 200
GET  /v1/products  CON token     → 200
GET  /v1/products  SIN token     → 401
GET  /v1/warehouses              → la bodega sembrada
Login por el proxy de la web     → 200
APK de Android                   → 44 MB generado
```

**584 tests** en el monorepo (`./gradlew :stock-domain:check :backend:test`).

### NO verificado

- **La cámara en un iPhone real** y **en un Android real**. El APK se genera, pero
  el escáner con cámara no se ha probado en dispositivo.
- **El proyecto Xcode** (`iosApp`): el `pbxproj` se escribió sin abrirlo.
- **Los flujos de negocio de la web**: 16 de las 17 pantallas son marcadores con
  su permiso y sus endpoints. Solo la salud está implementada de verdad, y el
  catálogo está vacío (hay que importar el Excel).

---

## Datos

El **catálogo está vacío**: los 1.398 productos del Excel no se han cargado. Se
pueden importar con `POST /v1/products/import` (acepta CSV y XLSX, con `dryRun`).

**El stock del archivo no se escribe al inventario**, y es una decisión pendiente:
el Excel trae las 10.914.130 unidades **sin ubicación**, así que cargarlas exigiría
decidir dónde van. Las opciones son recepción, arrancar en cero, o una ubicación
`SIN-UBICAR` propia. Está sin decidir a propósito en lugar de inventar una
respuesta que después habría que deshacer.

---

## IntelliJ

Abre **`/Users/axzy/DEV/STOCK`** (la raíz, donde está `settings.gradle.kts`).
IntelliJ detecta el build de Gradle y carga los 7 módulos de una vez.

Configura **Gradle user home** = `.../STOCK/.gradle-home`, porque el sandbox
bloquea `~/.gradle` y la sincronización fallaría.

**Dos excepciones que se abren aparte, no desde la raíz:**

| Carpeta | Por qué |
|---|---|
| `mobile-app/androidApp/` | Tiene su propio `settings.gradle.kts` (limitación de AGP 9) |
| `web/` | Es un proyecto de pnpm, no de Gradle |

### Documentos de referencia

| Archivo | Qué contiene |
|---|---|
| `CONTRATO_API.md` | **La fuente de verdad del contrato HTTP** |
| `PLAN_ALMACEN_INTEGRAL.md` | Arquitectura, modelo de datos y las 9 fases |
| `PLAN_ROLES_PERMISOS_POLITICAS.md` | RBAC + ABAC, los 76 permisos, 12 políticas |
| `PLAN_AUDITORIA.md` | Las seis dimensiones de auditoría |
| `PLAN_REFACTOR_MEJORAS.md` | Las 85 correcciones al legacy |

---

## Problemas comunes

**`✗ No existe infra/.env`**
```bash
cp infra/.env.example infra/.env
# y ponle secretos de verdad:
#   openssl rand -base64 32
```

**La base no responde**
```bash
cd infra && docker compose up -d && docker compose ps
```

**`Address already in use`** — ya hay un backend corriendo:
```bash
for P in $(lsof -t -nP -iTCP:8082 -sTCP:LISTEN); do kill -9 "$P"; done
```

**Los tokens dejaron de servir al reiniciar** — es esperado: el secreto se genera
nuevo. Define `STOCK_JWT_SECRET` si quieres que sobrevivan.

**El puerto 8080 está ocupado** — el harness de DSH lo usa para un túnel. Por eso
los scripts usan **8082**.
