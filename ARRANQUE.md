# Cómo arrancar el proyecto

**Un comando.** Levanta PostgreSQL, aplica las migraciones y arranca la API.

```bash
cp .env.example .env      # una sola vez; completa los valores vacíos (ver abajo)
docker compose up -d --build
```

```bash
curl -s http://127.0.0.1:8081/v1/health
# {"status":"UP","schemaVersion":"15","version":"dev","database":{"status":"UP",...}}
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
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew verify
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
| `pnpm test:e2e` | Playwright (30 pruebas). La entrada necesita la API levantada; el resto usa la API simulada |

**La primera vez te obliga a cambiar la contraseña**, y es el comportamiento documentado (I8):
mientras no la cambies, la API responde `PASSWORD_CHANGE_REQUIRED` a todo lo demás y la web solo
enseña esa pantalla. Después aparece el menú con Productos, Ubicaciones, Bodegas y los módulos de lectura (Existencias, Kardex, Recepción, Surtido, Traslados, Etiquetas y Auditoría), cada uno según sus permisos.

**Las pruebas de navegador usan `E2E_ADMIN_PASSWORD`**, no `ADMIN_BOOTSTRAP_PASSWORD`. La de arranque solo crea el primer administrador y la API obliga a cambiarla: una prueba que la usara como contraseña vigente se rompería en cuanto alguien la cambiara. Va en `.env` (ignorado por git); la plantilla lo documenta.

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

- **752 pruebas, 0 fallos, 0 omitidas**: 412 del dominio, 11 de la capa de aplicación, 91 de
  persistencia, 17 de la capa web, 11 de criptografía y 210 de flujo completo por HTTP, medidas con
  `./gradlew verify` el 2026-10-10. Esquema en **V15**.
  **`./gradlew test` no basta**: no ejecuta las pruebas del dominio (su tarea se llama `jvmTest`),
  así que el informe puede parecer verde leyendo XML de una ejecución anterior. Por eso el comando
  de verificación es `verify`.
- La imagen de la API se construye y arranca en Compose; `/v1/health` responde `UP` con el esquema en V15.
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
- **Recepción (fase 6, M03), cerrada**: facturas con líneas, captura física con su diferencia
  calculada por la base, aprobación **por factura** (una sola acción, y quien capturó no puede
  aprobar), rechazo que devuelve la línea a conteo, cierre que respeta el criterio del dominio,
  bandeja de pendientes y la **ubicación que conoce la línea de factura**: ubicar aplica la regla B1
  antes de mover nada y deja la línea `LOCATED`.
- **Surtido y ERP (fase 7, M04 + M09), cerrada**: órdenes con líneas, empaque que descuenta lo empacado,
  confirmación que descuenta **solo lo que falta** y se reclama con una sentencia condicional (dos
  confirmaciones simultáneas no descuentan dos veces), faltantes por la regla única de ajuste y
  exportación al ERP con contador de intentos y motivo del fallo.
- **Traslados (fase 8, M05), cerrada**: creación, despacho, recepción, cancelación y el informe de
  mercancía en tránsito con sus días contados por el servidor. Las funciones del motor SQL
  (`despachar_traslado`, `recibir_traslado`, `cancelar_traslado`, migración V13) mueven el saldo, dejan
  las dos mitades en el kardex y conservan la invariante: **la mercancía en el camión sigue contando**.
- **Etiquetas (fase 9, M06), cerrada**: plantillas ZPL con validación al guardar, previsualización que
  resuelve con la misma función que imprime, encolado que resuelve antes de encolar, cola por estado,
  reimpresión enlazada al original y **la confirmación de la impresora** con credencial de servicio
  —la única ruta sin usuario y la única que marca una etiqueta como impresa—.
- **Auditoría (fase 9, M08), cerrada**: `GET /v1/audit/log` con filtros por entidad, actor, acción,
  nivel, resultado, canal y rango de fechas de negocio, paginado con el total del mismo filtro. La
  bitácora es inmutable por disparadores y **su propia consulta se registra como acceso**.
- **Usuarios y roles (M07), cerrada**: alta, consulta, edición, asignación de roles y restablecimiento
  de contraseña —que invalida las sesiones abiertas—, y **roles con su matriz de permisos**: crear,
  editar, activar/desactivar y conceder. Un rol nuevo concede exactamente lo que dice su matriz, y
  quitarlo o desactivarlo se nota de inmediato.
- **Migración (M10)**, en lo que es código: la importación del catálogo es estricta e idempotente,
  acepta **CSV, TSV y XLSX** por el mismo lector, y carga el stock heredado a `SIN-UBICAR` con un
  movimiento `INITIAL` por producto —solo a quien no tenía existencias, para que reimportar no
  duplique nada—.
- **Web (fase 11, empezada)**: entrada y cambio de contraseña obligatorio contra la API real;
  menú lateral con las entradas que permiten los permisos de la sesión; y las tres pantallas del
  catálogo (productos con alta/edición/baja e importación con simulación, ubicaciones y bodegas),
  con la marca del paquete de iconos (favicon, manifiesto, glifo del armazón) y el loader de Lottie
  mientras la importación trabaja. `tsc`, `eslint` (con las guardias de capas y de envoltorios
  **probadas fallando**) y `vite build` en verde, y 30 pruebas de navegador: 25 con la API simulada (6 del
  catálogo, 6 de los módulos de lectura y 13 de las acciones de escritura), y 5 contra la API real
  (entrada y sesión).

```bash
# El catálogo, de punta a punta (necesita una sesión con permisos de catálogo)
curl -s -X POST http://127.0.0.1:8081/v1/products/import   -H "Authorization: Bearer $TOKEN" -F "file=@catalogo.csv" -F "dryRun=true"
```

### No construido todavía

- **La conexión con el ERP**: el intento de exportación falla con `ERP_NOT_CONNECTED` y el contador
  sube; falta el adaptador del ERP (ADR-0013), que depende de conocer su contrato (D10).
- **Dentro de la web**: las pantallas de los módulos que aún no tienen pantalla (existencias,
  recepción, surtido, traslados, etiquetas y auditoría) no se dibujan, y el inicio dice cuáles faltan.
  Tampoco está el despliegue de la web (Dockerfile con nginx y su servicio en Compose). La marca sí:
  el paquete de iconos está versionado y conectado (favicon, manifiesto y el glifo del armazón).
- **La app** (Kotlin Multiplatform).
- **El conteo físico y el corte** del stock heredado: son trabajo del negocio, no de código
  ([cutover.md](docs/migracion/cutover.md)).

El orden y el criterio de terminado de cada uno están en
[docs/plan/PLAN-IMPLEMENTACION.md](docs/plan/PLAN-IMPLEMENTACION.md).
