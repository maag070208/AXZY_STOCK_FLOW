#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# ARRANCA EL BACKEND
#
#   ./scripts/api.sh              arranca en el puerto 8082
#   STOCK_SEED=true ./scripts/api.sh   además siembra roles, permisos y admin
#   PORT=9000 ./scripts/api.sh    otro puerto
#
# POR QUÉ UN SCRIPT Y NO DOCUMENTACIÓN CON SIETE VARIABLES
# Porque el backend exige siete variables de entorno y **falla si falta
# cualquiera**, que es lo correcto: un despliegue con credenciales por defecto es
# un despliegue vulnerable que nadie nota. Pero eso hace que arrancarlo a mano sea
# tedioso y fácil de equivocar. El script deriva lo que puede del `infra/.env` que
# ya existe y documento el resto.
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ"

PUERTO="${PORT:-8082}"
ENTORNO_INFRA="$RAIZ/infra/.env"

# ── La contraseña de PostgreSQL sale del .env de la infraestructura ─────────
# El archivo está ignorado por git, así que no hay secreto en el repositorio: el
# script lee la misma fuente que el contenedor.
if [ ! -f "$ENTORNO_INFRA" ]; then
  echo "✗ No existe infra/.env" >&2
  echo "  Créalo copiando la plantilla:" >&2
  echo "    cp infra/.env.example infra/.env" >&2
  exit 1
fi

PW="$(grep '^POSTGRES_PASSWORD=' "$ENTORNO_INFRA" | cut -d= -f2- || true)"
if [ -z "$PW" ]; then
  echo "✗ infra/.env no tiene POSTGRES_PASSWORD" >&2
  exit 1
fi

# ── El secreto de firma de JWT ──────────────────────────────────────────────
# **Se lee de `infra/.env` para que los tokens SOBREVIVAN a un reinicio.**
#
# La primera versión generaba uno nuevo en cada arranque. Es lo más seguro, pero
# en la práctica significaba que **cada reinicio del backend cerraba la sesión de
# todo el mundo**, y el usuario lo veía como un error de autenticación —
# `{"code":"UNAUTHENTICATED"}`— sin entender por qué. Lo era, pero provocado por
# nosotros.
#
# Con el secreto en el archivo, reiniciar el backend (para aplicar migraciones,
# por ejemplo) no cierra ninguna sesión. El archivo está ignorado por git.
SECRETO_ARCHIVO="$(grep '^STOCK_JWT_SECRET=' "$ENTORNO_INFRA" 2>/dev/null | cut -d= -f2- || true)"

if [ -n "${STOCK_JWT_SECRET:-}" ]; then
  SECRETO="$STOCK_JWT_SECRET"
elif [ -n "$SECRETO_ARCHIVO" ]; then
  SECRETO="$SECRETO_ARCHIVO"
else
  # Si no hay ninguno, se genera y SE AVISA: el usuario tiene que saber que sus
  # sesiones no van a sobrevivir al siguiente reinicio.
  SECRETO="$(openssl rand -base64 48 | tr -d '\n')"
  echo "⚠  No hay STOCK_JWT_SECRET en infra/.env: se generó uno temporal."
  echo "   Los tokens NO sobrevivirán al siguiente reinicio."
  echo "   Para fijarlo:  echo \"STOCK_JWT_SECRET=$SECRETO\" >> infra/.env"
  echo
fi

export STOCK_DB_HOST="${STOCK_DB_HOST:-127.0.0.1}"
export STOCK_DB_PORT="${STOCK_DB_PORT:-5432}"
export STOCK_DB_NAME="${STOCK_DB_NAME:-stock}"
export STOCK_DB_USER="${STOCK_DB_USER:-stock_app}"
export STOCK_DB_PASSWORD="$PW"
export STOCK_JWT_SECRET="$SECRETO"
export STOCK_PORT="$PUERTO"

# Las migraciones se aplican al arrancar solo si se pide. En un despliegue real se
# aplican como paso aparte: un arranque que migra solo puede sorprender.
export STOCK_DB_RUN_MIGRATIONS="${STOCK_DB_RUN_MIGRATIONS:-false}"

echo "─────────────────────────────────────────────────────────────"
echo "  Backend"
echo "    puerto:      $PUERTO"
echo "    base:        $STOCK_DB_NAME en $STOCK_DB_HOST:$STOCK_DB_PORT"
echo "    migrar:      $STOCK_DB_RUN_MIGRATIONS"
echo "    sembrar:     ${STOCK_SEED:-false}"
echo "    health:      http://127.0.0.1:$PUERTO/v1/health"
echo "─────────────────────────────────────────────────────────────"

# ── Se comprueba la base ANTES de arrancar ──────────────────────────────────
# Un backend que arranca contra una base que no responde da errores confusos en la
# primera petición del usuario. Mejor fallar aquí, con el motivo claro.
if ! docker exec stock-postgres pg_isready -U "$STOCK_DB_USER" -d "$STOCK_DB_NAME" -q 2>/dev/null; then
  echo
  echo "⚠  La base de datos no responde. Levántala con:" >&2
  echo "     cd infra && docker compose up -d" >&2
  echo >&2
fi

# ── Compilar y arrancar ─────────────────────────────────────────────────────
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$RAIZ/.gradle-home}"

echo "Compilando…"
./gradlew :backend:installDist --no-daemon --console=plain -q

echo "Arrancando…"
exec backend/build/install/backend/bin/backend
