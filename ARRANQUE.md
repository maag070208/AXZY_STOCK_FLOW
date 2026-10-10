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

## La web

Hace falta Node 20 o superior y `pnpm`. La API tiene que estar levantada.

```bash
cd web
pnpm install
pnpm dev            # http://127.0.0.1:5173, con proxy a la API
```

El navegador habla **siempre con rutas relativas** (`/v1/...`): en desarrollo las proxya Vite y en
producción lo hace nginx. Así no hay CORS ni una URL que se rompa al cambiar de entorno. Si la API
no está en el puerto por defecto: `STOCK_API_URL=http://127.0.0.1:9090 pnpm dev`.

| Comando | Qué hace |
|---|---|
| `pnpm dev` | Servidor de desarrollo con proxy a la API |
| `pnpm typecheck` | `tsc --noEmit` |
| `pnpm lint` | `eslint . --max-warnings=0`: impone las capas FSD y los envoltorios del UI system |
| `pnpm build` | `tsc --noEmit && vite build` |
| `pnpm test:e2e` | Playwright (9 pruebas). Necesita la API levantada |

**La primera vez te obliga a cambiar la contraseña**, y es el comportamiento documentado (I8):
mientras no la cambies, la API responde `PASSWORD_CHANGE_REQUIRED` a todo lo demás y la web solo
enseña esa pantalla. Después aparece el menú con Productos, Ubicaciones y Bodegas.

**La caché del binario nativo de SWC vive dentro del proyecto.** Los scripts la fijan con
`SWC_NATIVE_BINDING_CACHE="$PWD/.cache/swc"`; sin eso, `@swc/core` intenta escribir un cerrojo en
`~/Library/Caches/swc-native-*` y el build falla con «Failed to load native binding», un error que no
dice nada de la causa.

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

- **622 pruebas, 0 fallos, 0 omitidas**: 407 del dominio, 11 de la capa de aplicación, 72 de
  persistencia, 13 de la capa web, 11 de criptografía y 108 de flujo completo por HTTP.
- La imagen de la API se construye y arranca en Compose; `/v1/health` responde `UP` con el esquema en V12.
- Login, sesión actual, renovación con detección de reuso, cierre de sesión y cambio de contraseña.
- El usuario de la API no puede crear tablas ni modificar el kardex o la bitácora.
- **Catálogo (fase 4)**: productos con alta, edición y baja lógica; ubicaciones con la de recepción
  protegida; bodegas con una sola predeterminada; e importación estricta e idempotente del catálogo
  (K1–K6). Cada ruta declara su permiso y la API no arranca si alguna no lo hace (I5).
- **Existencias (fase 5, M02), cerrada**: ajustes con motivo (el permiso depende del motivo), ubicar
  y mover; existencias por ubicación y kardex; y los reportes de existencias con totales, resumen de
  movimientos por día/semana/mes/producto/ubicación, reconciliación `kardex-vs-projection` y
  exportación del kardex a CSV y XLSX. Todo pasa por el motor SQL: una guardia de código impide que
  un adaptador escriba el inventario a mano. Las fechas de los reportes son **días operativos** en la
  zona del negocio, no días UTC.
- **Recepción (fase 6, M03)**: facturas con líneas, captura física con su diferencia calculada por la
  base, aprobación **por factura** (una sola acción, y quien capturó no puede aprobar), rechazo que
  devuelve la línea a conteo, cierre que respeta el criterio del dominio y bandeja de pendientes.
- **Web (fase 11, empezada)**: entrada y cambio de contraseña obligatorio contra la API real;
  menú lateral con las entradas que permiten los permisos de la sesión; y las tres pantallas del
  catálogo (productos con alta/edición/baja e importación con simulación, ubicaciones y bodegas),
  con la marca del paquete de iconos (favicon, manifiesto, glifo del armazón) y el loader de Lottie
  mientras la importación trabaja. `tsc`, `eslint` (con las guardias de capas y de envoltorios
  **probadas fallando**) y `vite build` en verde, y 11 pruebas de navegador: 5 contra la API real y
  6 con la API simulada.

```bash
# El catálogo, de punta a punta (necesita una sesión con permisos de catálogo)
curl -s -X POST http://127.0.0.1:8081/v1/products/import   -H "Authorization: Bearer $TOKEN" -F "file=@catalogo.csv" -F "dryRun=true"
```

### No construido todavía

- **Dentro de recepción**: que ubicar conozca la línea de factura (regla B1 completa). Hoy
  `POST /v1/inventory/locate` mueve el saldo sin saber de qué línea viene, así que `LOCATED` no se
  alcanza por ninguna ruta.
- Los módulos de negocio que siguen: surtido, traslados y etiquetas.
- **Dentro del catálogo**: la lectura de XLSX nativo (llega con la migración de datos, M10; hoy la
  importación acepta CSV y TSV).
- **Dentro de la web**: las pantallas de los módulos que aún no tienen endpoint (existencias,
  recepción, surtido, traslados y etiquetas) no se dibujan; el inicio dice cuáles faltan. Tampoco
  está el despliegue de la web (Dockerfile con nginx y su servicio en Compose). La marca sí: el
  paquete de iconos está versionado y conectado (favicon, manifiesto y el glifo del armazón).
- La app (Kotlin Multiplatform).

El orden y el criterio de terminado de cada uno están en
[docs/plan/PLAN-IMPLEMENTACION.md](docs/plan/PLAN-IMPLEMENTACION.md).
