#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# ARRANCA EL ENTORNO DEL E2E DE NAVEGADOR
#
#   ./scripts/e2e-web.sh
#
# Levanta, con una base DEDICADA y sembrada por el camino de producción:
#
#   · backend en 127.0.0.1:8098   (base `stock_e2e_web`, recreada y migrada)
#   · web     en 127.0.0.1:5174   (Vite, con su proxy `/v1` → 8098)
#
# y ejecuta Playwright contra los dos.
#
# POR QUÉ UN SCRIPT Y NO `playwright test` A SECAS
#
# El e2e de navegador necesita las TRES cosas a la vez: una base con esquema y
# semilla, un backend que sirva esa base y una web apuntando a ese backend. Sin
# script, cada persona lo monta a mano y **el fallo más caro no es que falle: es
# que pase contra el backend equivocado**. Aquí el puerto y el proxy se fijan en
# un solo sitio.
#
# POR QUÉ LA BASE SE RECREA ENTERA Y NO SE LIMPIA
#
# `audit_log` es append-only y sus disparadores PROHÍBEN el `DELETE` a propósito.
# «Limpiar» no es una opción sin desactivar la protección que queremos que siga
# existiendo. Se borra la base y la recrea Flyway: además así se prueba el camino
# de un despliegue nuevo.
#
# LA CONTRASEÑA DEL ADMINISTRADOR ES CONOCIDA Y ES CORRECTO
#
# Es una base de pruebas local que se destruye en cada ejecución. No es un
# secreto: es un dato de arranque, y tenerlo fijo es lo que permite que el test
# sea reproducible. **Nunca se usa esta ruta contra producción.**
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ"

ENTORNO_INFRA="$RAIZ/infra/.env"
BASE_E2E="${STOCK_E2E_DB_NAME:-stock_e2e_web}"
PUERTO_API="${STOCK_E2E_API_PORT:-8098}"
PUERTO_WEB="${STOCK_E2E_WEB_PORT:-5174}"
CONTENEDOR="${STOCK_DB_CONTAINER:-stock-postgres}"
USUARIO="${STOCK_DB_USER:-stock_app}"

ADMIN_USUARIO="${STOCK_E2E_ADMIN_USER:-e2e-admin}"
ADMIN_PASSWORD="${STOCK_E2E_ADMIN_PASSWORD:-Contrasena-E2E-de-prueba-2026}"

log() { printf '\033[1m▸ %s\033[0m\n' "$1"; }

if [ ! -f "$ENTORNO_INFRA" ]; then
  echo "✗ No existe infra/.env. Créalo: cp infra/.env.example infra/.env" >&2
  exit 1
fi

PW="$(grep '^POSTGRES_PASSWORD=' "$ENTORNO_INFRA" | cut -d= -f2- || true)"
if [ -z "$PW" ]; then
  echo "✗ infra/.env no tiene POSTGRES_PASSWORD" >&2
  exit 1
fi

# ── Cierre ordenado: no dejar procesos ni puertos ocupados ──────────────────
PIDS=()
limpiar() {
  for pid in "${PIDS[@]:-}"; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap limpiar EXIT INT TERM

# ── 1. Base dedicada, recién creada ─────────────────────────────────────────
log "Recreando la base «${BASE_E2E}»"
docker exec "$CONTENEDOR" psql -U "$USUARIO" -d postgres -q -c \
  "DROP DATABASE IF EXISTS $BASE_E2E WITH (FORCE);" >/dev/null
docker exec "$CONTENEDOR" psql -U "$USUARIO" -d postgres -q -c \
  "CREATE DATABASE $BASE_E2E;" >/dev/null

# ── 2. Backend: migra y siembra por el camino de producción ─────────────────
log "Compilando el backend"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$RAIZ/.gradle-home}" \
  ./gradlew :backend:installDist --no-daemon --console=plain -q

# ── El puerto tiene que estar LIBRE, y comprobarlo no es paranoia ──────────
#
# **Este script arrancó un backend que falló en silencio durante varias
# ejecuciones.** Si el puerto estaba ocupado por un proceso anterior —de una
# depuración a mano, por ejemplo—, el binario nuevo NO podía escuchar, moría, y el
# `curl` de espera encontraba al VIEJO respondiendo: el script decía «backend
# listo» y los tests corrían contra un servidor con otro código. Los fallos que
# producía no señalaban a su causa.
#
# `pkill -f` no bastaba: el patrón no casaba con el proceso real. Se libera el
# puerto por su PID, que es lo que siempre funciona.
if command -v lsof >/dev/null 2>&1; then
  OCUPANTE="$(lsof -t -i :"$PUERTO_API" 2>/dev/null | head -1 || true)"
  if [ -n "$OCUPANTE" ]; then
    log "El puerto $PUERTO_API estaba ocupado por el PID $OCUPANTE: se libera"
    kill -9 "$OCUPANTE" 2>/dev/null || true
    sleep 2
  fi
fi

if curl -fsS -m 2 "http://127.0.0.1:$PUERTO_API/v1/health" >/dev/null 2>&1; then
  echo "✗ El puerto $PUERTO_API sigue ocupado y no se pudo liberar." >&2
  echo "  Un backend viejo respondería en lugar del que este script arranca." >&2
  exit 1
fi

log "Arrancando el backend en $PUERTO_API (migra y siembra)"
STOCK_DB_HOST=127.0.0.1 \
STOCK_DB_PORT=5432 \
STOCK_DB_NAME="$BASE_E2E" \
STOCK_DB_USER="$USUARIO" \
STOCK_DB_PASSWORD="$PW" \
STOCK_DB_RUN_MIGRATIONS=true \
STOCK_SEED=true \
STOCK_ADMIN_USER="$ADMIN_USUARIO" \
STOCK_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
STOCK_JWT_SECRET="secreto-e2e-de-navegador-con-longitud-suficiente-para-hmac" \
STOCK_PORT="$PUERTO_API" \
  backend/build/install/backend/bin/backend >/tmp/e2e-backend.log 2>&1 &
PIDS+=("$!")

# ── Espera activa, y por una señal que NO miente ────────────────────────────
#
# **Dos intentos anteriores de esto estuvieron mal, y conviene saber por qué.**
#
#  1. Esperar a que el PUERTO conteste no basta: el servidor abre el socket
#     antes de migrar, así que el script daba el arranque por bueno mientras
#     Flyway seguía trabajando. Los tests pasaban por suerte de tiempos.
#  2. Esperar a que el health informe `schemaVersion` tampoco vale: el health
#     responde `UP` con la versión que lleve EN ESE MOMENTO, así que la vio en
#     `"2"` y el script siguió con la base a medio migrar. **Un indicador que
#     responde «listo» antes de estarlo no es un indicador: es una carrera.**
#
# La señal que sí significa «puedo servir» es que el ADMINISTRADOR SIEMBRA DO
# pueda iniciar sesión: eso exige migraciones aplicadas, roles, permisos, matriz
# y usuario creados. Se pregunta por la misma puerta que usará el test.
log "Esperando al backend (con el esquema migrado y el administrador sembrado)"
LISTO=""
for _ in $(seq 1 90); do
  if curl -fsS -m 3 -X POST "http://127.0.0.1:$PUERTO_API/v1/auth/login" \
       -H 'Content-Type: application/json' \
       -d "{\"username\":\"$ADMIN_USUARIO\",\"password\":\"$ADMIN_PASSWORD\"}" >/dev/null 2>&1; then
    LISTO="$(curl -fsS -m 2 "http://127.0.0.1:$PUERTO_API/v1/health" 2>/dev/null || echo '(health no disponible)')"
    break
  fi
  sleep 1
done

# Si el proceso ya no existe, esperar más es inútil y el mensaje tiene que decir
# POR QUÉ, no solo que no llegó.
if ! kill -0 "${PIDS[0]}" 2>/dev/null; then
  echo "✗ El backend MURIÓ al arrancar. Últimas líneas de /tmp/e2e-backend.log:" >&2
  tail -25 /tmp/e2e-backend.log >&2
  exit 1
fi

if [ -z "$LISTO" ]; then
  echo "✗ El backend no llegó a admitir el inicio de sesión del administrador." >&2
  echo "  Últimas líneas de /tmp/e2e-backend.log:" >&2
  tail -25 /tmp/e2e-backend.log >&2
  exit 1
fi

log "Backend listo: $LISTO"

# ── 3. La web, apuntando a ESE backend por el proxy de Vite ──────────────────
log "Arrancando la web en $PUERTO_WEB → $PUERTO_API"
(
  cd web
  VITE_DEV_API_TARGET="http://127.0.0.1:$PUERTO_API" \
  SWC_NATIVE_BINDING_CACHE="$PWD/.cache/swc" \
  PLAYWRIGHT_BROWSERS_PATH="$PWD/.browsers" \
    pnpm exec vite --port "$PUERTO_WEB" --strictPort --host 127.0.0.1
) >/tmp/e2e-web.log 2>&1 &
PIDS+=("$!")

log "Esperando a la web"
for _ in $(seq 1 60); do
  if curl -fsS -m 2 "http://127.0.0.1:$PUERTO_WEB/" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! curl -fsS -m 2 "http://127.0.0.1:$PUERTO_WEB/" >/dev/null 2>&1; then
  echo "✗ La web no respondió. Últimas líneas de /tmp/e2e-web.log:" >&2
  tail -25 /tmp/e2e-web.log >&2
  exit 1
fi

log "Web arriba. Ejecutando Playwright"
cd web
PLAYWRIGHT_BROWSERS_PATH="$PWD/.browsers" \
E2E_BASE_URL="http://127.0.0.1:$PUERTO_WEB" \
E2E_API_URL="http://127.0.0.1:$PUERTO_API" \
E2E_USERNAME="$ADMIN_USUARIO" \
E2E_PASSWORD="$ADMIN_PASSWORD" \
E2E_DB_NAME="$BASE_E2E" \
E2E_DB_USER="$USUARIO" \
E2E_DB_CONTAINER="$CONTENEDOR" \
  pnpm exec playwright test "$@"
