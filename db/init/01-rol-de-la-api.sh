#!/bin/sh
# Se ejecuta UNA vez, cuando PostgreSQL inicializa un volumen vacío.
#
# Crea el usuario con el que se conecta la API (`STOCK_API_USER`) como miembro del rol de
# grupo `stock_api`. Los permisos de ese rol los define la migración V11; aquí solo se crea
# la credencial, que no puede vivir en una migración.
set -eu

: "${STOCK_API_USER:?Falta STOCK_API_USER}"
: "${STOCK_API_PASSWORD:?Falta STOCK_API_PASSWORD}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v api_user="$STOCK_API_USER" -v api_password="$STOCK_API_PASSWORD" <<'SQL'
SELECT 'CREATE ROLE stock_api NOLOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stock_api') \gexec

-- `:"var"` y `:'var'` citan el identificador y el literal: el valor no se interpreta como SQL.
CREATE ROLE :"api_user" LOGIN PASSWORD :'api_password' IN ROLE stock_api;
SQL
