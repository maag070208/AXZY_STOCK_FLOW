# ARQUITECTURA.md — Arquitecturas, patrones y estándares

> **Este documento es normativo.** Si el código contradice algo de aquí, el código está
> mal. Si hay que cambiar una regla, se cambia **aquí primero** y se explica por qué.
>
> Se escribió porque el usuario preguntó *«¿qué arquitectura estamos usando? es
> necesario saberlo y ser estrictos con eso»*. Tenía razón en pedirlo: **el backend
> había derivado a un híbrido sin que nadie lo decidiera**, y nadie podía citar la
> regla que se estaba incumpliendo.

---

## 0. Resumen: la arquitectura de cada parte, y por qué son distintas

| Parte | Arquitectura | Unidad de organización |
|---|---|---|
| **API** (`backend/`) | **Clean Architecture** (hexagonal) | La **feature**, y **dentro** la capa |
| **Web** (`web/`) | **FSD** (Feature-Sliced Design) | La **capa**, y dentro la feature |
| **App** (`mobile-app/`) | **Vertical slice en KMP** | La **feature**, con `core/` compartido |
| **Dominio** (`stock-domain/`) | **KMP puro** (hexagonal de facto) | El **concepto del negocio** |

**No son la misma arquitectura a propósito**, y conviene entender por qué:

- **Clean Architecture** (API) está pensada para que **la regla de negocio no dependa de
  nada**: `infrastructure → application → domain`, nunca al revés (§1.3). Su fuerza es la
  dirección de dependencias, y su precio es que **las capas van dentro de cada feature**,
  no en carpetas globales. **Revierte la decisión anterior del backend, que era vertical
  slice; el porqué está en §1.1 y no se borra la historia.**
- **FSD** (web) está pensado para interfaces con estado, donde importa mucho **de qué
  puede depender qué** (una página no puede importar de otra página). Su fuerza es la
  dirección de dependencias.
- **Vertical slice** (app) está pensado para que **cambiar una funcionalidad
  toque una sola carpeta**. Su fuerza es la localidad. **Sigue siendo la arquitectura del
  móvil**, y es la que el backend acaba de abandonar.
- **KMP puro** (dominio) está pensado para **no depender de nadie**, porque lo consumen
  las tres partes anteriores.

Lo que **sí es obligatorio en las tres**: los **nombres en inglés**, los **comentarios
en español**, y que **cada regla de negocio viva en un solo sitio**.

---

## 1. API — `backend/`

### 1.1 La arquitectura: Clean Architecture (hexagonal), y por qué se cambió

**Esta sección REVIERTE una decisión anterior del proyecto.** Hasta hoy el backend era
**vertical slice** (package-by-feature): una feature era una carpeta con TODO lo suyo
dentro —rutas, servicio, DTOs, repositorio y tablas— y no había capas horizontales. Se
justificó así: *cambiar una funcionalidad toca una sola carpeta*. Su fuerza era la
**localidad**.

**Se revisa, y no por moda.** La guía de arquitectura de API del usuario (rol
Staff/Principal) lo exige por escrito, y el propio código ya le daba la razón:

- **Sin la regla de dependencias escrita, cada archivo decide por su cuenta.** En
  vertical slice nada impedía que una ruta importara el driver JDBC o que un DTO
  importara Ktor. Cuando la regla no está escrita, **el diseño se degrada sin que nadie
  tome la decisión** — que es exactamente lo que ya pasó una vez en este proyecto.
- **La lógica de negocio quedó atrapada en las rutas.** `catalog/` es la prueba: llegó a
  tener **741 líneas de rutas en un solo archivo, con tres recursos dentro, y NINGÚN
  `CatalogService`**, así que una regla que afectara a más de un repositorio no tenía
  dónde vivir y acababa repartida por los handlers.
- **Sin puertos no hay dobles de prueba.** Si el caso de uso pide un `DataSource` en vez
  de exigir una interfaz, la única prueba posible corre contra PostgreSQL. Eso está bien
  como test de integración, pero deja la regla de negocio **sin cubrir en aislamiento**.

> La decisión anterior **no se borra**: se documenta y se explica por qué se cambia. Si
> alguien pregunta «¿por qué no seguimos con vertical slice?», la respuesta está aquí y no
> en el historial de una conversación.

**Lo que se CONSERVA de vertical slice es la localidad:** las capas van **DENTRO de la
feature**, nunca en carpetas globales del tipo `services/` y `repositories/`. Es Clean
Architecture **sin volver al cajón por tipo de archivo**, que es el error que la
estructura anterior ya había corregido una vez.

**Esta reversión es solo del backend.** La app (`mobile-app/`) sigue en vertical slice y
la web sigue en FSD: cada parte tiene su arquitectura por las razones de §0.

### 1.2 La estructura: capas DENTRO de la feature

```
backend/src/main/kotlin/dev/axzy/stock/backend/
├── Application.kt                  punto de entrada y composición
├── domain/                         model/ (modelos propios del backend) + error/ (DomainFailure)
├── application/
│   ├── <feature>/                  caso de uso + puertos + DTOs + mappers + entrada/salida
│   │   ├── <Feature>UseCase.kt
│   │   ├── <Feature>Ports.kt       interfaces: lo que el caso de uso EXIGE
│   │   ├── <Feature>Dtos.kt
│   │   ├── <Feature>Mappers.kt
│   │   └── <Feature>Input.kt       (opcional: validación de entrada, sin Ktor)
│   ├── shared/                     Page, paginación, resultados de consulta
│   └── audit/                      AuditWriter como puerto de salida
└── infrastructure/
    ├── inbound/http/<feature>/     rutas Ktor, serialización, códigos de estado
    └── outbound/persistence/       repositorios, tablas Exposed, SQL, clientes externos
        ├── <feature>/
        └── tables/
└── config/ error/ security/ health/ tools/ docs/   transversales
```

> ⚠️ **ESTADO REAL, MEDIDO: este árbol es la forma OBJETIVO y NO existe todavía.**
>
> El backend sigue siendo `package-by-feature` **con una excepción ya hecha**: `catalog/`
> está partido en archivo por recurso **y en subpaquete por recurso** —`catalog/product/`,
> `catalog/location/`, `catalog/warehouse/`— (ver «Partir `catalog/`» al final), que es el
> primer paso y el que más dolía. Las demás features (`inventory/`, `receiving/`,
> `identity/`, `audit/`, `health/`) siguen en la raíz del paquete, con sus rutas, sus
> servicios y sus tablas mezclados en la misma carpeta.
>
> **`domain/` y `application/` e `infrastructure/` como carpetas NO EXISTEN.** Lo que sí
> existe y se cumple es la **dirección de dependencias** de §1.3, que es la regla que de
> verdad importa: hoy ningún archivo del backend depende de nada que no pueda ver.
>
> Se intentó la migración completa el 2026-10-07 y **se revirtió a propósito**: mover
> 12.000 líneas a la vez con un script rompió imports en 28 archivos (906 errores) y lo
> único verificable que quedó fue el destrozo. Un refactor de esta clase se hace
> **por feature y compilando entre fases**, no de una vez. Las fases están al final.
>
> **Los bloques de código que llevan rutas `application/...` o `infrastructure/...`
> muestran la forma OBJETIVO**, no archivos que existan todavía; cuando un ejemplo es
> código real, se dice de qué archivo sale. Las dos cosas se distinguen a propósito:
> **una arquitectura que solo se puede leer en el código que ya existe no sirve para
> migrar nada.**

**`routes/` NO EXISTE, y esto viene de un tropiezo viejo.** La estructura anterior a
vertical slice tenía `inventory/InventoryService.kt` con la lógica y
`routes/InventoryRoutes.kt` con su HTTP, **en carpetas distintas**. Para tocar inventario
había que saltar entre dos sitios, y `routes/` era un cajón con 8 archivos sin relación
entre sí. **No era por capas ni por features: lo peor de los dos.** En la estructura
nueva el HTTP sigue viviendo **con su feature**
(`infrastructure/inbound/http/<feature>/`), no en un `routes/` global: la capa cambia,
la localidad no.

### 1.3 La regla de dependencias — la única que no se negocia

> **`infrastructure` → `application` → `domain`. Nunca al revés. Nunca un salto.**

| Capa | Puede importar | **PROHIBIDO** importar |
|---|---|---|
| `domain/` | Kotlin stdlib, `stock-domain` | **Ktor, Exposed, JDBC, HikariCP, `kotlinx.serialization`** |
| `application/` (caso de uso y puertos) | `domain/`, stdlib, `kotlinx-coroutines` | **Ktor, Exposed, JDBC, HikariCP, `kotlinx.serialization`** |
| `application/` (DTOs de la feature) | `domain/`, stdlib, `kotlinx.serialization` | **Ktor, Exposed, JDBC, HikariCP** |
| `infrastructure/` | todo lo anterior, Ktor, Exposed, JDBC, HikariCP | — |

**Por qué el caso de uso NO puede llevar `@Serializable`.** Los DTOs de la feature son la
**forma del contrato** y por eso sí pueden anotarse; el archivo del caso de uso es la
**regla de negocio**, y anotarlo lo ata a la representación HTTP. El día que haya que
devolver lo mismo por otro canal, el caso de uso arrastraría la serialización encima. **La
forma de la respuesta es de la capa HTTP; la decisión, del caso de uso.**

**Por qué esto no es ceremonia.** El acoplamiento no se nota cuando se escribe: se nota
cuando hay que probar. Un caso de uso que importa `java.sql.Connection` **no se puede
probar sin base de datos**, y un `domain/` que importa `io.ktor.http.HttpStatusCode` deja
de poder reutilizarse fuera del servidor.

**Estado real:** la regla **no tiene guardia automática** todavía. Nada impide hoy que
`application/` importe Ktor. Se verifica a mano con:

```bash
grep -rn "import io.ktor\|import org.jetbrains.exposed\|import java.sql\|import com.zaxxer" \
  backend/src/main/kotlin/dev/axzy/stock/backend/application \
  backend/src/main/kotlin/dev/axzy/stock/backend/domain
```

Salida esperada: **vacía**. Añadir esa comprobación como tarea de Gradle —al estilo de
`verifyDomainPurity` de `stock-domain/build.gradle.kts`— está en el plan de migración.

### 1.4 Qué va en cada sitio, con el criterio

| Si es… | Va en… |
|---|---|
| Un endpoint HTTP, la validación de forma y los códigos de estado | `infrastructure/inbound/http/<feature>/<Feature>Routes.kt` |
| Una regla de negocio, un caso de uso | `application/<feature>/<Feature>UseCase.kt` |
| Lo que el caso de uso **EXIGE** del exterior (interfaz, sin implementación) | `application/<feature>/<Feature>Ports.kt` |
| La forma de la petición o de la respuesta HTTP | `application/<feature>/<Feature>Dtos.kt` |
| Validación de entrada **sin Ktor** | `application/<feature>/<Feature>Input.kt` (opcional) |
| De fila de base → DTO | `application/<feature>/<Feature>Mappers.kt` |
| SQL, Exposed, clientes externos, lectura de ficheros | `infrastructure/outbound/persistence/<feature>/` |
| Tablas Exposed compartidas por varias features | `infrastructure/outbound/persistence/tables/` |
| `Page`, paginación, resultados de consulta | `application/shared/` |
| La escritura de auditoría, como puerto de salida | `application/audit/` |
| Un modelo propio del backend y sus fallos | `domain/model/`, `domain/error/` |
| Algo que usan **dos o más** features | `config/`, `error/`, `security/`, `application/shared/` |
| El punto de entrada y la composición | `Application.kt` (raíz) |

**El error más común es el contrario:** meter en `security/` o en `error/` algo que solo
usa una feature «porque es de seguridad». Si solo lo usa inventario, va dentro de
`inventory/`, en la capa que le toque.

### 1.5 Las capas dentro de una feature

```
Routes  → valida la forma, comprueba el permiso, llama al caso de uso
   ↓
UseCase → la regla de negocio, el ámbito transaccional, los errores tipados
   ↓
Ports   → lo que el caso de uso EXIGE (interfaces, sin implementación)
   ↓
Repository → el SQL, la tabla, el cliente externo (implementa el puerto)
```

**Las rutas NO contienen lógica de negocio.** No calculan, no deciden, no escriben SQL.
Validan la forma, autorizan, delegan y traducen a HTTP.

**Por qué importa:** cuando la regla vive en el caso de uso se prueba sin arrancar un
servidor. Cuando vive en la ruta, solo se prueba por HTTP y se olvida.

**Los puertos se declaran donde se EXIGEN, no donde se implementan.** El puerto vive en
`application/` porque es el caso de uso quien lo pide; la implementación vive en
`infrastructure/`. Si el puerto viviera junto al repositorio, la aplicación volvería a
depender de la infraestructura — que es exactamente el acoplamiento que la regla de §1.3
prohíbe.

### 1.6 Las piezas de una feature, y qué pasa si falta una

```
<feature>/
├── <Feature>UseCase.kt    la regla de negocio; no conoce Ktor ni JDBC
├── <Feature>Ports.kt      las interfaces que el caso de uso EXIGE
├── <Feature>Dtos.kt       la forma de la petición y de la respuesta
├── <Feature>Mappers.kt    de fila de base → DTO
└── <Feature>Input.kt      validación de entrada, sin Ktor (opcional)
```

**Las piezas son obligatorias.** Si falta una, el trabajo se mete en la de al lado:

| Si falta… | Acaba dentro de… | Y entonces… |
|---|---|---|
| **UseCase** | las rutas | la regla solo se prueba por HTTP |
| **Ports** | el caso de uso | el caso de uso instancia el repositorio concreto y no se puede sustituir en un test |
| **Mapper** | el caso de uso | la consulta y el mapeo se entrelazan; no se prueba solo |
| **Dtos** | el caso de uso | el JSON se acopla a los nombres de columna |
| **Repository** | el caso de uso | el SQL se mezcla con la lógica de negocio |

> ⚠️ **Nota sobre MVVM.** El usuario mencionó MVVM. **MVVM es un patrón de UI**
> (Model-View-ViewModel): su ViewModel existe para sostener el estado de una pantalla y
> sobrevivir a la rotación. **En una API no hay View ni estado de pantalla**, así que no
> aplica. Lo que sí aplica —y es lo que faltaba— es el **patrón de capas** de §1.5. Se
> anota aquí porque la confusión es razonable: en el móvil sí se usa MVVM.

### 1.7 Los mappers, y por qué no son burocracia

Un mapper es **una función de extensión sobre `ResultSet`** que construye el DTO:

```kotlin
// Código REAL de backend/.../inventory/InventoryMappers.kt, abreviado.
internal fun ResultSet.toStockByLocationDto(): StockByLocationDto = StockByLocationDto(
    productCode = getString("product_code"),
    quantity = getBigDecimal("quantity").toPlainString(),
    // El nombre visible NO se lee de una columna: se compone aquí (ver §2.3). La base
    // guarda `hall`, `code` y `position` por separado y el operador ve una sola etiqueta.
    locationDisplayName = locationDisplayName(getString("hall"), getString("code")),
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

### 1.8 Composición: dependencias por constructor, nunca `object`

Las dependencias se construyen **una vez** y se **inyectan por constructor**. El
composition root es `Application.kt` / `config/AppDependencies.kt`:

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
`DataSource`. Un `object` que guarda estado, además, se comparte entre peticiones.

**Koin ya está en el classpath** (`libs.koin.ktor`, 4.0.4) y **hoy no se usa**: el cableado
es a mano con `by lazy`. La regla es **inyección por constructor en las dos formas**; el
contenedor es un detalle de `config/` y no debe filtrarse a `application/`. Cuando el
cableado crezca, Koin entra **en el composition root**, no en las clases: un caso de uso
que recibe su repositorio por constructor se prueba igual con Koin o sin él.

### 1.9 Modelado defensivo: tipos que no se pueden construir mal

**La regla de §4.3 aplicada al backend:** si puedes construir un objeto inválido, el tipo
está mal diseñado.

**1. `@JvmInline value class` para las primitivas del dominio.** Un `Long` que es un
identificador de producto y un `Long` que es una cantidad **son el mismo tipo para el
compilador** y cosas distintas para el negocio: intercambiarlos compila. Un `value class`
lo impide sin coste en tiempo de ejecución (es un `Long` por debajo).

```kotlin
// ✅ El compilador no deja pasar un Quantity donde va un ProductCode.
fun adjust(productId: Id<Product>, quantity: Quantity): Adjustment

// ❌ Los dos son Long: el orden invertido compila y descuadra el inventario.
fun adjust(productId: Long, quantity: Long)
```

> ⚠️ **VERIFICADO, y rompe el build de iOS si se ignora.** En `stock-domain` (Kotlin
> Multiplatform) la anotación **tiene que escribirse calificada**: `@kotlin.jvm.JvmInline`.
> Sin calificar, el dominio **compila en JVM y ROMPE iOS** con «Unresolved reference
> 'JvmInline'». Lo encontró Kotlin/Native, no la guardia, y desde entonces
> `stock-domain/build.gradle.kts` lo comprueba (§4.1). En `backend/`, que es JVM puro,
> `@JvmInline` sin calificar **sí** vale.

```kotlin
// stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/value/Values.kt
@kotlin.jvm.JvmInline          // ← CALIFICADO: sin esto no compila para iOS
@Serializable
value class Quantity private constructor(val value: Long) {
    companion object {
        const val SCALE = 3
        val ZERO = Quantity(0)
        fun of(units: Long): Quantity = Quantity(units * 1000L)
    }

    val isZero: Boolean get() = value == 0L
    operator fun plus(other: Quantity): Quantity = Quantity(value + other.value)
}

// backend/src/main/kotlin/.../application/catalog/CatalogDtos.kt (JVM puro: sin calificar)
@Serializable(with = JsonQuantitySerializer::class)
@JvmInline
value class JsonQuantity(val thousandths: Long)
```

**Ya existen y se usan**, no hay que inventarlos: `Id<T>`, `ProductCode`, `LocationCode`,
`Quantity`, `BoxQuantity` y `UnitsPerBox` en
`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/value/Values.kt`.

**2. `sealed interface` / `sealed class` para jerarquías cerradas y estados.** Un `sealed`
obliga a que el `when` cubra **todos** los casos: si mañana se añade un estado, **el
compilador señala cada `when` que hay que revisar**. Con una jerarquía abierta, el caso
nuevo se cuela y se trata como el `else`.

```kotlin
// stock-domain/.../access/AccessEngine.kt — seis estados, y el `when` los cubre TODOS.
sealed interface AccessDecision { /* Allowed, NotAuthenticated, ScopeDenied, PolicyDenied, PolicyRequired, SegregationOfDuties */ }

// Código REAL: backend/.../security/PermissionChecker.kt. Sin `else`: si se añade un
// estado al motor, esto deja de compilar y hay que decidir qué se responde.
private fun AccessDecision.toPermissionDecision(): PermissionDecision = when (this) {
    is AccessDecision.Allowed -> PermissionDecision.Permitted
    is AccessDecision.NotAuthenticated -> PermissionDecision.Denied("UNAUTHENTICATED", reason)
    is AccessDecision.ScopeDenied -> PermissionDecision.Denied(code, message)
    is AccessDecision.PolicyDenied -> PermissionDecision.Denied("POLICY_DENIED", message)
    is AccessDecision.PolicyRequired -> PermissionDecision.Denied("POLICY_DENIED", message)
    is AccessDecision.SegregationOfDuties -> PermissionDecision.Denied("SEGREGATION_OF_DUTIES", message)
}
```

**Y los mismos `sealed` son los catálogos de errores:** `Either` (`Right`/`Left`),
`DomainError` y `AccessDecision`. **Un `when` sin `else` es la red de seguridad**: el día
que se añade un caso, es el **compilador** el que encuentra los sitios a revisar, y no un
usuario en producción.

**3. Constructores privados con fábricas.** El constructor se cierra y solo se entra por
una fábrica que valida (`Quantity private constructor(...)`, arriba). El corolario de §4.3
es literal: *si puedes construir un objeto inválido, el tipo está mal diseñado*.

### 1.10 Concurrencia: coroutines, no hilos

**La regla:** todo es `suspend` de arriba abajo; **nada de `Thread`, `Thread.sleep`, ni
`runBlocking` fuera de `main`**.

| Trabajo | Dispatcher |
|---|---|
| E/S **bloqueante** (JDBC, ficheros, clientes HTTP sin coroutines) | **`Dispatchers.IO`** |
| CPU (cálculo, serialización pesada, hashing) | **`Dispatchers.Default`** |
| Continuaciones de Ktor | el que ya trae la petición — **no se cambia** |

```kotlin
class RegisterMovementUseCase(
    private val repository: MovementRepository,
) {
    suspend fun execute(command: RegisterMovement): Either<DomainError, MovementId> =
        // El driver de PostgreSQL es BLOQUEANTE: sin moverlo a IO, ocupa un hilo del
        // pool de Netty y deja de atender peticiones mientras espera a la base.
        withContext(Dispatchers.IO) {
            repository.register(command)
        }
}
```

**Concurrencia estructurada.** Nada de `GlobalScope`: su ciclo de vida no cuelga de nadie,
así que sobrevive a la petición que lo lanzó y **nadie puede cancelarlo ni esperarlo**.

```kotlin
// ✅ Se espera a los dos y se cancelan juntos si uno falla.
suspend fun loadDashboard(): Dashboard = coroutineScope {
    val stock = async { repository.readStock() }
    val audit = async { repository.readLastAudits() }
    Dashboard(stock.await(), audit.await())
}

// ✅ Con supervisorScope, el fallo de uno NO tumba al hermano (informes parciales).
suspend fun loadPartialReport(): Report = supervisorScope { /* ... */ }

// ❌ Sobrevive a la petición, nadie lo cancela y su error se pierde.
GlobalScope.launch { repository.writeAudit(entry) }
```

> ⚠️ **ESTADO REAL VERIFICADO: CERO usos de `Dispatchers.` y CERO `withContext` en
> `backend/src`.** Todo el JDBC es bloqueante y corre **sobre el hilo de Netty**. Bajo
> carga sostenida eso agota el pool del motor y el servidor deja de responder aunque la
> base esté sana. **Se documenta como deuda, con plan, en §8** — no se arregla en esta
> revisión porque el cambio toca cada servicio y hay que hacerlo con las capas ya
> migradas.

### 1.10-bis Hilos virtuales: la puerta que hoy está CERRADA

**Si algún día se sube a Java 21+**, valorar **hilos virtuales** para las tareas
síncronas: un hilo virtual bloqueado en JDBC **no ocupa un hilo del sistema**, así que el
problema de §1.10 se puede resolver sin reescribir el acceso a datos a coroutines.

**Hoy NO se puede usar, y el motivo es concreto:** el toolchain es **JVM 17**
(`backend/build.gradle.kts` → `jvmToolchain(17)`). Los hilos virtuales son **finales desde
Java 21**; en 17 no existen de forma estable. **No se propone subir el toolchain en este
documento**: primero se migran las capas, y el salto de JVM es una decisión aparte con su
propia verificación.

### 1.11 Errores: un fallo esperado se devuelve, no se lanza

> **Un error esperado de negocio NO se lanza: se devuelve.**

`Either` ya existe en
`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/common/Either.kt`, con `Right`
y `Left`, y **se usa en el dominio**. El motivo está escrito allí y merece repetirse: se
usa un tipo propio en lugar de `kotlin.Result` porque `Result` obliga a que el error sea
un `Throwable`, y **«la cantidad excede la factura» no es excepcional**. Modelarlo como
excepción lleva al `try/catch` por todas partes que hacía el legacy, y con él a los
errores silenciados.

```kotlin
// ✅ El caso de uso declara los fallos en el tipo de retorno: quien lo llama
// NO puede olvidarse de tratarlos. `AdjustmentRequest` y `AdjustmentResult` son
// tipos reales del proyecto (dominio e infraestructura de la API).
suspend fun execute(request: AdjustmentRequest): Either<DomainError, AdjustmentResult>

// ❌ El fallo viaja escondido en el tipo de retorno `Unit` y solo se ve en ejecución.
suspend fun execute(request: AdjustmentRequest)
```

**Los `try/catch` se encapsulan en el BORDE** —el repositorio o el cliente externo— y allí
se **traducen a un fallo tipado**. Un `SQLException` no sube a la aplicación: se convierte
en el fallo de dominio que corresponda.

```kotlin
// infrastructure/outbound/persistence/inventory/InventoryRepository.kt
override suspend fun findBalance(
    productId: Id<Product>,
    location: LocationCode,
): Either<DomainFailure, Quantity> =
    try {
        readBalance(connection, productId, location).asRight()
    } catch (error: SQLException) {
        // La traducción vive AQUÍ, en el borde: la aplicación no sabe que existe JDBC.
        DomainFailure.Storage(error.sqlState).asLeft()
    }
```

> **`DomainFailure` es el tipo de fallo propio del backend que aparece en el árbol de
> §1.2, y HOY NO EXISTE** (§8): el catálogo actual, `DomainError` en `stock-domain`, solo
> tiene errores **de negocio**. Por eso hace falta: un fallo de almacenamiento **no es una
> regla de negocio**, y meterlo en `DomainError` obligaría al dominio a saber que existe una
> base de datos. Esa distinción es la que hace que un `SQLException` se pueda traducir
> **sin mentir** sobre qué ha pasado.

**Excepción legítima:** un fallo **de programación** (estado imposible, `when` sin cubrir)
sí se lanza. Eso no es negocio, es un bug, y tiene que verse.

> ⚠️ **ESTADO REAL: la regla se cumple A MEDIAS.** Medido con
> `grep -rn "throw .*Exception" backend/src/main --include=*.kt | wc -l` → **119 `throw`
> de excepciones HTTP** (`BadRequestException`, `UnauthorizedException`,
> `UnprocessableException`, `ConflictException`, `NotFoundException`, `ForbiddenException`),
> **84 de ellos dentro de rutas y servicios**. En el dominio sí se usa `Either`. La
> traducción a fallo tipado existe, pero **convive con el lanzamiento**, y mientras
> convivan, la firma de un caso de uso no dice la verdad sobre lo que puede fallar.

### 1.12 Persistencia y transacciones

**El ámbito transaccional se define en la CAPA DE APLICACIÓN —el caso de uso—, no en las
rutas.** Es el caso de uso quien sabe **qué operaciones tienen que ser atómicas**; la ruta
solo sabe de HTTP. Si la transacción se abriera en la ruta, media regla de negocio quedaría
fuera de la transacción que la protege.

**Si se usa Exposed, `newSuspendedTransaction`.** Es la puerta de Exposed para coroutines:
no bloquea el hilo mientras dura la transacción.

**PERO AQUÍ HAY UNA PARTICULARIDAD VERIFICADA QUE MANDA SOBRE LA REGLA GENERAL, y hay que
respetarla:** el **motor de inventario y la escritura de auditoría viven en funciones
PL/pgSQL de PostgreSQL** (migraciones `V6`, `V7` y `V8`) porque **un movimiento tiene que
ajustar las ubicaciones, recalcular el total y escribir el kardex en una sola
transacción**, y la **cadena de hash de auditoría necesita un `pg_advisory_xact_lock`**
(`V7`) para no bifurcarse.

```
V6   aplicar_movimiento_a_ubicacion · recalcular_total_producto · registrar_movimiento
V7   audit_log_record  ← serializa la inserción con pg_advisory_xact_lock(hashtext('audit_log_chain'))
V8   comprobar_proyecciones()  ← los nombres de columna pasaron a inglés
```

**Es decir: la transacción la abre el caso de uso y el SQL la ejecuta.** El caso de uso
abre la conexión, fija `autoCommit = false`, llama a las funciones y hace `commit` o
`rollback`. **NO se propone mover eso a Kotlin.** Ya se intentó razonar «esto se haría
mejor en el código» y la respuesta es no: si el ajuste de ubicaciones, el recálculo y el
kardex se hicieran desde Kotlin, un fallo a mitad dejaría **el inventario descuadrado y
nadie lo sabría hasta el conteo físico**; y una cadena de hash escrita desde fuera de la
transacción **se bifurca** en cuanto dos peticiones coinciden.

```kotlin
// application/inventory/RegisterMovementUseCase.kt — el ámbito transaccional
suspend fun execute(command: RegisterMovement): Either<DomainFailure, MovementId> =
    withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                // El SQL de V6/V7 hace las tres cosas DENTRO de esta transacción:
                // ubicaciones + total + kardex, con el advisory lock de la cadena.
                val movementId = repository.register(connection, command)
                connection.commit()
                movementId.asRight()
            } catch (error: SQLException) {
                connection.rollback()
                DomainFailure.Storage(error.sqlState).asLeft()
            }
        }
    }
```

**Consecuencia práctica, ya conseguida:** el kardex es **append-only** y las proyecciones
se pueden comprobar en cualquier momento. Eso es lo que el legacy no podía hacer.

> ⚠️ **ESTADO REAL: la regla se cumple en la práctica, sin formalizar.** Hoy el patrón
> `dataSource.connection.use { ... connection.commit() }` está **en los servicios**
> (`InventoryService`, `ReceivingService`, `IdentityAdminService`), que es el sitio
> correcto —la capa de aplicación— pero **todavía dentro de la estructura vertical**, y
> hay que **arrastrarlo tal cual** al migrar, no reescribirlo.
>
> **`newSuspendedTransaction` NO se usa: CERO apariciones.** El driver JDBC se usa
> directo. Matiz honesto: en `identity/`, Exposed **sí** se usa con su `transaction(db) {}`
> **bloqueante**, que es justo lo que §1.10 señala como deuda (bloquea el hilo de Netty).
> Las dos cosas son ciertas y por eso van juntas en esta fila.

### 1.13 Seguridad: mínimo privilegio y contexto explícito

**Reglas de la casa:**

1. **Mínimo privilegio.** Cada endpoint exige **el permiso que necesita**, no el rol.
2. **Toda entrada se valida ANTES de llegar al caso de uso** (`<Feature>Input.kt`, sin
   Ktor). Si el caso de uso recibe un dato sin validar, la validación no existe: **existe
   en el sitio donde se acordó de hacerla**.
3. **El contexto autenticado viaja como objeto explícito.** El **usuario autenticado**
   (`AccessUser` hoy; `Principal` es el nombre que recibe cuando se pase como parámetro)
   **se pasa como argumento**; **nunca por variable thread-local**. Una thread-local se
   pierde en cuanto el trabajo salta de hilo —y con coroutines el salto es la norma—, así
   que el permiso se comprobaría contra un contexto que ya no es el de la petición. **Un
   fallo de seguridad que solo aparece bajo concurrencia es el peor de todos: no se
   reproduce.**

```kotlin
// ✅ Explícito: se ve en la firma, y no se puede olvidar. `AccessUser` es real
// (stock-domain/.../access/AccessEngine.kt) y viaja como argumento, no en un ThreadLocal.
suspend fun execute(user: AccessUser, request: AdjustmentRequest): Either<DomainError, AdjustmentResult>

// El motor de acceso decide. Firma REAL: decide(user, permissionKey, resource, context),
// con el `ResourceContext` REAL (warehouseId, locationCode, productCode, difference…):
// son esos atributos los que alimentan las políticas ABAC.
val decision = engine.decide(
    user = user,
    permissionKey = "inventory.adjust",
    resource = ResourceContext(warehouseId = warehouseId, locationCode = locationCode),
    context = requestContext(call),
)

// DENY gana y sin decisión explícita se NIEGA (§5.4): solo `Allowed` deja pasar.
// La traducción de los otros cinco estados a error HTTP ya existe y es exhaustiva
// (`PermissionChecker.toPermissionDecision`, §1.9), así que aquí NO se repite.
if (decision !is AccessDecision.Allowed) return@post call.respondForbidden(decision)
useCase.execute(user, request)
```

**Estado real:** el contexto por objeto **ya se cumple** —
`security/TokenCall.kt` y `security/PermissionChecker.kt`, con el `AccessEngine` del
dominio aplicando RBAC + ABAC y **denegación por defecto** (§5.4). Lo que falta es
**cerrar la puerta a las thread-local**: hoy no hay ninguna, y esta regla existe para que
no se añada la primera.

### 1.14 Observabilidad: logging estructurado, MDC y métricas

**Tres reglas, y las tres son deuda:**

**1. Logging estructurado.** `logback.xml` ya está en
`backend/src/main/resources/logback.xml`. La regla: los campos van **con nombre**, no
concatenados en el mensaje, para que se puedan filtrar por ellos.

```kotlin
// ✅ Consultable: se puede filtrar por productCode.
logger.info("Ajuste aplicado productCode={} location={} delta={}", productCode, location, delta)

// ❌ Hay que hacer grep de texto libre para encontrar algo.
logger.info("Ajuste aplicado en $productCode de $location con delta $delta")
```

**2. Propagación de MDC a través de los cambios de contexto de coroutine.** Con
coroutines, el `MDC` **no viaja solo**: cada cambio de contexto puede caer en otro hilo y
el `MDC` es thread-local. Se propaga con **`MDCContext()`**:

```kotlin
withContext(Dispatchers.IO + MDCContext(mapOf("userId" to principal.userId.toString()))) {
    repository.register(command)   // aquí el MDC sigue puesto
}
```

> **Requiere `kotlinx-coroutines-slf4j`, que HOY NO ESTÁ en el classpath.** Es parte del
> trabajo de esta deuda, no un detalle de configuración.

**3. Métricas con Micrometer.** Un contador por operación y un temporizador por consulta.
Sin métricas, «va lento» es una opinión; y el `429` de §1.15 no se puede dimensionar.

> ⚠️ **ESTADO REAL VERIFICADO:** el logging existe pero es **plano y escaso** —
> `logger` aparece en **7 archivos** de `backend/src/main` (`Application`,
> `DatabaseConfig`, `IdentityRepository`, `Argon2PasswordHasher`, `CatalogImportService`,
> `ProductRoutes`, `LocationRoutes`). Y hay **CERO Micrometer, CERO MDC, CERO
> OpenTelemetry**. El `callId` / **`X-Request-Id` está prometido en las cabeceras**
> (`OpenApiDocumentation.kt`: «UUID de correlación. Se propaga a la auditoría») **y no se
> propaga**: nadie lo lee de la petición ni lo mete en el MDC. **Un identificador de
> correlación que no se propaga es peor que no tenerlo**, porque promete un rastro que no
> existe y hace perder tiempo buscándolo.

### 1.15 Resiliencia: circuit breaker, timeouts, reintentos

**Reglas:**

| Mecanismo | Para qué | Sin él… |
|---|---|---|
| **Timeout** | Toda llamada externa lleva uno | Una dependencia lenta **agota los hilos** y tumba el backend entero |
| **Circuit breaker** | Deja de llamar a lo que ya está fallando | Se insiste contra un servicio caído y **se encola el trabajo** |
| **Rate limiter** | Protege el recurso, no el cliente | Un cliente en bucle consume la base de todos |
| **Retry con backoff exponencial + jitter** | Solo para fallos **transitorios** | Sin jitter, los reintentos **se sincronizan** y llegan en manada |

**Resilience4j o equivalente, en versión coroutines**, y **nunca reintentar lo que no es
idempotente**: reintentar un movimiento de inventario duplica el movimiento.

```kotlin
// El backoff necesita JITTER: sin él, todos los clientes reintentan a la vez.
private suspend fun <T> retryWithBackoff(attempts: Int = 3, block: suspend () -> T): T {
    repeat(attempts - 1) { attempt ->
        try {
            return block()
        } catch (error: IOException) {
            val base = 100L shl attempt            // 100, 200, 400 ms
            delay(base + Random.nextLong(0, base)) // ← jitter
        }
    }
    return block()
}
```

> ⚠️ **ESTADO REAL: no hay NADA de esto.** Cero timeouts, cero circuit breaker, cero
> reintentos, cero rate limiter; `resilience4j` no está en el classpath. Lo único que
> existe es el **`429 RATE_LIMITED` en el catálogo de errores del contrato**
> (`CONTRATO_API.md` §1.3-bis) y en el documento OpenAPI: **el código de error está
> prometido y no hay nada que lo emita**. Es deuda en §8.

### 1.16 Estándares de código — la checklist obligatoria

**Cada punto se revisa antes de dar una tarea por terminada.** No es una guía de estilo:
es lo que se comprueba.

| # | Regla | El fallo que evita |
|---|---|---|
| 1 | **Visibilidad explícita** (`private`, `internal`, `public`) | Todo es `public` por defecto y cualquier archivo puede tocar cualquier cosa |
| 2 | **Inmutabilidad por defecto**: `val`, colecciones de **solo lectura** | Un `MutableList` compartido cambia «solo» desde otro sitio |
| 3 | **Tipo de retorno explícito en lo público** | Cambiar el tipo de retorno de una `fun` pública **sin tocar la firma** rompe a quien la llama |
| 4 | **Seguridad frente a nulos**: `?.`, `?:`, smart casts | El `NullPointerException` en producción |
| 5 | **`!!` PROHIBIDO** | Un `!!` es una **afirmación sin prueba**: convierte un dato ausente en una caída |
| 6 | **Funciones de extensión** para utilidades | Utilidades sueltas en clases `Utils` que nadie sabe dónde viven |
| 7 | **Inyección por constructor** (§1.8) | Dependencias escondidas y clases que no se pueden probar |
| 8 | **Todo tiene que ser TESTEABLE** | Código que solo se puede probar arrancando el servidor |

```kotlin
// ✅ Las ocho reglas, en una función: `internal`, `val`, retorno explícito,
//    null-safe, sin `!!`, y como función de extensión.
internal fun ResultSet.toStockByLocationDto(): StockByLocationDto? {
    val code: String = getString("product_code") ?: return null
    val quantity: String = getBigDecimal("quantity")?.toPlainString() ?: "0.000"
    return StockByLocationDto(productCode = code, quantity = quantity)
}

// ❌ Tres reglas incumplidas en una línea: pública sin tipo de retorno,
//    `var` que no hace falta y `!!` en lugar de `?:`.
fun toCode(rs: ResultSet) { var code = rs.getString("product_code")!! }
```

### 1.17 Testabilidad, con lo que hay

**Un test unitario (Kotest) prueba la regla de negocio aislada; un test de integración
prueba el SQL contra el motor real.** Las dos cosas hacen falta y **no son
intercambiables**.

- **Unitario: Kotest.** Ya se usa (`kotest-runner-junit5`, `kotest-assertions-core`,
  `kotest-property`, `kotest-datatest`).
- **Integración: PostgreSQL REAL** en `127.0.0.1:5432`, con bases dedicadas que se borran
  y se recrean por spec (`backend/src/test/kotlin/dev/axzy/stock/backend/catalog/CatalogTestDb.kt`).
- **Moritz (verificado):** **`MockK` NO está en el proyecto** y **`Testcontainers` NO está
  en el proyecto**. Con puertos (§1.3), un doble de prueba se escribe **a mano,
  implementando la interfaz** — que es más honesto: un `mock` programado comprueba que el
  mock hace lo que se le dijo, no que el código haga lo correcto.

### 1.18 Cumplimiento: cada regla contra el estado real

**Medido el 2026-10-07 sobre el árbol de trabajo, DESPUÉS de revertir la migración de capas.**
Las cifras se vuelven a medir cuando se cierre un hito: **una tabla de deuda sin fecha no vale
nada**, porque al mes siguiente nadie sabe si sigue siendo cierta.

| Regla | Estado verificado |
|---|---|
| Capas `domain`/`application`/`infrastructure` | **NO** — el backend es `package-by-feature`; `catalog/` ya está partido por recurso **y en subpaquetes**: `catalog/product/`, `catalog/location/`, `catalog/warehouse/` |
| `value class` para primitivas | **SÍ** en el dominio (6 tipos en `value/Values.kt`); **1** en backend (`JsonQuantity`) |
| `sealed` para jerarquías y errores | **SÍ** (`Either` y `DomainError` son `sealed`; `StockException` es una jerarquía cerrada de facto con `open class`, no `sealed`) |
| Coroutines | **SÍ** (Ktor es `suspend`) |
| `Dispatchers.IO` para JDBC bloqueante | **NO** — 0 usos de `Dispatchers.` / `withContext`; el JDBC corre en el hilo de Netty |
| Errores de negocio sin excepciones | **PARCIAL** — `Either` en el dominio; **119 `throw` de excepciones HTTP** en `backend/src/main`, **84 de ellos en rutas y servicios** (§1.11, con el comando exacto) |
| Transacción en la capa de aplicación | **SÍ en la práctica** (`dataSource.connection.use { ... commit }` en los servicios), sin capa formal donde declararla |
| `newSuspendedTransaction` (Exposed) | **NO** — el driver JDBC se usa directo (en `identity/`, Exposed `transaction(db)` **bloqueante**) |
| Contexto autenticado por objeto | **SÍ** (`security/TokenCall.kt`, `PermissionChecker`) |
| MDC + `MDCContext()` | **NO** |
| Métricas (Micrometer) | **NO** |
| Resiliencia (circuit breaker, retry, rate limit) | **NO** |
| Inyección por constructor | **SÍ** (`config/AppDependencies.kt`) |
| Tests unitarios y de integración (Kotest) | **SÍ** — 392 en `stock-domain`, **291** en `backend` = **683**, **0 fallos**, 0 saltados |
| E2E de navegador | **SÍ — 18 pruebas**: sesión, recarga, reportes con descuadre real, **el flujo de traslado completo por la interfaz**, el pendiente y el empaque del surtido, la cola de impresión, la bitácora con una denegación real, la consulta de usuarios y **las dos altas**: orden de surtido y plantilla de etiqueta |
| E2E de la API (token real, motor RBAC real, PostgreSQL migrado) | **SÍ** — `ApiE2ETest`, **15** pruebas |
| E2E de navegador (Playwright, web + backend reales) | **SÍ** — `web/e2e/flow.spec.ts`, **6** pruebas |
| Un test unitario comprueba que todo código de error emitido está en el catálogo del contrato | **SÍ** — encontró `PRODUCT_NOT_FOUND` y `LOCATION_NOT_FOUND` ausentes |
| Un e2e comprueba que la MISMA falta de token da el mismo estado en todas las rutas | **SÍ** — encontró 403 donde tocaba 401 en `inventory/` y `audit/` |
| Un e2e de navegador comprueba que recargar conserva la sesión | **SÍ** — encontró que la app se quedaba en blanco al recargar |
| MockK | **NO** está en el proyecto |
| Testcontainers | **NO**; se usa PostgreSQL real local |
| Un mapper leído por nombre de columna tiene test contra la función real | **SÍ desde 2026-10-07** — `DivergenceMapperTest`, y **se verificó que falla** al revertir el nombre de la columna a `total_en_bd` (regla §6.2) |

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

> ✅ **CORREGIDO (2026-10-08): las DENEGACIONES DE ACCESO ya se registran.**
>
> `AuditWriter.recordDeniedAccessSafely` **existía desde el principio y nadie la
> llamaba**: el método estaba escrito, la tabla tenía sus seis dimensiones y la
> bitácora quedaba **en cero filas**. Se conectó al verificador de permisos
> (`AccessEnginePermissionChecker`), así que **todo acceso denegado deja entrada**
> con quién, qué permiso, sobre qué ruta, desde qué canal, con qué IP y con qué
> decisión. Un test del e2e de la API lo fija, y **se comprobó que falla al
> desconectar el escritor**.
>
> **Por qué empezar por las denegaciones:** son el caso de seguridad. Un sistema que
> niega sin dejar constancia no puede responder a lo único que importa después de un
> incidente —cuántas veces se intentó y desde dónde—, y un ataque contra permisos es
> indistinguible de un usuario que se equivocó de pantalla.
>
> **Un fallo al auditar NO cambia la decisión.** `recordDeniedAccessSafely` usa
> `runCatching`: si la auditoría no puede escribir, **el acceso se sigue denegando**.
> Que un fallo de registro ABRIERA el acceso sería convertir un problema de
> auditoría en un agujero de seguridad.
>
> **Lo que sigue sin auditarse:** las operaciones de LECTURA y las que no cambian
> estado. El inventario y la recepción ya escribían; el resto de escrituras —alta de
> catálogo, surtido, traslados, etiquetas— **todavía no**. Es la deuda que queda, y
> ahora es una lista concreta y no una fachada.

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
   (hexagonal)     (FSD)      (vertical, build propio)
        │               │               │
        └── CONTRATO_API.md ────────────┘
             (la web y el móvil hablan HTTP)
```

**El contrato es la frontera.** La web y el móvil **no importan código del backend**:
hablan HTTP. Eso permite cambiarlo sin recompilar los clientes.

> ✅ **`CONTRATO_API.md` §9 ya NO miente, y estuvo mintiendo.** Prometía
> `productsChecked`/`ledgerNet`/`projected` y la ruta devolvía
> `cuadra`/`totalEnBd`/`totalEsperado`. Se corrigieron **las dos partes**: la
> `RETURNS TABLE` de `comprobar_proyecciones()` (migración `V8`) y el contrato. Se deja
> escrito porque **un contrato que miente es peor que no tenerlo** —el cliente se tipa
> contra él y pinta `undefined`— y porque la lección no caduca: el campo se llamaba en
> español, así que además incumplía §5.1 **en API pública**, donde el idioma no es
> cosmética.

---

## 8. Lo que NO está verificado — hay que saberlo

Un documento de arquitectura que solo dice lo que va bien no sirve para decidir.

| Qué | Estado |
|---|---|
| **Auditoría** | Las **denegaciones de acceso** se registran (2026-10-08). Inventario y recepción ya escribían. **Faltan las escrituras de catálogo, surtido, traslados y etiquetas** |
| **Migración a capas de la API** | **NO HECHA.** `catalog/` está partido por recurso (fase 2, ver el plan al final); las capas `application/` e `infrastructure/` **no existen** y `inventory/`, `receiving/`, `identity/`, `audit/`, `docs/` siguen en la raíz (§1.2) |
| **Regla de dependencias, sin guardia** | Nada impide hoy que `application/` importe Ktor, Exposed o JDBC. Se comprueba a mano (§1.3) |
| **`Dispatchers.IO` / `withContext`** | **0 usos** en `backend/src`. Todo el JDBC bloquea el hilo de Netty (§1.10) |
| **MDC, métricas y trazas** | **0 `MDC`, 0 Micrometer, 0 OpenTelemetry.** El logging es plano; el `X-Request-Id` está prometido y **no se propaga** (§1.14) |
| **Resiliencia** | **0** timeouts, circuit breaker, reintentos y rate limiter. El `429 RATE_LIMITED` existe **solo en el catálogo de errores**, sin nada que lo emita (§1.15) |
| **`domain/` propio del backend** | `domain/error/DomainFailure` **todavía no existe**; el catálogo vive en `stock-domain` (`DomainError`) |
| **iOS** | El `.xcodeproj` **nunca se abrió en Xcode**. El framework compila, el proyecto no está verificado |
| **Cámara del móvil** | Sin probar en dispositivo real |
| **Móvil ↔ API** | Los módulos `stock-network`/`stock-database`/`stock-datastore` (130 tests) **no están conectados a las pantallas** |
| **Swagger** | No existía; en implementación |
| **Escrituras sin auditar** | Inventario y recepción ya escriben su auditoría, y las **denegaciones de acceso** desde 2026-10-08. Faltan las de **catálogo, surtido, traslados y etiquetas**: hoy mueven inventario sin dejar rastro en `audit_log` |
| **Administración de acceso por interfaz** | `GET /v1/users`, `/v1/roles` y `/v1/permissions` existen y la pantalla los consulta. **No hay rutas de escritura**: crear un usuario o cambiarle los roles se hace por SQL |
| **E2E de navegador en CI** | `./scripts/e2e-web.sh` **no está en el workflow**: necesita navegador y dos servidores. Se ejecuta a mano (§9). Lo que SÍ está en CI desde el 2026-10-07 son los 212 del backend (`api-backend`), que antes **no se ejecutaban** y ahora fallan si alguno se salta |
| **E2E del móvil** | No hay ninguno. Las pantallas del móvil no están conectadas a la API (§3) |
| **Cobertura de `receiving/` e `identity/` por HTTP con RBAC real** | Parcial: `ApiE2ETest` cubre token, 401/403, catálogo, paginación y reconciliación; **no** el flujo de captura y aprobación de facturas |
| **API completa** | **SÍ, el 100% del contrato**: 57 endpoints en las 9 secciones, verificados uno a uno contra `CONTRATO_API.md` (2026-10-07) |
| **Web conectada** | **SÍ — las 11 pantallas**: Reportes, Traslados, Surtido, Etiquetas, Recepción, Inventario, Catálogo, Bitácora y Usuarios, todas contra la API real. **No queda ninguna maqueta ni placeholder.** Las altas de orden y de plantilla de etiqueta ya están en la interfaz |
| **Integración con el ERP (`/v1/orders/{id}/export`)** | **No existe.** El intento se registra y la orden pasa a `COMPLETED`, pero **no hay ningún envío real**: está marcado en el código y aquí. Fingir un fallo aleatorio sería peor —haría pasar tests que no prueban nada— |
| **Envío real a la impresora Zebra** | La **cola** está completa y probada (el trabajo nace `PENDING` y solo la impresora lo cierra), pero **nadie envía el ZPL a la impresora**: el `PATCH` lo simula el cliente. Falta el worker que habla con la Zebra |
| **Claves de servicio** | La verificación por `X-Service-Key` está implementada y probada, pero **no hay endpoint para crear una clave**: hoy se inserta por SQL con el hash calculado a mano |
| **`transfer_lines` (migración V9)** | **Nueva**: la V3 creó `transfers` sin líneas y `stock_in_transit` sin `transfer_id`, así que un traslado no podía decir qué llevaba ni atribuirse una fila de tránsito |

---

## 9. Estrategia de tests: cuatro capas, y cada una caza lo que las otras no pueden

**Añadida el 2026-10-07, después de que tres defectos reales sobrevivieran a 504
tests en verde.** No es teoría: cada capa existe porque algo se le escapó a las de
abajo.

| Capa | Dónde | Qué caza | Coste |
|---|---|---|---|
| **Unitaria pura** | `backend/…/catalog/CatalogValidationTest` | reglas de validación y **códigos de error** | milisegundos, no toca la base |
| **Integración con PostgreSQL real** | `MotorInventarioTest`, `DivergenceMapperTest` | SQL, nombres de columna, funciones PL/pgSQL | segundos, necesita Docker |
| **E2E de API** | `ApiE2ETest` | token, RBAC, serialización, contrato HTTP | segundos, base dedicada |
| **E2E de navegador** | `web/e2e/flow.spec.ts` | que la pantalla pinte de verdad | ~7 s, backend + web + navegador |

### La regla que las ordena

> **Una capa de test no sustituye a otra.** Un test unitario no puede ver un nombre
> de columna; un e2e de navegador no puede decir QUÉ campo del contrato está mal.
> Cuando algo falla, la capa que lo cazó dice dónde mirar.

### Los tres defectos que obligaron a escribir esto

**1. El mapper de la reconciliación leía columnas que el contrato publicaba.**
`DivergenceDto` se serializaba con `totalEnBd`/`totalEsperado`/`diferencia` porque
**eran los nombres del `RETURNS TABLE`** de una función PL/pgSQL. Ningún test leía
esa función: con el verificador por defecto, la ruta responde `401` **antes** del
mapper. Lo cubre ahora `DivergenceMapperTest`, que **se comprobó que falla** al
revertir el nombre de la columna.

**2. La misma falta de token daba `403` en unas rutas y `401` en otras.**
Cada feature traducía la denegación por su cuenta. Los tests de ruta usaban
`AllowAllPermissionChecker`, así que **nunca ejercitaban una denegación real**. Lo
cazó `ApiE2ETest`, que ahora recorre seis rutas y exige el mismo estado en todas.

**3. La web se quedaba EN BLANCO al recargar en una ruta autenticada.**
Dos fallos encadenados: `setSessionHooks` —el puente entre el cliente HTTP y la
sesión— **no se llamaba en ningún sitio**, así que ninguna petición llevaba el
token; y `useSessionBootstrap` cancelaba su propio resultado en el doble montaje de
`StrictMode`, dejando el estado en `loading` para siempre.

**Ningún test de los 504 anteriores podía verlo:** `tsc` compila (los ganchos tienen
valores por defecto válidos), `eslint` no ve un import que nunca se hizo, y los
tests de API no abren un navegador. Lo cazó `web/e2e/flow.spec.ts`, y hay un test
dedicado a **recargar**, porque navegar dentro de la SPA no lo mostraba.

### Cómo se ejecutan

```bash
# Unitarios y de integración (necesitan PostgreSQL: si no hay, se SALTAN y lo dicen)
./gradlew :backend:test :stock-domain:jvmTest

# E2E de API (incluido en lo anterior)
./gradlew :backend:test --tests "*ApiE2ETest*"

# E2E de navegador: levanta base dedicada, backend y web, y ejecuta Playwright
./scripts/e2e-web.sh
```

### Reglas de la casa para un test nuevo

- **Un test que se salta solo es un test que no vigila.** Si falta la base, se
  imprime el motivo; si falta una variable, el e2e **falla** diciendo qué ejecutar.
- **Un test que nunca ha fallado no demuestra que vigile nada** (§6.2). Antes de
  dar por buena una guardia, se revierte el defecto y se comprueba que sale roja.
- **Nada de `sleep` fijos para esperar a un servicio.** El script del e2e espera a
  que **el administrador sembrado pueda iniciar sesión**: es la única señal que
  significa «puedo servir». Esperar al puerto, y hasta esperar a `schemaVersion`,
  se probó y **las dos mentían** — el servidor abre el puerto y responde el health
  antes de terminar de migrar.
- **Los specs comparten base, así que van en serie** (`workers: 1`). En paralelo,
  el orden decide el resultado, que es la peor clase de fallo.

---

## Migración a capas: el plan por fases

**Estado: la partición de `catalog/` está HECHA; las capas `domain`/`application`/
`infrastructure`, NO.** Esta sección **sustituye** al «refactor pendiente: partir `catalog/`»
que había aquí, y el objetivo ya no es el mismo: **partir un directorio por features sin
capas deja intacto el problema de §1.1** —features sin sitio donde poner la regla—. El
objetivo es **la estructura de §1.2**.

> **Regla que no se negocia: la migración NO cambia comportamiento.** Es un movimiento de
> código: mismas rutas, mismas respuestas, mismos códigos de estado, mismos tests. Si una
> respuesta cambia o un test se tiene que reescribir para pasar, **la migración se salió de
> su carril** y hay que parar y separar el cambio.

### Lo que YA está hecho: `catalog/` partido por recurso (2026-10-07)

| Fichero | Antes | Ahora | Qué es |
|---|---|---|---|
| `CatalogRoutes.kt` | **741** | **65** | solo el cableado: repositorios y orden de montaje |
| `ProductRoutes.kt` | — | 206 | `GET\|POST\|PUT\|DELETE /v1/products` |
| `LocationRoutes.kt` | — | 208 | `GET\|POST\|PUT\|DELETE /v1/locations` |
| `WarehouseRoutes.kt` | — | 61 | `GET /v1/warehouses` |
| `CatalogValidation.kt` | — | 293 | los 17 helpers, `internal` |

**Cero cambios de comportamiento:** mismas rutas, mismas respuestas. Verificado con
**548 tests + 6 de navegador, 0 fallos, 0 saltados**. El único test tocado es la guardia de visibilidad que
se explica abajo, y **es un cambio de criterio, no un ajuste para que pase**.

**Éste es el patrón que hay que repetir** en `inventory/`, `receiving/`, `identity/`,
`audit/` y `health/`: **un archivo por recurso, y el de rutas se queda solo con el
cableado.** Es un movimiento mecánico, compila entre pasos y no toca SQL ni reglas.

#### Tres cosas que costaron una iteración cada una

1. **`private` de nivel superior en Kotlin es privado del FICHERO.** Al partir el archivo,
   los helpers dejan de ser accesibles desde los nuevos ficheros: hay que pasarlos a
   `internal` **en su propio archivo** (`CatalogValidation.kt`). No es un descuido de
   visibilidad: es la consecuencia obligada de partir.
2. **Las funciones de rutas también tienen que ser `internal`, no `private`.** Las llama
   `catalogRoutes()` **desde otro fichero**, así que `private` no compila. La visibilidad
   mínima que funciona es `internal`, y es la correcta: dentro del módulo, fuera de la API
   pública. Esto obligó a afinar la guardia de `RoutesWiringTest`: ahora excluye `private`
   **e `internal`**, porque las dos son implementación interna que **no** debe montar
   `Application.kt`. Si se montaran allí **además** de `catalogRoutes()`, las rutas
   quedarían registradas **dos veces** — el mismo defecto que ya hizo fallar 49 tests.
3. **Al partir el archivo se copian los 50 imports de la cabecera a cada trozo.** Funciona
   (los que no se usan son un warning, no un error), **pero los imports internos que hacen
   falta se pierden si se filtraron por prefijo**. Ver la trampa siguiente.

### La trampa que costó la reversión completa, y la lección

Se intentó migrar **todo el backend a la vez** (12.000 líneas, las 6 features) con un script
que movía ficheros y reescribía imports por **prefijo de paquete**
(`dev.axzy.stock.backend.catalog.*`). El resultado: **906 errores en 28 ficheros** y una
reversión completa.

**La causa, y es reutilizable:** un script que reescribe «todos los imports que empiecen por
X» acierta con los que apuntan a la ruta vieja y **destruye los otros dos casos** —los que
apuntan a una ruta nueva equivocada y los que se quedan huérfanos—, porque **no sabe dónde
vive cada símbolo**. Y al no saberlo, borra imports que hacían falta **sin decirlo**.

**La regla que sale de ahí:**

> **Un refactor de paquetes se hace por feature y compilando entre fases.** Y para reparar
> imports, **la única fuente fiable es el compilador**, no un mapa de prefijos: se compila,
> se leen los `Unresolved reference 'X'` con su fichero, y se resuelve `X` contra el
> inventario real de declaraciones.

**Dos sub-trampas del mismo intento, por si alguien lo repite:**

- **Un miembro de clase NO es un símbolo importable.** `val size: Int` dentro de
  `data class Page` no se puede importar. Cuando el compilador dice
  `Unresolved reference 'size'` sobre `page.size`, el problema real es que **falta el import
  de `Page`**. Tratarlo como símbolo genera imports inventados que rompen el fichero entero
  y **esconden el error de verdad**.
- **El nombre de una función de extensión es el identificador ANTERIOR al paréntesis.** En
  `fun <T, R> Page<T>.aResponse(...)` el nombre es `aResponse`, no `Page`. Un detector que
  se quede con el primer identificador atribuye `aResponse` al fichero equivocado y **borra
  un import que hacía falta**; el síntoma es el peor posible: el fichero compila y la
  llamada no resuelve.

### El punto de partida de la fase pendiente

Medido **antes** de partirlo: en `HEAD`, `catalog/` tenía **2.998 líneas en 12 archivos** y
mezclaba cuatro cosas distintas. Al partirlo quedó en **3.090 líneas en 16 archivos**: crece
un poco porque cada fichero nuevo lleva su `package`, sus imports y la explicación de por qué
existe, y porque `CatalogValidation.kt` reúne los helpers que antes estaban repartidos por el
monolito. **Partir un archivo no reduce líneas: reduce el alcance de lo que hay que leer para
tocarlo.**

```
catalog/   (ANTES)
  CatalogRoutes.kt      741  ← products + locations + warehouses en 3 `private fun Route.*`
  ProductRepository     398
  CatalogDtos           369
  LocationRepository    274
  XlsxReader            260  ← importación de Excel
  CatalogFileReader     194  ← importación de Excel
  CatalogTables         188
  Pagination            181  ← compartido con el resto del backend
  CatalogImportService  127  ← importación de Excel
  WarehouseRepository   103
  CatalogErrors         103
  UnitOfMeasureRepository 60
```

**El problema de fondo es el de §1.1, y aquí se ve entero:** `CatalogRoutes.kt` eran **741
líneas con TRES recursos dentro, y NO HAY `CatalogService`**. Las rutas hablan con **cuatro
repositorios** directamente, así que **no hay dónde poner una regla de negocio que afecte a
más de uno** — y la validación acaba repartida por los 7 handlers.

**Y hay dos cosas que no son del catálogo:** la **importación de Excel** (un proceso por
lotes, con su ciclo de vida y sus errores; no comparte nada con un `GET /v1/products`) y
`Pagination.kt` / `CatalogTables.kt`, que **los usa el resto del backend** y están en
`catalog/` por accidente histórico.

### Las fases

El orden va **de dentro hacia fuera y de lo compartido a lo particular**, porque cada fase
tiene que dejar el build compilando.

**Fase 0 — Congelar el contrato.** Antes de mover nada: `CONTRATO_API.md` y los tests de
rutas en verde. **Si el contrato y la ruta no coinciden hoy, la migración convierte la
discrepancia en un bug importado** y ya no se sabrá de dónde vino. (La de §9 del contrato ya
se corrigió con la migración `V8`; esa es la lección.)

**Fase 1 — Lo compartido sale de `catalog/`. PENDIENTE, y es la de más valor por línea
movida.** `Pagination.kt` y `CatalogTables.kt` salen **los primeros** porque los usa todo el
backend: `Page`, `PageRequest` y los resultados de consulta van a `application/shared/`, y
las tablas Exposed a `infrastructure/outbound/persistence/tables/`. **Hoy `identity/`,
`audit/` e `inventory/` importan `Pagination` DEL CATÁLOGO**, que es acoplamiento accidental
entre features: un módulo depende de otro por un detalle de paginación.

**Fase 2 — `catalog/`, recurso a recurso. Mitad hecha** (la partición, ver arriba). Falta la
otra mitad: crear `ProductService`/`LocationService`/`WarehouseService` y que las rutas
deleguen. Es lo que llena el hueco de «no hay dónde poner una regla que afecte a más de un
recurso».

**Fase 3 — `inventory/` y `receiving/`.** Son los que llevan el motor PL/pgSQL y su propia
transacción (§1.12). **Se mueven tal cual.** El patrón
`dataSource.connection.use { autoCommit = false; ... commit() }` **no se reescribe al
migrar**: formalizarlo es otra tarea. Si se «aprovecha» para cambiarlo, **se pierde la
capacidad de atribuir un fallo** — no se sabrá si lo rompió el movimiento o el cambio.

**Fase 4 — Transversales.** `identity/`, `audit/`, `docs/`, `health/`, `tools/`, y el
`domain/` propio del backend (`domain/model/` y `domain/error/DomainFailure`), que **hoy no
existe** (§8; el catálogo de errores vive en `stock-domain`).

**Fase 5 — `Application.kt` y el composition root.** Se cierra la composición (§1.8) y se
comprueba que **no queda ningún directorio de feature en la raíz del paquete**.

**Fase 6 — La guardia.** Una tarea de Gradle que **falle** si `domain/` o `application/`
importan Ktor, Exposed, JDBC o HikariCP —y, en el caso de uso, `kotlinx.serialization`—
(§1.3), al estilo de `verifyDomainPurity` y `forbidJvmMainSourceSet` de
`stock-domain/build.gradle.kts`. **Sin guardia, la regla se degrada sola**: es exactamente
la lección que dejó vertical slice.

### Trampas que ya me encontré al intentarlo (y siguen siendo ciertas)

- **Los 17 helpers de validación eran `private` de nivel superior** (`page`, `routeId`,
  `productCode`, `textRequired`, `locationPart`, `parseLocationKind`…). Ya están en
  `CatalogValidation.kt` con `internal`, que es donde tienen que estar.
- **`requirePermission` era `private`** y lo usan los tres recursos. Mismo tratamiento.
- **Cortar por número de línea NO funciona**: el primer intento se llevó las tres funciones
  enteras a `CatalogRoutes.kt` (overloads duplicados) y el segundo cortó los helpers por la
  mitad (error de sintaxis en la línea 257). **Hay que cortar por el cierre real de cada
  función**, no por un rango fijo.
- **Los 50 imports de la cabecera.** Cada archivo nuevo necesita los suyos; copiarlos todos
  deja imports sin usar (warning, no error), **pero los internos que hacen falta se pierden
  si se filtraron por prefijo** — ver «la trampa que costó la reversión».

### Cómo se sabe que la migración terminó

1. **Cero directorios de feature** en la raíz de `backend/src/main/kotlin/dev/axzy/stock/backend/`.
2. El `grep` de §1.3 sobre `domain/` y `application/` devuelve **vacío**.
3. La guardia de la fase 6 **existe y falla cuando se la provoca a propósito** (§6): se mete
   un `import io.ktor` en `application/` y la tarea tiene que romper.
   **Una guardia que nunca ha fallado no demuestra que vigile nada.**
4. **683 tests + 18 de navegador, 0 fallos, 0 saltados**, y **ninguno reescrito** para que pase.
