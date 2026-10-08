# ARQUITECTURA.md — Arquitecturas, patrones y estándares

> **Este documento es normativo.** Si el código contradice algo de aquí, el código está
> mal. Si hay que cambiar una regla, se cambia **aquí primero** y se explica por qué.
>
> Se escribió porque el usuario preguntó *«¿qué arquitectura estamos usando? es
> necesario saberlo y ser estrictos con eso»*. Tenía razón en pedirlo: **el backend
> había derivado a un híbrido sin que nadie lo decidiera**, y nadie podía citar la
> regla que se estaba incumpliendo.

---

## 0. Resumen: las tres arquitecturas y por qué son distintas

| Parte | Arquitectura | Unidad de organización |
|---|---|---|
| **API** (`backend/`) | **Vertical slice** (package-by-feature) | La **feature** |
| **Web** (`web/`) | **FSD** (Feature-Sliced Design) | La **capa**, y dentro la feature |
| **App** (`mobile-app/`) | **Vertical slice en KMP** | La **feature**, con `core/` compartido |
| **Dominio** (`stock-domain/`) | **KMP puro** (hexagonal de facto) | El **concepto del negocio** |

**No son la misma arquitectura a propósito**, y conviene entender por qué:

- **FSD** (web) está pensado para interfaces con estado, donde importa mucho **de qué
  puede depender qué** (una página no puede importar de otra página). Su fuerza es la
  dirección de dependencias.
- **Vertical slice** (API y app) está pensado para que **cambiar una funcionalidad
  toque una sola carpeta**. Su fuerza es la localidad.
- **KMP puro** (dominio) está pensado para **no depender de nadie**, porque lo consumen
  las tres partes anteriores.

Lo que **sí es obligatorio en las tres**: los **nombres en inglés**, los **comentarios
en español**, y que **cada regla de negocio viva en un solo sitio**.

---

## 1. API — `backend/`

### 1.1 La arquitectura: vertical slice

**Una feature es una carpeta que contiene TODO lo suyo**: sus rutas, su servicio, sus
DTOs, su repositorio y sus tablas.

```
backend/src/main/kotlin/dev/axzy/stock/backend/
├── Application.kt            ← punto de entrada y composición
│
├── inventory/                ← UNA FEATURE, todo junto
│   ├── InventoryRoutes.kt        su HTTP
│   ├── InventoryService.kt       su lógica
│   └── InventoryDtos.kt          sus DTOs
├── receiving/
│   ├── ReceivingRoutes.kt
│   └── ReceivingService.kt
├── identity/
│   ├── AuthRoutes.kt
│   ├── IdentityAdminRoutes.kt
│   ├── IdentityRepository.kt
│   └── TokenService.kt
├── catalog/  audit/  health/
│
└── TRANSVERSAL — estos NO son features:
    ├── config/     DatabaseConfig
    ├── error/      ErrorResponse, códigos
    ├── security/   PermissionChecker, TokenCall
    └── tools/      herramientas de línea de comandos
```

### 1.2 La regla, en una frase

> **`routes/` NO EXISTE.** Cada feature se lleva su HTTP.

**Por qué.** La estructura anterior tenía `inventory/InventoryService.kt` con la lógica
y `routes/InventoryRoutes.kt` con su HTTP, **en carpetas distintas**. Para tocar
inventario había que saltar entre dos sitios, y `routes/` era un cajón con 8 archivos
sin relación entre sí. **No era por capas ni por features: lo peor de los dos.**

### 1.3 Qué va en cada sitio, con el criterio

| Si es… | Va en… |
|---|---|
| Un endpoint HTTP y su validación de entrada | `feature/FeatureRoutes.kt` |
| Una regla de negocio con transacción | `feature/FeatureService.kt` |
| La forma de una petición o respuesta HTTP | `feature/FeatureDtos.kt` |
| Acceso a una tabla que solo usa esta feature | `feature/FeatureRepository.kt` |
| Algo que usan **dos o más** features | `config/`, `error/`, `security/` |
| El punto de entrada | `Application.kt` (raíz) |

**El error más común es el contrario:** meter en `security/` algo que solo usa una
feature «porque es de seguridad». Si solo lo usa inventario, va en `inventory/`.

### 1.4-bis La estructura profesional de una feature

**Esto es lo que el usuario exigió: «verticals, singletons, tests, mappers y todo lo que
una API profesional debe llevar».** Aquí está el equivalente en un backend, con el
nombre técnico de cada pieza.

> ⚠️ **Nota sobre MVVM.** El usuario mencionó MVVM. **MVVM es un patrón de UI**
> (Model-View-ViewModel): su ViewModel existe para sostener el estado de una pantalla y
> sobrevivir a la rotación. **En una API no hay View ni estado de pantalla**, así que no
> aplica. Lo que sí aplica —y es lo que falta— es el **patrón de capas** de abajo. Se
> anota aquí porque la confusión es razonable: en el móvil sí se usa MVVM.

```
feature/
├── FeatureRoutes.kt      HTTP: validar entrada, autorizar, delegar, responder
├── FeatureService.kt     la regla de negocio, la transacción, los errores
├── FeatureRepository.kt  el SQL
├── FeatureDtos.kt        la forma de la petición y de la respuesta
└── FeatureMappers.kt     de fila de base → DTO
```

**Las cinco piezas son obligatorias.** Si falta una, el trabajo se mete en la de al lado:

| Si falta… | Acaba dentro de… | Y entonces… |
|---|---|---|
| **Service** | las rutas | la regla solo se prueba por HTTP |
| **Mapper** | el servicio | la consulta y el mapeo se entrelazan; no se prueba solo |
| **Dtos** | el servicio | el JSON se acopla a los nombres de columna |
| **Repository** | el servicio | el SQL se mezcla con la lógica |

#### Los mappers, y por qué no son burocracia

Un mapper es **una función de extensión sobre `ResultSet`** que construye el DTO:

```kotlin
internal fun ResultSet.toStockItemDto(): StockItemDto = StockItemDto(
    productCode = getString("product_code"),
    quantity = getBigDecimal("quantity").toPlainString(),
)
```

**Sin mappers, esto está dentro del bucle de lectura de la consulta:**

```kotlin
add(KardexDto(id = rs.getLong("id"), movedAt = ..., /* 15 campos más */))
```

…mezclado con el `WHERE`, el `LIMIT` y el `ORDER BY`. Para saber qué forma tiene la
respuesta hay que leer el método entero. Y si cambia una columna, **hay que encontrarla en
todos los sitios donde se mapea**.

**Trampa que el compilador NO detecta:** el mapper lee por **nombre de columna en una
cadena**. `getString("product_code")` compila igual si el SQL dice `AS productCode`, y
devuelve `null` en silencio. **Un mapper mal escrito no rompe: miente.** Por eso los
mappers se prueban con `curl` contra la respuesta real, además de con tests.

#### Singletons: un contenedor, no `object`

Las dependencias viven en `config/AppDependencies.kt`, construidas **una vez**:

```kotlin
class AppDependencies(private val dataSource: DataSource) {
    val auditWriter: AuditWriter by lazy { AuditWriter(dataSource) }
    val inventoryService: InventoryService by lazy { InventoryService(dataSource, auditWriter) }
}
```

**`by lazy` y no `val` a secas**, porque si un servicio depende de otro, **el orden de las
líneas decidiría si la dependencia ya existe**: reordenar dos propiedades rompería la
aplicación sin tocar la lógica.

**Nunca `object`**: un `object` de Kotlin no se puede sustituir en un test ni acepta un
`DataSource`.

### 1.4 Las capas dentro de una feature

```
Routes  → valida la entrada, comprueba el permiso, llama al servicio
   ↓
Service → la regla de negocio, la transacción, los códigos de error
   ↓
Repository → SQL
```

**Las rutas NO contienen lógica de negocio.** No calculan, no deciden, no escriben SQL.
Validan, autorizan, delegan y traducen a HTTP.

**Por qué importa:** cuando la regla vive en el servicio, se puede probar sin arrancar
un servidor. Cuando vive en la ruta, solo se prueba por HTTP y se olvida.

### 1.5 La lógica de inventario vive en SQL, y es deliberado

`aplicar_movimiento_a_ubicacion`, `recalcular_total_producto`, `registrar_movimiento` y
`comprobar_proyecciones` son **funciones de PostgreSQL** (migración `V6`).

**Por qué en la base y no en Kotlin:** un movimiento tiene que ajustar las ubicaciones,
recalcular el total y escribir el kardex **en una sola transacción**. Si se hiciera
desde el código, un fallo a mitad dejaría el inventario descuadrado **y nadie lo sabría
hasta el conteo físico**.

**Consecuencia práctica:** el kardex es **append-only**, y las proyecciones se pueden
comprobar en cualquier momento. Eso es lo que el legacy no podía hacer.

---

## 2. Web — `web/`

### 2.1 La arquitectura: FSD (Feature-Sliced Design)

```
web/src/
├── app/        providers, router, guards, estilos globales
├── pages/      una carpeta por pantalla
├── widgets/    bloques grandes reutilizables (layout, tabla)
├── features/   acciones con significado de negocio (login, capture)
├── entities/   modelos del negocio y su acceso a datos (catalog, reception)
└── shared/     api, ui, lib, config — sin lógica de negocio
```

### 2.2 La regla de dependencias — la única que importa de verdad

> **Una capa solo puede importar de las capas INFERIORES.**

```
app  →  pages  →  widgets  →  features  →  entities  →  shared
```

**Prohibido**, y es la razón de existir de FSD:
- Una **página** no importa de otra página.
- Una **feature** no importa de otra feature.
- **`shared/` no importa de NADA.** Si `shared/` importa de `entities/`, se ha roto.

**Por qué:** sin esta regla, dos pantallas acaban acopladas y cambiar una rompe la
otra sin que nadie lo vea hasta el despliegue.

### 2.3 Reglas concretas que ya nos han mordido

**1. Las rutas van SIN `/v1`.** `API_CONSTANTS.BASE_URL` ya vale `/v1`.

```ts
get("/invoices/pending-approval")       // ✅
get("/v1/invoices/pending-approval")    // ❌ → /v1/v1/... → 404
```

**Y `tsc` y `eslint` NO detectan esto**: ninguno valida una URL en ejecución.
Un error de ruta relativa **solo se ve llamándola**. Por eso: **se prueba con `curl`
antes de escribir la interfaz, siempre.**

**2. El nombre visible de una ubicación lo compone el SERVIDOR.** El cliente nunca
concatena `hall+code+position`. Si mañana cambia el formato de las etiquetas, no hay
que tocar el backend, la web y el móvil a la vez.

**3. Los datos de servidor son `string` decimal, no `number`.** `"5193.000"`. Tipar
contra el contrato en lugar de contra la respuesta real pinta `undefined`.

**4. Una pantalla sin endpoint NO muestra filas inventadas.** Dice qué hará y que el
endpoint no existe. **Una tabla con datos falsos es peor que una pantalla vacía**,
porque el usuario decide con ellos.

---

## 3. App — `mobile-app/`

### 3.1 La arquitectura: build KMP autocontenido + vertical slice

**`mobile-app` es su PROPIO build de Gradle**, separado del monorepo. No es un
submódulo: **un directorio no puede ser raíz de dos builds de Gradle**, y meterlo con
`include(":mobile-app")` hace que IntelliJ no pueda sincronizarlo.

```
mobile-app/
├── settings.gradle.kts      include(":shared") + include(":androidApp")
├── build.gradle.kts         los plugins con `apply false`
├── shared/                  la biblioteca KMP
│   └── src/{commonMain,androidMain,iosMain,jvmMain,jvmTest}
│       └── dev/axzy/stock/mobile/
│           ├── App.kt       navegación y montaje
│           ├── core/        theme, ui, camera — compartido
│           └── feature/     una carpeta por funcionalidad
├── androidApp/              el APK, submódulo NORMAL
└── iosApp/                  proyecto Xcode
```

**Consume el dominio por `includeBuild("..")`**, que es **la misma instancia y no una
copia**: las decisiones de negocio son idénticas en backend y móvil **por construcción**.

### 3.2 Targets: por qué iOS es opt-in

| Target | Cuándo |
|---|---|
| **JVM** | siempre — es lo que corre los tests en segundos |
| **Android** | siempre |
| **iOS** | **opt-in** con `-PenableIosTargets=true` |

**Por qué opt-in:** sin el flag, el build funciona **con solo un JDK**, sin descargar
Kotlin/Native (~300 MB). **El precio** es que IntelliJ avisa «iOS Source Set Used
Without an iOS Target» y que iOS exige recordar el flag. **Se intentó activarlo por
defecto y obliga a descargar Kotlin/Native**, que el entorno bloquea. Se prefiere un
build que funciona en cualquier máquina.

### 3.3 Precio, dicho claro

Los comandos del móvil se lanzan **desde `mobile-app/`**, no desde la raíz. Es el mismo
precio que paga el proyecto de referencia.

```bash
cd mobile-app && ./gradlew :shared:jvmTest
./scripts/movil-android.sh instalar
```

---

## 4. Dominio — `stock-domain/`

### 4.1 La regla: KMP puro, sin dependencias

**Solo puede usar `kotlin-stdlib` + `kotlinx-datetime`, `kotlinx-serialization` y
`kotlinx-coroutines`.** Nada más.

**Está impuesto por una guardia de arquitectura** (`verifyDomainPurity` y
`forbidJvmMainSourceSet`), no por buena voluntad:

```
✓ stock-domain no tiene código específico de plataforma
✓ stock-domain es Kotlin puro (guardia de arquitectura OK)
```

**Comprueba además** que no se use `@JvmInline` ni ninguna anotación de JVM: compila en
JVM y **rompe iOS**.

### 4.2 Qué vive aquí

| Carpeta | Contenido |
|---|---|
| `value/` | `Quantity`, `ProductCode`, `LocationCode` — tipos que **no se pueden construir mal** |
| `common/` | `Either`, `DomainResult` |
| `error/` | `DomainError` — el catálogo de errores |
| `inventory/` | `StockMovement`, `InventoryLedger`, `Adjustment` |
| `receiving/` | `InvoiceLine`, la máquina de estados |
| `access/` | `PermissionCatalog`, `AccessEngine`, RBAC + ABAC |
| `audit/` | `AuditModel`, `AccessDecisionTrace` |

### 4.3 Por qué las reglas viven en el dominio

**Una regla en el dominio se puede probar en milisegundos y se cumple en las tres
partes.** Una regla en un endpoint se prueba por HTTP y se olvida en el siguiente.

Ejemplo real: `AdjustmentRequest.create` exige cantidad **positiva**. Cuando solo se
comprobaba `isZero`, una cantidad **negativa** con dirección `DECREASE` **sumaba
inventario sin necesitar aprobación**. Se arregló en el tipo, no en el endpoint, porque
**la próxima ruta que se añada tendría el mismo agujero**.

**Corolario:** si puedes construir un objeto inválido, el tipo está mal diseñado. Usa
constructores privados con fábricas que devuelvan `Either`.

---

## 5. Estándares transversales — obligatorios en las tres partes

### 5.1 Idioma: la regla que el usuario exigió

> **Variables, métodos, constantes, clases, tablas y propiedades: EN INGLÉS.
> Solo los COMENTARIOS en español.**

```kotlin
// ✅
fun approveInvoice(invoiceId: Long, reason: AdjustmentReason)

// ❌
fun aprobarFactura(facturaId: Long, motivo: AdjustmentReason)
```

**Lo que NO se traduce nunca:**

| Qué | Por qué |
|---|---|
| **Los comentarios** | Son para el equipo, y el equipo piensa en español |
| **Los mensajes de error** | `"El producto no existe"` **lo lee el operador** |
| **Los `displayName`** | Es texto de interfaz |
| **Los valores de enum** | Están en la base y en el contrato (`SHORTAGE`, `PENDING`) |
| **Los códigos de error** | `INSUFFICIENT_STOCK` es **API pública** |

**Los nombres de tabla y columna YA están en inglés** y no se tocan.

### 5.2 Comentarios: el estilo de la casa

**Explican el POR QUÉ, no el qué.** El qué ya lo dice el código.

```kotlin
// ❌ No aporta nada
// Suma la cantidad al saldo
quantity = quantity + delta

// ✅ Explica la decisión y el tropiezo
// La cualificación es OBLIGATORIA: en `DO UPDATE`, un nombre sin cualificar se
// resuelve a la fila PROPUESTA, así que `quantity + delta` calculaba
// `delta + delta` y el saldo NUNCA se acumulaba.
quantity = stock_by_location.quantity + delta
```

**Se documentan los tropiezos.** Un comentario que dice «esto se intentó y rompía X»
vale más que diez que describen lo obvio: evita que el siguiente lo repita.

### 5.3 Auditoría: las 6 dimensiones

**Todo cambio de estado tiene que registrar quién, qué, sobre qué, cuándo, desde dónde
y con qué permiso.** La tabla `audit_log` ya tiene las 6 dimensiones, particiones
mensuales, cadena de hash y disparadores de inmutabilidad.

> ⚠️ **ESTADO REAL: NADIE ESCRIBE EN `audit_log`.** Tiene 0 filas. El endpoint de
> consulta existe, el esquema está completo, y **no registra nada**. Es una fachada, y
> es el requisito central del proyecto sin cumplir.

**La auditoría es append-only.** No hay `UPDATE` ni `DELETE`: los disparadores los
hacen fallar **a propósito**. Las correcciones son registros nuevos.

### 5.4 Permisos: RBAC + ABAC, denegar por defecto

```
Identidad → RBAC (rol) → ABAC (atributos) → decisión
```

- **Denegar por defecto.** Sin decisión explícita, se niega.
- **`DENY` gana sobre `ALLOW`**, siempre.
- `PermissionScope`: `NONE < OWN < AREA < ALL`.

> **Regla que ya nos mordió:** el permiso de una ruta **tiene que ser el mismo** que el
> del guard que protege su pantalla. La ruta de reconciliación exigía
> `inventory.reconcile` mientras la web la protegía con `report.view`: **un usuario con
> permiso de reportes entraba y recibía un 403.** Dos listas que deben coincidir
> acaban separándose, y el síntoma aparece solo en producción con un rol concreto.

### 5.5 Errores: catálogo cerrado

Los códigos están en `CONTRATO_API.md` §1.3-bis y **no se inventan**. Un error nuevo se
añade al catálogo **antes** de usarlo.

**Un `500` es una mentira.** Dice «el servidor falló» cuando el servidor está bien.
Un cliente que reintenta ante un `500` **reintenta para siempre**; ante un `400`,
corrige.

### 5.6 Paginación: el total se CUENTA

```kotlin
Page(items, page.page, page.size, total)   // total de un SELECT count(*)
```

**Nunca `items.size`.** Ya hubo dos bugs por esto:
- En inventario: `totalItems` devolvía el tamaño de la página → «50 resultados» siempre.
- En kardex: el conteo **ignoraba los filtros** → un rango sin resultados daba
  `items: []` pero `totalItems: 1064`.

> **Si el conteo ignora los filtros, es peor que no tenerlo.**

`size` mayor de **200 se RECHAZA** con `400`, no se recorta: recortar hace que el
cliente crea que tiene todos los datos.

---

## 6. Verificación: las reglas que impiden el autoengaño

Estas no son recomendaciones. Son la respuesta a errores concretos que ya cometimos.

**1. Lee los XML de los tests, nunca «BUILD SUCCESSFUL».**
Gradle reporta éxito con 0 tests ejecutados.

```bash
python3 -c "
import glob, xml.etree.ElementTree as ET
for mod in ['stock-domain','backend']:
    t=f=0
    for p in glob.glob(mod+'/build/test-results/**/TEST-*.xml', recursive=True):
        r=ET.parse(p).getroot(); t+=int(r.get('tests',0)); f+=int(r.get('failures',0))
    print(f'{mod}: {t} tests, {f} fallos')
"
```

**2. Prueba que una guardia FALLA antes de creerla.**
Se quitó la condición `quantity.isNegative` a propósito y los 2 tests nuevos **fallaron**.
**Un test que nunca ha fallado no demuestra que vigile nada.**

**3. Los tests compilan el código; el servidor corre un ARTEFACTO.**
Son dos afirmaciones distintas. Tras cambiar código:

```bash
./gradlew :backend:installDist && reiniciar
```

**Este error nos mordió dos veces** (`NoClassDefFoundError` en producción con 153 tests
en verde).

**4. Prueba una URL con `curl` antes de escribir la interfaz.**
`tsc` y `eslint` **no validan URLs en ejecución**. El bug `/v1/v1/...` pasó los dos.

**5. Un resultado alarmante también se verifica antes de creerlo.**
Una consulta mal formada hizo creer que el kardex había perdido 1.064 filas. **Estuve a
punto de investigar una pérdida de datos que no existía.**

**6. Los agentes en paralelo necesitan archivos DISJUNTOS.**
Dos agentes modificaron `Application.kt` a la vez y el build salió con
`FileNotFoundException: DatabaseConfig.class`. **Los que compilan Gradle se serializan.**
Lo mismo: **dos agentes no pueden tocar el mismo archivo**, nunca.

---

## 7. Flujo de trabajo entre las partes

```
                 stock-domain  (KMP puro, sin dependencias)
                        │
        ┌───────────────┼───────────────┐
        │               │               │
    backend/        web/         mobile-app/
   (vertical)      (FSD)      (vertical, build propio)
        │               │               │
        └── CONTRATO_API.md ────────────┘
             (la web y el móvil hablan HTTP)
```

**El contrato es la frontera.** La web y el móvil **no importan código del backend**:
hablan HTTP. Eso permite cambiarlo sin recompilar los clientes.

> ⚠️ **`CONTRATO_API.md` §9 miente hoy**: promete `productsChecked`/`ledgerNet`/`projected`
> y la ruta devuelve `cuadra`/`totalEnBd`/`totalEsperado`. **Un contrato que miente es
> peor que no tenerlo**, porque el cliente se tipa contra él y pinta `undefined`.

---

## 8. Lo que NO está verificado — hay que saberlo

Un documento de arquitectura que solo dice lo que va bien no sirve para decidir.

| Qué | Estado |
|---|---|
| **Auditoría** | **0 filas. Nadie escribe.** El esquema está completo y sin usar |
| **`CONTRATO_API.md` §9** | Describe campos que la ruta no devuelve |
| **iOS** | El `.xcodeproj` **nunca se abrió en Xcode**. El framework compila, el proyecto no está verificado |
| **Cámara del móvil** | Sin probar en dispositivo real |
| **Móvil ↔ API** | Los módulos `stock-network`/`stock-database`/`stock-datastore` (130 tests) **no están conectados a las pantallas** |
| **Swagger** | No existía; en implementación |
| **Fases 3, 3-bis, 4** | Surtido, traslados y etiquetas: ~21 rutas del contrato sin implementar |
