# API — Clean Architecture por feature (`backend/`)

> **Normativo.** Arquitectura, reglas de dependencia y estándares de la API Ktor. Si el
> código contradice algo de aquí, uno de los dos está mal. Última verificación: 2026-10-09.

La decisión y su motivo están en
[ADR 0001](../adr/0001-clean-architecture-en-la-api.md).

## 1.1 La arquitectura: Clean Architecture (hexagonal)

La API se organiza en **capas horizontales dentro de cada feature**: `infrastructure →
application → domain`. La unidad de organización es la **feature**, y dentro de ella la
**capa**.

El motivo es concreto: **sin la regla de dependencias escrita, cada archivo decide por su
cuenta.** Nada impide que una ruta importe el driver JDBC o que un DTO importe Ktor, y el
diseño se degrada sin que nadie tome la decisión. Los tres síntomas que esto produce:

- **La lógica de negocio queda atrapada en las rutas.** Una regla que afecte a más de un
  repositorio no tiene dónde vivir y acaba repartida por los handlers. El caso medido es
  `catalog/`: `CatalogRoutes.kt` reunía tres recursos y no existía ningún `CatalogService`
  (`backend/src/main/kotlin/dev/axzy/stock/backend/catalog/CatalogRoutes.kt`).
- **Sin puertos no hay dobles de prueba.** Si el caso de uso pide un `DataSource` en vez
  de exigir una interfaz, la única prueba posible corre contra PostgreSQL. Eso vale como
  test de integración, pero deja la regla de negocio **sin cubrir en aislamiento**.
- **El acoplamiento no se nota al escribir, sino al probar.** Un caso de uso que importa
  `java.sql.Connection` no se puede probar sin base de datos, y un `domain/` que importa
  `io.ktor.http.HttpStatusCode` deja de poder reutilizarse fuera del servidor.

**Lo que se conserva de vertical slice es la localidad:** las capas van **dentro de la
feature**, nunca en carpetas globales del tipo `services/` y `repositories/`. Es Clean
Architecture **sin volver al cajón por tipo de archivo**.

**Esta arquitectura es solo de la API.** La web sigue en FSD ([web-fsd.md](web-fsd.md)) y
la app en vertical slice ([app-kmp.md](app-kmp.md)).

## 1.2 La estructura: capas DENTRO de la feature

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

> ⚠️ **Estado real: este árbol es la forma OBJETIVO y no existe todavía.** El backend sigue
> siendo `package-by-feature`, con una excepción: `catalog/` está partido por recurso **y en
> subpaquete por recurso** (`catalog/product/`, `catalog/location/`, `catalog/warehouse/`).
> Las demás features siguen en la raíz del paquete, con sus rutas, servicios y tablas en la
> misma carpeta. **`domain/`, `application/` e `infrastructure/` como carpetas no existen.**
> Lo que sí se cumple es la **dirección de dependencias** de §1.3, que es la regla que
> importa. El plan y las fases están en [migracion-capas.md](migracion-capas.md); lo que
> falta, en [lo-no-verificado.md](../guia/lo-no-verificado.md).
>
> **Los bloques de código con rutas `application/...` o `infrastructure/...` muestran la
> forma OBJETIVO**, no archivos que existan. Cuando un ejemplo es código real, se dice de
> qué archivo sale. Una arquitectura que solo se puede leer en el código que ya existe no
> sirve para migrar nada.

**`routes/` no existe, y es deliberado.** Una estructura con `inventory/InventoryService.kt`
por un lado y `routes/InventoryRoutes.kt` por otro obliga a saltar entre dos sitios para
tocar una feature, y convierte `routes/` en un cajón de archivos sin relación entre sí: no
es agrupar por capas ni por features, es lo peor de los dos. En la estructura objetivo el
HTTP vive **con su feature** (`infrastructure/inbound/http/<feature>/`): cambia la capa,
no la localidad.

## 1.3 La regla de dependencias — la única que no se negocia

> **`infrastructure` → `application` → `domain`. Nunca al revés. Nunca un salto.**

| Capa | Puede importar | **Prohibido** importar |
|---|---|---|
| `domain/` | Kotlin stdlib, `stock-domain` | **Ktor, Exposed, JDBC, HikariCP, `kotlinx.serialization`** |
| `application/` (caso de uso y puertos) | `domain/`, stdlib, `kotlinx-coroutines` | **Ktor, Exposed, JDBC, HikariCP, `kotlinx.serialization`** |
| `application/` (DTOs de la feature) | `domain/`, stdlib, `kotlinx.serialization` | **Ktor, Exposed, JDBC, HikariCP** |
| `infrastructure/` | todo lo anterior, Ktor, Exposed, JDBC, HikariCP | — |

**Por qué el caso de uso no puede llevar `@Serializable`.** Los DTOs de la feature son la
**forma del contrato** y por eso sí pueden anotarse; el archivo del caso de uso es la
**regla de negocio**, y anotarlo lo ata a la representación HTTP. El día que haya que
devolver lo mismo por otro canal, el caso de uso arrastraría la serialización encima. **La
forma de la respuesta es de la capa HTTP; la decisión, del caso de uso.**

**Estado de la guardia:** la regla **no tiene guardia automática** todavía. Nada impide hoy
que `application/` importe Ktor. Se verifica a mano con:

```bash
grep -rn "import io.ktor\|import org.jetbrains.exposed\|import java.sql\|import com.zaxxer" \
  backend/src/main/kotlin/dev/axzy/stock/backend/application \
  backend/src/main/kotlin/dev/axzy/stock/backend/domain
```

Salida esperada: **vacía**. Añadir esa comprobación como tarea de Gradle —al estilo de
`verifyDomainPurity` de `stock-domain/build.gradle.kts`— es la fase final del plan de
migración ([migracion-capas.md](migracion-capas.md)).

## 1.4 Qué va en cada sitio, con el criterio

| Si es… | Va en… |
|---|---|
| Un endpoint HTTP, la validación de forma y los códigos de estado | `infrastructure/inbound/http/<feature>/<Feature>Routes.kt` |
| Una regla de negocio, un caso de uso | `application/<feature>/<Feature>UseCase.kt` |
| Lo que el caso de uso **exige** del exterior (interfaz, sin implementación) | `application/<feature>/<Feature>Ports.kt` |
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

**El error más común es el contrario:** meter en `security/` o en `error/` algo que solo usa
una feature «porque es de seguridad». Si solo lo usa inventario, va dentro de `inventory/`,
en la capa que le toque.

## 1.5 Las capas dentro de una feature

```
Routes     → valida la forma, comprueba el permiso, llama al caso de uso
   ↓
UseCase    → la regla de negocio, el ámbito transaccional, los errores tipados
   ↓
Ports      → lo que el caso de uso EXIGE (interfaces, sin implementación)
   ↓
Repository → el SQL, la tabla, el cliente externo (implementa el puerto)
```

**Las rutas no contienen lógica de negocio.** No calculan, no deciden, no escriben SQL.
Validan la forma, autorizan, delegan y traducen a HTTP.

**Por qué importa:** cuando la regla vive en el caso de uso se prueba sin arrancar un
servidor. Cuando vive en la ruta, solo se prueba por HTTP y se olvida.

**Los puertos se declaran donde se exigen, no donde se implementan.** El puerto vive en
`application/` porque es el caso de uso quien lo pide; la implementación vive en
`infrastructure/`. Si el puerto viviera junto al repositorio, la aplicación volvería a
depender de la infraestructura, que es justo el acoplamiento que §1.3 prohíbe.

## 1.6 Las piezas de una feature, y qué pasa si falta una

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

**MVVM no aplica a una API.** MVVM es un patrón de UI: el ViewModel existe para sostener el
estado de una pantalla y sobrevivir a la rotación. En una API no hay View ni estado de
pantalla. Lo que aplica es el patrón de capas de §1.5. En el móvil sí se usa MVVM
([app-kmp.md](app-kmp.md)).

## 1.7 Los mappers, y por qué no son burocracia

Un mapper es **una función de extensión sobre `ResultSet`** que construye el DTO:

```kotlin
// Código real de backend/src/main/kotlin/dev/axzy/stock/backend/inventory/InventoryMappers.kt.
internal fun ResultSet.toStockByLocationDto(): StockByLocationDto = StockByLocationDto(
    productCode = getString("product_code"),
    quantity = getBigDecimal("quantity").toPlainString(),
    // El nombre visible no se lee de una columna: se compone aquí. La base guarda
    // `hall`, `code` y `position` por separado y el operador ve una sola etiqueta.
    locationDisplayName = locationDisplayName(
        getString("hall"),
        getString("code"),
        getString("position"),
        getString("kind"),
    ),
)
```

**Sin mappers, el mapeo acaba dentro del bucle de lectura de la consulta**, mezclado con el
`WHERE`, el `LIMIT` y el `ORDER BY`. Para saber qué forma tiene la respuesta hay que leer el
método entero, y si cambia una columna hay que encontrarla en todos los sitios donde se
mapea.

**Trampa que el compilador no detecta:** el mapper lee por **nombre de columna en una
cadena**. `getString("product_code")` compila igual si el SQL dice `AS productCode`, y
devuelve `null` en silencio. **Un mapper mal escrito no rompe: miente.** Por eso los mappers
se prueban contra la respuesta real además de con tests (§1.17).

## 1.8 Composición: dependencias por constructor, nunca `object`

Las dependencias se construyen **una vez** y se **inyectan por constructor**. El composition
root es `Application.kt` y `config/AppDependencies.kt`
(`backend/src/main/kotlin/dev/axzy/stock/backend/config/AppDependencies.kt`):

```kotlin
class AppDependencies(private val dataSource: DataSource) {
    val auditWriter: AuditWriter by lazy { AuditWriter(dataSource) }
    val inventoryService: InventoryService by lazy { InventoryService(dataSource, auditWriter) }
    val receivingService: ReceivingService by lazy { ReceivingService(dataSource, auditWriter) }
}
```

**`by lazy` y no `val` a secas**, porque si un servicio depende de otro, el orden de las
líneas decide si la dependencia ya existe: reordenar dos propiedades rompería la aplicación
sin tocar la lógica. Con `lazy` cada dependencia se construye la primera vez que se pide, y
sigue siendo única.

**Nunca `object`**: un `object` de Kotlin no se puede sustituir en un test ni acepta un
`DataSource`. Un `object` que guarda estado, además, se comparte entre peticiones.

**Koin ya está en el classpath** (`libs.koin.ktor`, `gradle/libs.versions.toml`) y **hoy no
se usa**: el cableado es a mano con `by lazy`. La regla es **inyección por constructor en
las dos formas**; el contenedor es un detalle de `config/` y no debe filtrarse a
`application/`. Cuando el cableado crezca, Koin entra **en el composition root**, no en las
clases: un caso de uso que recibe su repositorio por constructor se prueba igual con Koin o
sin él.

## 1.9 Modelado defensivo: tipos que no se pueden construir mal

Si puedes construir un objeto inválido, el tipo está mal diseñado. Es la regla de
[dominio-kmp.md](dominio-kmp.md) aplicada al backend.

**1. `@JvmInline value class` para las primitivas del dominio.** Un `Long` que es un
identificador de producto y un `Long` que es una cantidad son el mismo tipo para el
compilador y cosas distintas para el negocio: intercambiarlos compila. Un `value class` lo
impide sin coste en tiempo de ejecución (por debajo es un `Long`).

```kotlin
// ✅ El compilador no deja pasar un Quantity donde va un ProductCode.
fun adjust(productId: Id<Product>, quantity: Quantity): Adjustment

// ❌ Los dos son Long: el orden invertido compila y descuadra el inventario.
fun adjust(productId: Long, quantity: Long)
```

> ⚠️ **En `stock-domain` (KMP) la anotación tiene que ir calificada: `@kotlin.jvm.JvmInline`.**
> Sin calificar, el dominio compila en JVM y **rompe el build de iOS** con «Unresolved
> reference 'JvmInline'». `stock-domain/build.gradle.kts` lo comprueba en `verifyDomainPurity`.
> En `backend/`, que es JVM puro, `@JvmInline` sin calificar sí vale.

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

// backend/src/main/kotlin/dev/axzy/stock/backend/catalog/CatalogDtos.kt (JVM puro: sin calificar)
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
// stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/AccessEngine.kt
sealed interface AccessDecision { /* Allowed, NotAuthenticated, ScopeDenied, PolicyDenied, PolicyRequired, SegregationOfDuties */ }

// Código real: backend/src/main/kotlin/dev/axzy/stock/backend/security/PermissionChecker.kt.
// Sin `else`: si se añade un estado al motor, esto deja de compilar y hay que decidir qué se responde.
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
`DomainError` y `AccessDecision`. **Un `when` sin `else` es la red de seguridad**: el día que
se añade un caso, es el compilador el que encuentra los sitios a revisar, no un usuario en
producción.

**3. Constructores privados con fábricas.** El constructor se cierra y solo se entra por una
fábrica que valida (`Quantity private constructor(...)`, arriba). El corolario es literal:
*si puedes construir un objeto inválido, el tipo está mal diseñado*.

## 1.10 Concurrencia: coroutines, no hilos

**La regla:** todo es `suspend` de arriba abajo; **nada de `Thread`, `Thread.sleep` ni
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

> ⚠️ **Estado real: cero usos de `Dispatchers.` y cero `withContext` en `backend/src`.** Todo
> el JDBC es bloqueante y corre **sobre el hilo de Netty**. Bajo carga sostenida eso agota el
> pool del motor y el servidor deja de responder aunque la base esté sana. Es deuda con plan
> en [migracion-capas.md](migracion-capas.md) y en
> [lo-no-verificado.md](../guia/lo-no-verificado.md); el cambio toca cada servicio y se hace
> con las capas ya migradas.

### 1.10-bis Hilos virtuales: la puerta que hoy está cerrada

**Si algún día se sube a Java 21+**, valorar **hilos virtuales** para las tareas síncronas:
un hilo virtual bloqueado en JDBC **no ocupa un hilo del sistema**, así que el problema de
§1.10 se puede resolver sin reescribir el acceso a datos a coroutines.

**Hoy no se puede usar, y el motivo es concreto:** el toolchain es **JVM 17**
(`backend/build.gradle.kts` → `jvmToolchain(17)`). Los hilos virtuales son finales desde
Java 21; en 17 no existen de forma estable. **Subir el toolchain es una decisión aparte con
su propia verificación**: primero se migran las capas.

## 1.11 Errores: un fallo esperado se devuelve, no se lanza

> **Un error esperado de negocio no se lanza: se devuelve.**

`Either` existe en `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/common/Either.kt`,
con `Right` y `Left`, y **se usa en el dominio**. El motivo está escrito allí: se usa un tipo
propio en lugar de `kotlin.Result` porque `Result` obliga a que el error sea un `Throwable`,
y **«la cantidad excede la factura» no es excepcional**. Modelarlo como excepción lleva al
`try/catch` por todas partes que hacía el legacy, y con él a los errores silenciados.

```kotlin
// ✅ El caso de uso declara los fallos en el tipo de retorno: quien lo llama
// NO puede olvidarse de tratarlos.
suspend fun execute(request: AdjustmentRequest): Either<DomainError, AdjustmentResult>

// ❌ El fallo viaja escondido en el tipo de retorno `Unit` y solo se ve en ejecución.
suspend fun execute(request: AdjustmentRequest)
```

**Los `try/catch` se encapsulan en el borde** —el repositorio o el cliente externo— y allí
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

> **`DomainFailure` es el tipo de fallo propio del backend y hoy NO existe.** El catálogo
> actual, `DomainError` en `stock-domain`, solo tiene errores **de negocio**. Hace falta
> porque un fallo de almacenamiento **no es una regla de negocio**, y meterlo en
> `DomainError` obligaría al dominio a saber que existe una base de datos. Esa distinción es
> la que permite traducir un `SQLException` **sin mentir** sobre qué ha pasado.

**Excepción legítima:** un fallo **de programación** (estado imposible, `when` sin cubrir) sí
se lanza. Eso no es negocio, es un bug, y tiene que verse.

> ⚠️ **Estado real: la regla se cumple a medias.** En el dominio sí se usa `Either`, y la
> traducción a fallo tipado existe, pero **convive con el lanzamiento** de excepciones HTTP
> (`BadRequestException`, `UnauthorizedException`, `UnprocessableException`,
> `ConflictException`, `NotFoundException`, `ForbiddenException`) dentro de rutas y
> servicios. Mientras convivan, la firma de un caso de uso no dice la verdad sobre lo que
> puede fallar. Se mide con:
>
> ```bash
> grep -rn "throw .*Exception" backend/src/main --include=*.kt | wc -l
> ```

La decisión está en [ADR 0005](../adr/0005-errores-devuelto-no-lanzado.md).

## 1.12 Persistencia y transacciones

**El ámbito transaccional se define en la capa de aplicación —el caso de uso—, no en las
rutas.** Es el caso de uso quien sabe **qué operaciones tienen que ser atómicas**; la ruta
solo sabe de HTTP. Si la transacción se abriera en la ruta, media regla de negocio quedaría
fuera de la transacción que la protege.

**Si se usa Exposed, `newSuspendedTransaction`.** Es la puerta de Exposed para coroutines: no
bloquea el hilo mientras dura la transacción.

**Pero hay una particularidad verificada que manda sobre la regla general:** el **motor de
inventario y la escritura de auditoría viven en funciones PL/pgSQL de PostgreSQL** porque un
movimiento tiene que ajustar las ubicaciones, recalcular el total y escribir el kardex en una
sola transacción, y la cadena de hash de auditoría necesita un `pg_advisory_xact_lock` para
no bifurcarse:

```
V6  backend/src/main/resources/db/migration/V6__unassigned_location_and_inventory_engine.sql
    aplicar_movimiento_a_ubicacion · recalcular_total_producto · registrar_movimiento
V7  backend/src/main/resources/db/migration/V7__audit_write_and_verify.sql
    audit_log_record  ← serializa la inserción con pg_advisory_xact_lock(hashtext('audit_log_chain'))
V8  backend/src/main/resources/db/migration/V8__english_reconciliation_columns.sql
    comprobar_proyecciones()  ← los nombres de columna pasaron a inglés
```

**Es decir: la transacción la abre el caso de uso y el SQL la ejecuta.** El caso de uso abre
la conexión, fija `autoCommit = false`, llama a las funciones y hace `commit` o `rollback`.
**Eso no se mueve a Kotlin.** Si el ajuste de ubicaciones, el recálculo y el kardex se
hicieran desde Kotlin, un fallo a mitad dejaría el inventario descuadrado y nadie lo sabría
hasta el conteo físico; y una cadena de hash escrita desde fuera de la transacción se
bifurca en cuanto dos peticiones coinciden.

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

**Consecuencia práctica:** el kardex es **append-only** y las proyecciones se pueden
comprobar en cualquier momento. Eso es lo que el legacy no podía hacer.

> ⚠️ **Estado real: la regla se cumple en la práctica, sin formalizar.** El patrón
> `dataSource.connection.use { ... connection.commit() }` está **en los servicios**
> (`InventoryService`, `ReceivingService`, `IdentityAdminService`), que es el sitio correcto
> —la capa de aplicación— pero todavía dentro de la estructura vertical. **Al migrar se
> arrastra tal cual, no se reescribe.**
>
> **`newSuspendedTransaction` no se usa: cero apariciones.** El driver JDBC se usa directo.
> En `identity/`, Exposed **sí** se usa con su `transaction(db) {}` **bloqueante**
> (`backend/src/main/kotlin/dev/axzy/stock/backend/identity/IdentityRepository.kt`), que es
> justo lo que §1.10 señala como deuda.

## 1.13 Seguridad: mínimo privilegio y contexto explícito

1. **Mínimo privilegio.** Cada endpoint exige **el permiso que necesita**, no el rol.
2. **Toda entrada se valida antes de llegar al caso de uso** (`<Feature>Input.kt`, sin Ktor).
   Si el caso de uso recibe un dato sin validar, la validación no existe: existe en el sitio
   donde se acordó de hacerla.
3. **El contexto autenticado viaja como objeto explícito.** El **usuario autenticado**
   (`AccessUser`, `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/AccessEngine.kt`)
   **se pasa como argumento**; **nunca por variable thread-local**. Una thread-local se pierde
   en cuanto el trabajo salta de hilo —y con coroutines el salto es la norma—, así que el
   permiso se comprobaría contra un contexto que ya no es el de la petición. **Un fallo de
   seguridad que solo aparece bajo concurrencia no se reproduce.**

```kotlin
// ✅ Explícito: se ve en la firma, y no se puede olvidar.
suspend fun execute(user: AccessUser, request: AdjustmentRequest): Either<DomainError, AdjustmentResult>

// El motor de acceso decide. Firma real: decide(user, permissionKey, resource, context),
// con el `ResourceContext` real (warehouseId, locationCode, productCode, difference…):
// son esos atributos los que alimentan las políticas ABAC.
val decision = engine.decide(
    user = user,
    permissionKey = "inventory.adjust",
    resource = ResourceContext(warehouseId = warehouseId, locationCode = locationCode),
    context = requestContext(call),
)

// DENY gana y sin decisión explícita se NIEGA: solo `Allowed` deja pasar.
// La traducción de los otros cinco estados a error HTTP ya existe y es exhaustiva
// (PermissionChecker.toPermissionDecision, §1.9), así que aquí NO se repite.
if (decision !is AccessDecision.Allowed) return@post call.respondForbidden(decision)
useCase.execute(user, request)
```

**Estado real:** el contexto por objeto **ya se cumple** —
`backend/src/main/kotlin/dev/axzy/stock/backend/security/TokenCall.kt` y
`security/PermissionChecker.kt`, con el `AccessEngine` del dominio aplicando RBAC + ABAC y
**denegación por defecto**. Lo que falta es **cerrar la puerta a las thread-local**: hoy no
hay ninguna, y esta regla existe para que no se añada la primera.

Las reglas de acceso están en [roles-permisos.md](../seguridad/roles-permisos.md) y su
decisión en [ADR 0006](../adr/0006-rbac-mas-abac-denegar-por-defecto.md).

## 1.14 Observabilidad: logging estructurado, MDC y métricas

**Tres reglas, y las tres son deuda:**

**1. Logging estructurado.** `backend/src/main/resources/logback.xml` ya está. La regla: los
campos van **con nombre**, no concatenados en el mensaje, para que se puedan filtrar por
ellos.

```kotlin
// ✅ Consultable: se puede filtrar por productCode.
logger.info("Ajuste aplicado productCode={} location={} delta={}", productCode, location, delta)

// ❌ Hay que hacer grep de texto libre para encontrar algo.
logger.info("Ajuste aplicado en $productCode de $location con delta $delta")
```

**2. Propagación de MDC a través de los cambios de contexto de coroutine.** Con coroutines,
el `MDC` **no viaja solo**: cada cambio de contexto puede caer en otro hilo y el `MDC` es
thread-local. Se propaga con **`MDCContext()`**:

```kotlin
withContext(Dispatchers.IO + MDCContext(mapOf("userId" to principal.userId.toString()))) {
    repository.register(command)   // aquí el MDC sigue puesto
}
```

> **Requiere `kotlinx-coroutines-slf4j`, que hoy no está en el classpath.** Es parte del
> trabajo de esta deuda, no un detalle de configuración.

**3. Métricas con Micrometer.** Un contador por operación y un temporizador por consulta. Sin
métricas, «va lento» es una opinión; y el `429` de §1.15 no se puede dimensionar.

> ⚠️ **Estado real: el logging existe pero es plano y escaso** — `logger` aparece en
> `Application.kt`, `config/DatabaseConfig.kt`, `identity/IdentityRepository.kt`,
> `identity/NativeArgon2PasswordHasher.kt`, `catalog/CatalogImportService.kt`,
> `catalog/product/ProductRoutes.kt` y `catalog/location/LocationRoutes.kt`. Y hay **cero
> Micrometer, cero MDC, cero OpenTelemetry**. El `callId` / **`X-Request-Id` está prometido
> en las cabeceras** (`backend/src/main/kotlin/dev/axzy/stock/backend/docs/OpenApiDocumentation.kt`:
> «UUID de correlación. Se propaga a la auditoría») **y no se propaga**: nadie lo lee de la
> petición ni lo mete en el MDC. **Un identificador de correlación que no se propaga es peor
> que no tenerlo**, porque promete un rastro que no existe y hace perder tiempo buscándolo.

## 1.15 Resiliencia: circuit breaker, timeouts, reintentos

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

> ⚠️ **Estado real: no hay nada de esto.** Cero timeouts, cero circuit breaker, cero
> reintentos, cero rate limiter; `resilience4j` no está en el classpath. Lo único que existe
> es el **`429 RATE_LIMITED` en el catálogo de errores** y en el documento OpenAPI: **el
> código de error está prometido y no hay nada que lo emita.**

## 1.16 Estándares de código

La checklist obligatoria de estándares de código vive en
[convenciones.md](../guia/convenciones.md) para no duplicarla. Se revisa en cada parte, no
solo en la API.

## 1.17 Testabilidad, con lo que hay

**Un test unitario (Kotest) prueba la regla de negocio aislada; un test de integración prueba
el SQL contra el motor real.** Las dos cosas hacen falta y **no son intercambiables**. Los
niveles y su orden están en [estrategia-pruebas.md](../pruebas/estrategia-pruebas.md).

- **Unitario: Kotest.** Ya se usa (`kotest-runner-junit5`, `kotest-assertions-core`,
  `kotest-property`, `kotest-datatest`, `gradle/libs.versions.toml` y
  `backend/build.gradle.kts`).
- **Integración: PostgreSQL real** en `127.0.0.1:5432`, con bases dedicadas que se borran y
  se recrean por spec
  (`backend/src/test/kotlin/dev/axzy/stock/backend/catalog/CatalogTestDb.kt`).
- **`MockK` no está en el proyecto y `Testcontainers` tampoco.** Con puertos (§1.3), un doble
  de prueba se escribe **a mano, implementando la interfaz** — que es más honesto: un `mock`
  programado comprueba que el mock hace lo que se le dijo, no que el código haga lo correcto.

## 1.18 Cumplimiento: cada regla contra el estado real

Esta tabla se vuelve a medir cuando se cierra un hito: **una tabla de deuda sin fecha no vale
nada**, porque al mes siguiente nadie sabe si sigue siendo cierta.

| Regla | Estado verificado |
|---|---|
| Capas `domain`/`application`/`infrastructure` | **NO** — el backend es `package-by-feature`; `catalog/` ya está partido por recurso **y en subpaquetes**: `catalog/product/`, `catalog/location/`, `catalog/warehouse/` |
| `value class` para primitivas | **SÍ** en el dominio (`Id`, `ProductCode`, `LocationCode`, `Quantity`, `BoxQuantity`, `UnitsPerBox` en `value/Values.kt`); **1** en backend (`JsonQuantity`) |
| `sealed` para jerarquías y errores | **SÍ** (`Either` y `DomainError` son `sealed`; `StockException` es una jerarquía cerrada de facto con `open class`, no `sealed`) |
| Coroutines | **SÍ** (Ktor es `suspend`) |
| `Dispatchers.IO` para JDBC bloqueante | **NO** — 0 usos de `Dispatchers.` / `withContext`; el JDBC corre en el hilo de Netty |
| Errores de negocio sin excepciones | **PARCIAL** — `Either` en el dominio; convive con `throw` de excepciones HTTP en rutas y servicios (§1.11, con el comando exacto) |
| Transacción en la capa de aplicación | **SÍ en la práctica** (`dataSource.connection.use { ... commit }` en los servicios), sin capa formal donde declararla |
| `newSuspendedTransaction` (Exposed) | **NO** — el driver JDBC se usa directo (en `identity/`, Exposed `transaction(db)` **bloqueante**) |
| Contexto autenticado por objeto | **SÍ** (`security/TokenCall.kt`, `security/PermissionChecker.kt`) |
| MDC + `MDCContext()` | **NO** |
| Métricas (Micrometer) | **NO** |
| Resiliencia (circuit breaker, retry, rate limit) | **NO** |
| Inyección por constructor | **SÍ** (`config/AppDependencies.kt`) |
| Tests unitarios, de integración y e2e | **SÍ** — niveles, comandos y reglas en [estrategia-pruebas.md](../pruebas/estrategia-pruebas.md) |
| MockK | **NO** está en el proyecto |
| Testcontainers | **NO**; se usa PostgreSQL real local |
| Un mapper leído por nombre de columna tiene test contra la función real | **SÍ** — `backend/src/test/kotlin/dev/axzy/stock/backend/inventory/DivergenceMapperTest.kt`, que cubre el nombre de columna real de la función PL/pgSQL (regla de [verificacion.md](../pruebas/verificacion.md)) |
