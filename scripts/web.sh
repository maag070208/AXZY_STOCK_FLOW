#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# ARRANCA LA WEB
#
#   ./scripts/web.sh                 apunta al backend en 8082 (el del api.sh)
#   API_PORT=9000 ./scripts/web.sh   otro puerto de backend
#
# La web NO se recompila para apuntar a otro backend: el proxy de Vite lee la
# variable en runtime. Es la misma propiedad que en producción, donde nginx sirve
# `config.js` con el `API_URL` del entorno desplegado.
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ/web"

if [ ! -d node_modules ]; then
  echo "Instalando dependencias (solo la primera vez)…"
  pnpm install
fi

export VITE_DEV_API_TARGET="http://localhost:${API_PORT:-8082}"

echo "─────────────────────────────────────────────────────────────"
echo "  Web"
echo "    http://127.0.0.1:5173"
echo "    proxy /v1 -> $VITE_DEV_API_TARGET"
echo "─────────────────────────────────────────────────────────────"

exec pnpm run dev
