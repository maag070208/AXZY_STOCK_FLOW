# Cómo arrancar el proyecto

**Un comando.** Levanta PostgreSQL, aplica las migraciones y arranca la API.

```bash
cp .env.example .env      # una sola vez; completa los valores vacíos (ver abajo)
docker compose up -d --build
```

```bash
curl -s http://127.0.0.1:8081/v1/health
# {"status":"UP","schemaVersion":"12","version":"dev","database":{"status":"UP",...}}
```

El detalle de cada servicio, puerto y variable está en
[docs/arquitectura/infraestructura.md](docs/arquitectura/infraestructura.md).

---

## Qué hay que completar en `.env`

| Variable | Cómo generarla |
|---|---|
| `DB_OWNER_PASSWORD` | `openssl rand -hex 24` |
| `STOCK_API_PASSWORD` | `openssl rand -hex 24` |
| `STOCK_JWT_SECRET` | `openssl rand -base64 48` |
| `ADMIN_BOOTSTRAP_PASSWORD` | La que quieras para el primer administrador: mínimo 12 caracteres y que no sea común |

**Ningún secreto tiene valor por defecto.** Si falta uno, `docker compose` o la API se detienen y
dicen cuál. Un entorno que arranca con credenciales conocidas es un entorno vulnerable que nadie nota.

`.env` no se versiona.

## El primer administrador

La primera vez que la API arranca contra una base sin usuarios, crea al usuario `admin` con la
contraseña de `ADMIN_BOOTSTRAP_PASSWORD` y **le exige cambiarla al entrar**. Mientras no la cambie,
solo puede cambiarla, ver quién es y cerrar sesión.

Después de crearlo, `ADMIN_BOOTSTRAP_PASSWORD` se puede dejar vacía: ya no se usa.

## Lo que se levanta

| Servicio | Dónde | Qué es |
|---|---|---|
| `postgres` | `127.0.0.1:5434` | PostgreSQL 16, base `stock` |
| `migrate` | — | Aplica `db/migrations` y termina con código 0 |
| `api` | `127.0.0.1:8081` | La API (Kotlin + Spring Boot) |

```bash
docker compose ps -a      # migrate debe estar «exited (0)»; postgres y api, «healthy»
docker compose logs api   # el arranque dice cuántos permisos y roles sincronizó
```

**La API no migra: comprueba.** Si la base está por detrás de la migración que el código necesita, la
API no arranca y lo dice. Las migraciones las aplica solo el servicio `migrate`.

## Compilar y probar sin Compose

Hace falta un JDK 17 o superior para arrancar Gradle; el JDK 25 de la API lo descarga Gradle. Las
pruebas de integración usan Testcontainers, así que Docker tiene que estar en marcha.

```bash
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew test
```

El recuento real está en los XML de `**/build/test-results/`, no en «BUILD SUCCESSFUL».

**Las pruebas que necesitan PostgreSQL fallan si no hay Docker; no se saltan.** Una prueba saltada
cuenta como aprobada y un «0 fallos» sin base querría decir «no se probó nada».

Cuatro pruebas del dominio (`CatalogImporterTest`) leen el extracto real del catálogo,
`stock-domain/src/jvmTest/resources/catalogo_real.csv`. Ese archivo **no se versiona** (son datos de
la empresa, ver `.gitignore`): en un clon nuevo hay que colocarlo ahí o esas cuatro pruebas fallan
diciendo que falta.

## Convivencia con el sistema anterior

Si en la máquina sigue corriendo el Compose anterior (`stock-postgres` en el puerto 5432,
`stock-adminer` en el 8080), no hay conflicto: este proyecto se llama `stockflow` y usa otros puertos.
No se le puso `stock` precisamente porque dos proyectos de Compose con el mismo nombre se pisan los
contenedores.

## Qué está verificado y qué no

### Verificado ejecutándolo (2026-10-10)

- **474 pruebas, 0 fallos, 0 omitidas**: 392 del dominio, 36 de persistencia, 11 de criptografía y 35
  de flujo completo por HTTP.
- La imagen de la API se construye y arranca en Compose; `/v1/health` responde `UP` con el esquema en V12.
- Login, sesión actual, renovación con detección de reuso, cierre de sesión y cambio de contraseña.
- El usuario de la API no puede crear tablas ni modificar el kardex o la bitácora.

### No construido todavía

- Los módulos de negocio (catálogo, existencias, recepción, surtido, traslados, etiquetas).
- La app (Kotlin Multiplatform) y la web (React + AXZY UI System).

El orden y el criterio de terminado de cada uno están en
[docs/plan/PLAN-IMPLEMENTACION.md](docs/plan/PLAN-IMPLEMENTACION.md).
