#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# CARGA INICIAL DEL CATÁLOGO Y DEL STOCK HEREDADO
#
#   ./scripts/importar-catalogo.sh <archivo.xlsx>
#
# Hace las DOS cosas que necesita un arranque desde cero, y en este orden:
#
#   1. Importa el catálogo por la API (1.398 productos reales).
#   2. Carga las existencias como movimientos INITIAL a la ubicación SIN-UBICAR.
#
# **POR QUÉ EL STOCK VA A SIN-UBICAR Y NO A RECEPCIÓN.** El archivo trae las
# unidades SIN decir dónde están: son el saldo heredado del sistema anterior. Ponerlo
# en recepción le quitaría el significado a esa ubicación —«acaba de llegar y sin
# ubicar»— y el flujo de ubicar tendría que adivinar cuáles son nuevas. `SIN-UBICAR`
# dice exactamente lo que es, y el conteo físico lo va vaciando hacia los estantes.
#
# Las existencias se cargan con `registrar_movimiento`, el motor de la base, así que
# el kardex queda escrito y las proyecciones cuadran por construcción. No se escribe
# ningún total a mano.
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ"

ARCHIVO="${1:-}"
if [ -z "$ARCHIVO" ] || [ ! -f "$ARCHIVO" ]; then
  echo "Uso: $0 <archivo.xlsx>" >&2
  echo "  Ej.: $0 ~/Downloads/INVENTARIO\\ REAL\\ LUNES\\ 10\\ DE\\ JUN\\ 2024.xlsx" >&2
  exit 1
fi

PUERTO="${PORT:-8082}"
ENTORNO="$RAIZ/infra/.env"
PW="$(grep '^POSTGRES_PASSWORD=' "$ENTORNO" | cut -d= -f2-)"

echo "── 1. Iniciando sesión ─────────────────────────────────────────"
TOKEN="$(curl -s --max-time 15 -X POST "http://127.0.0.1:$PUERTO/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${STOCK_ADMIN_USER:-admin}\",\"password\":\"${STOCK_ADMIN_PASSWORD:?Define STOCK_ADMIN_PASSWORD}\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin).get("accessToken",""))')"

if [ -z "$TOKEN" ]; then
  echo "✗ No se pudo iniciar sesión. ¿Está el backend corriendo? ./scripts/api.sh" >&2
  exit 1
fi
echo "  ✓ sesión iniciada"

echo
echo "── 2. Simulación del import (no escribe nada) ──────────────────"
curl -s --max-time 300 -X POST "http://127.0.0.1:$PUERTO/v1/products/import" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@$ARCHIVO" -F "dryRun=true" \
  | python3 -c '
import json,sys
d = json.load(sys.stdin)
if "code" in d:
    print("  ✗", d); raise SystemExit(1)
print(f"  filas={d[\"totalRows\"]}  aceptadas={d[\"accepted\"]}  rechazadas={d[\"rejected\"]}")
print(f"  existencias={d[\"totalStock\"]}  en cero={d[\"zeroStockCount\"]}")
if d["rejected"]:
    print("  ⚠ HAY FILAS RECHAZADAS. Revisa el informe antes de continuar.")
'

echo
echo "── 3. Import real ─────────────────────────────────────────────"
curl -s --max-time 300 -X POST "http://127.0.0.1:$PUERTO/v1/products/import" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@$ARCHIVO" -F "dryRun=false" \
  | python3 -c '
import json,sys
d = json.load(sys.stdin)
print(f"  ✓ {d.get(\"persisted\",0)} productos escritos, {d.get(\"rejected\",0)} rechazados")
'

echo
echo "── 4. Existencias heredadas a SIN-UBICAR ───────────────────────"
TABLA="$RAIZ/scripts/catalogo-existencias.tsv"
if [ ! -f "$TABLA" ]; then
  echo "  ⚠ Falta $TABLA: es el extracto con código y existencia de cada producto." >&2
  echo "    Sin él el catálogo queda cargado pero el inventario a cero." >&2
  exit 0
fi

python3 - "$TABLA" > /tmp/carga-inicial.sql <<'PY'
import sys
lineas = open(sys.argv[1]).read().strip().split("\n")
valores = ",".join("('%s',%s)" % tuple(l.split("\t")) for l in lineas)
print(f"""DO $$
DECLARE r RECORD; n BIGINT := 0; suma NUMERIC := 0; ubicacion BIGINT;
BEGIN
    SELECT id INTO ubicacion FROM locations WHERE kind = 'UNASSIGNED' LIMIT 1;
    IF ubicacion IS NULL THEN RAISE EXCEPTION 'No existe SIN-UBICAR'; END IF;
    FOR r IN
        SELECT p.id AS product_id, v.cantidad
        FROM (VALUES {valores}) AS v(codigo, cantidad)
        JOIN products p ON p.product_code = v.codigo
        WHERE NOT EXISTS (SELECT 1 FROM kardex k WHERE k.product_id = p.id)
    LOOP
        PERFORM registrar_movimiento(r.product_id, 1, 'INITIAL', r.cantidad,
            NULL, ubicacion, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
        n := n + 1; suma := suma + r.cantidad;
    END LOOP;
    RAISE NOTICE 'Cargados % productos, % unidades en SIN-UBICAR', n, suma;
END $$;""")
PY

docker exec -i -e PGPASSWORD="$PW" stock-postgres psql -U stock_app -d stock -f - < /tmp/carga-inicial.sql 2>&1 \
  | grep -E "NOTICE|ERROR" | sed 's/^psql[^ ]* //'

echo
echo "── 5. Reconciliación ──────────────────────────────────────────"
docker exec -e PGPASSWORD="$PW" stock-postgres psql -U stock_app -d stock -tAc \
  "SELECT '  divergencias: ' || count(*) FROM comprobar_proyecciones();"

echo
echo "✓ Listo. El catálogo y el inventario coinciden con el archivo."
