# Infraestructura: Docker Compose, PostgreSQL y migraciones
> **Normativo.** Cómo se levanta el entorno y cómo se aplican las migraciones. Decisión en [ADR-0016](../adr/0016-docker-compose-para-el-entorno.md). Última revisión: 2026-10-09.

## 1. Servicios

| Servicio | Imagen | Qué hace | Depende de |
|---|---|---|---|
| `postgres` | `postgres:16` (versión menor fijada en `stack.md`) | Base de datos `stock` | — |
| `migrate` | Imagen de la API con el comando de Flyway | Aplica las migraciones pendientes y **termina** | `postgres` sana |
| `api` | Imagen construida desde `app` (JRE LTS) | API REST | `migrate` terminó bien |
| `web` | Imagen de nginx con el build de React | Sirve la web y reenvía `/api` a `api` | `api` |
| `adminer` *(solo desarrollo)* | `adminer` | Consola de la base, en `localhost` | `postgres` |

```yaml
# Esquema ilustrativo: docker-compose.yml de la raíz. Las versiones se fijan en stack.md.
services:
  postgres:
    image: postgres:16
    healthcheck: { test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB"] }
  migrate:
    depends_on: { postgres: { condition: service_healthy } }
  api:
    depends_on: { migrate: { condition: service_completed_successfully } }
  web:
    depends_on: [api]
```

## 2. Red y puertos

- Solo `web` (`8080` en el host) y, en desarrollo, `adminer` y `postgres` (`127.0.0.1`) publican puertos.
- `api` no publica puerto en el host: solo lo alcanza `web` por la red interna. En desarrollo se
  puede publicar en `127.0.0.1` para pruebas directas.
- `postgres` nunca se publica a todas las interfaces.

## 3. Variables de entorno

Los valores van en `.env` (no versionado). `.env.example` tiene los nombres y una descripción.
**Ninguna variable de secreto tiene valor por defecto.** Si falta una obligatoria, el servicio
termina con un mensaje que nombra la variable.

| Variable | Servicio | Obligatoria | Descripción |
|---|---|---|---|
| `POSTGRES_DB` | `postgres`, `migrate`, `api` | Sí | Nombre de la base |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `postgres`, `migrate`, `api` | Sí | Credenciales del rol de la aplicación |
| `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD` | `migrate` | Sí | Rol con permiso de DDL; la API **no** lo usa |
| `JWT_SECRET` | `api` | Sí | Secreto de firma de los tokens; se conserva entre reinicios |
| `JWT_ACCESS_TTL` / `JWT_REFRESH_TTL` | `api` | Sí | Vida de los tokens |
| `SERVICE_API_KEY_PEPPER` | `api` | Sí | Secreto para guardar las claves de servicio con hash |
| `ADMIN_BOOTSTRAP_PASSWORD` | `api` | Solo en el primer arranque | Contraseña inicial del administrador; se fuerza el cambio |

**Dos roles de base de datos.** `migrate` usa un rol con DDL. `api` usa un rol con `SELECT`,
`INSERT`, `UPDATE` y `EXECUTE` sobre las funciones del motor, **sin** `DROP` ni `ALTER`. Un
defecto en la API no puede cambiar el esquema.

## 4. Migraciones

- Las migraciones son archivos `V<n>__<descripcion>.sql` en `app/src/main/resources/db/migration`.
- **Inmutables una vez aplicadas.** Flyway valida la suma de cada archivo (`validateOnMigrate`):
  si alguien edita una migración aplicada, el arranque falla.
- `baselineOnMigrate` **desactivado**. Una base sin historial no se marca como migrada: falla,
  para que nadie suponga un estado que no se verificó.
- El servicio `migrate` ejecuta `flyway migrate` y termina con código 0 solo si todas las
  migraciones quedan aplicadas.
- La API **no** migra al arrancar. Su arranque comprueba que `flyway_schema_history` tenga la
  última versión esperada y falla si no la tiene.

### Comprobación

```sql
SELECT version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
-- Todas las filas con success = true, sin huecos en las versiones.
```

## 5. Respaldos

- Respaldo lógico con `pg_dump -Fc` antes de cualquier migración en un entorno que tenga datos.
  El procedimiento está en [operacion/respaldos.md](../operacion/respaldos.md).
- El volumen de datos de `postgres` es persistente; `docker compose down -v` lo borra y debe
  pedir confirmación en los scripts.

## 6. Arranque y verificación

```bash
cp .env.example .env          # una sola vez; completar los valores
docker compose up -d --build
docker compose ps             # migrate debe aparecer como "exited (0)"; api y web, "healthy"
curl -s http://localhost:8080/api/v1/health
```

El criterio de salida de la fase 1 de [plan/PLAN-REHACER.md](../plan/PLAN-REHACER.md) es que la
consulta de §4 devuelva todas las versiones sin error.

## 7. Lo que no cubre este documento

- Despliegue a producción, dominio, certificados y balanceo: fuera de alcance; se decide aparte.
- Impresoras y escáneres: los consume la app y el servicio de etiquetas, no forman parte del
  entorno de Compose.
