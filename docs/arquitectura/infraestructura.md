# Infraestructura: Docker Compose, PostgreSQL y migraciones
> **Normativo.** Cómo se levanta el entorno y cómo se aplican las migraciones. Decisión en [ADR-0016](../adr/0016-docker-compose-para-el-entorno.md). Cada afirmación cita su archivo. Última revisión: 2026-10-10.

## 1. Servicios

Definidos en `docker-compose.yml` (raíz), proyecto de Compose **`stockflow`**.

| Servicio | Imagen | Qué hace | Depende de |
|---|---|---|---|
| `postgres` | `postgres:16` | La base de datos `stock` | — |
| `migrate` | `flyway/flyway:12.4.0` | Aplica las migraciones pendientes y **termina** | `postgres` sana |
| `api` | Construida con `api/Dockerfile` (JRE 25) | La API REST | `migrate` terminó con éxito |
| `web` | *Fase 11* | Sirve la web y reenvía `/api` a `api` | `api` |

**El proyecto no se llama `stock`.** Ese nombre lo usa el Compose del sistema anterior
(`stock-postgres`, `stock-adminer`), y dos proyectos con el mismo nombre se pisan los contenedores:
un `up` de uno elimina y recrea los del otro. Ya pasó una vez; los datos sobrevivieron porque estaban
en un volumen con nombre.

## 2. Red y puertos

| Servicio | Puerto en el host | Variable |
|---|---|---|
| `postgres` | `127.0.0.1:5434` | `POSTGRES_PORT` |
| `api` | `127.0.0.1:8081` | `API_PORT` |

Los dos se publican **solo en loopback**. La web llegará a la API por la red interna de Compose.

La API toma la dirección del cliente de la cabecera del proxy solo si la petición viene de una red
interna (`server.forward-headers-strategy: native`, `api/app/src/main/resources/application.yml`).
El código nunca lee `X-Forwarded-For` por su cuenta: cualquiera puede enviarla.

## 3. Roles de base de datos y variables

**Dos roles, y la API no usa el que puede cambiar el esquema.**

| Rol | Quién lo usa | Qué puede hacer | Dónde se crea |
|---|---|---|---|
| `DB_OWNER_USER` (`stock_owner`) | Solo `migrate` | Todo: es el dueño del esquema | `POSTGRES_USER` del contenedor |
| `STOCK_API_USER` (`stock_app`) | La API | Lo que concede el rol de grupo `stock_api` | `db/init/01-rol-de-la-api.sh` |

El rol de grupo `stock_api` lo define la migración `V11`. Reúne lo que la API necesita y nada más:
no puede crear, alterar ni borrar tablas, ni hacer `UPDATE` o `DELETE` sobre `kardex` y `audit_log`,
ni escribir en el historial de migraciones. Los permisos se versionan en la migración; las
credenciales nunca entran en una. Lo comprueba
`api/adapters-persistence/src/test/.../ApiRolePrivilegesTest.kt`.

Variables de `.env` (no versionado; la plantilla es `.env.example`):

| Variable | Servicio | Obligatoria | Descripción |
|---|---|---|---|
| `POSTGRES_DB` | todos | Sí | Nombre de la base |
| `POSTGRES_PORT`, `API_PORT` | `postgres`, `api` | Sí | Puertos del host |
| `DB_OWNER_USER`, `DB_OWNER_PASSWORD` | `postgres`, `migrate` | Sí | El dueño del esquema |
| `STOCK_API_USER`, `STOCK_API_PASSWORD` | `postgres`, `api` | Sí | El usuario de la API |
| `STOCK_JWT_SECRET` | `api` | Sí | Firma de los tokens; al menos 32 bytes. Conservarlo entre reinicios |
| `ADMIN_BOOTSTRAP_PASSWORD` | `api` | Solo sin usuarios | Contraseña del primer administrador; se le exige cambiarla |
| `STOCK_BUSINESS_ZONE` | `api` | No | Zona horaria de las políticas de horario. Por defecto `America/Mexico_City` |
| `STOCK_VERSION` | `api` | No | La versión que informa `/v1/health` |

**Ningún secreto tiene valor por defecto.** En Compose, `${VAR:?mensaje}` hace fallar el comando si
falta. En la API, una propiedad sin valor por defecto en `application.yml` impide el arranque y el
error nombra la variable.

## 4. Migraciones

```
db/
  migrations/   V1__… a V12__…   Las migraciones versionadas
  callbacks/    afterMigrate.sql  Se ejecuta después de cada `migrate`
  init/         01-rol-de-la-api.sh  Crea el usuario de la API al inicializar el volumen
```

- **Inmutables una vez aplicadas.** Flyway valida la suma de cada archivo; si alguien edita una
  migración aplicada, `migrate` falla. Una corrección es una migración nueva.
- `baselineOnMigrate` **desactivado**: una base sin historial no se da por migrada.
- **La API no migra: comprueba.** Al arrancar lee `flyway_schema_history` y se niega a seguir si la
  base está por detrás de `REQUIRED_SCHEMA_VERSION` (`api/app/.../config/SchemaGuard.kt`). Una prueba
  comprueba que esa constante coincide con el último archivo de `db/migrations`
  (`SchemaVersionTest.kt`).
- `SqlMigrationGuardTest` (`stock-domain/src/jvmTest`) exige que las versiones sean correlativas y
  que no se pierdan las restricciones de la bitácora.

### Qué añaden V10, V11 y V12

| Migración | Qué corrige |
|---|---|
| `V10` | El total por producto se recalcula con el producto bloqueado. Sin eso, dos movimientos simultáneos perdían una escritura (205 en lugar de 215) |
| `V11` | El rol `stock_api`; las particiones de la bitácora (función con `SECURITY DEFINER`); el índice de la cadena de hash |
| `V12` | `audit_log_record` acepta la traza de la decisión de acceso. Sin eso, un registro `CRITICAL` no se podía guardar |

**El índice de V11, medido.** Cada escritura de la bitácora busca el eslabón anterior de la cadena
(`ORDER BY sequence_no DESC LIMIT 1`). Sin índice, eso leía todas las filas de todas las particiones
en cada escritura auditada, bajo un cerrojo global. Con 20.000 registros en la base de desarrollo:

| | Filas leídas | Tiempo |
|---|---|---|
| Sin el índice (V1–V10) | 20.000 | 1,99 ms, y crece con cada registro |
| Con el índice (V11) | 1 | 0,02 ms |

### Particiones de la bitácora

`audit_log` está particionada por mes. `afterMigrate.sql` asegura la del mes en curso y las 18
siguientes en cada despliegue. Si aun así faltara una, la fila cae en `audit_log_default` en lugar de
fallar.

### Comprobación

```sql
SELECT version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
-- Doce filas, todas con success = true.
```

## 5. La imagen de la API

`api/Dockerfile`, en dos etapas: la primera compila con el JDK 25; la segunda lleva solo el JRE y el
jar. El proceso corre con un usuario sin privilegios. Las pruebas no se ejecutan dentro del build de
la imagen: necesitan Docker y corren antes, en CI.

El contexto de build es la raíz del repositorio, filtrado por `.dockerignore` para que solo entren el
código de la API y del dominio.

## 6. Arranque y verificación

```bash
cp .env.example .env          # una sola vez; completar los valores vacíos
docker compose up -d --build
docker compose ps -a          # migrate: «exited (0)»; postgres y api: «healthy»
curl -s http://127.0.0.1:8081/v1/health
```

Para compilar y probar sin Docker Compose solo hace falta un JDK 17 o superior: Gradle descarga el
JDK 25 de la API (`settings.gradle.kts`, plugin de toolchains). Las pruebas de integración sí
necesitan Docker, para Testcontainers.

```bash
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew test
```

## 7. Respaldos

Respaldo lógico con `pg_dump -Fc` antes de migrar un entorno con datos
([operacion/respaldos.md](../operacion/respaldos.md)). El volumen `stockflow_pgdata` es persistente;
`docker compose down -v` lo borra.

## 8. Lo que no cubre este documento

- Despliegue a producción, dominio y certificados: se decide aparte.
- El servicio `web`: llega en la fase 11.
- Impresoras y escáneres: no forman parte del entorno de Compose.
