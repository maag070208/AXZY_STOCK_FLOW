# API: Kotlin con Spring Boot, en capas e inyección por constructor
> **Normativo.** Cómo está organizada la API. Decisiones en [ADR-0014](../adr/0014-api-spring-boot-clean-architecture.md) y [ADR-0015](../adr/0015-postgresql-flyway-motor-en-sql.md). Cada afirmación cita el archivo que la sostiene. Última revisión: 2026-10-10 (fase 4).

## 1. Módulos y dependencias

La API es un build de Gradle con seis módulos (`settings.gradle.kts`). Cada módulo es una capa, y la
dependencia solo apunta hacia el dominio:

| Módulo | Contiene | Puede depender de | No puede depender de |
|---|---|---|---|
| `stock-domain` | Reglas, estados, errores, motor de acceso. Compartido con la app (D6) | Kotlin stdlib, kotlinx-datetime, kotlinx-serialization | Cualquier framework |
| `api/application` | Casos de uso y puertos | `stock-domain` | Spring, JDBC, JSON, adaptadores |
| `api/adapters-persistence` | Los puertos de salida sobre PostgreSQL | `application`, Spring JDBC, Jackson | `adapters-web`, `adapters-security` |
| `api/adapters-security` | Criptografía: JWT, Argon2id, refresh tokens | `application`, Spring Security crypto y JOSE | `adapters-web`, `adapters-persistence` |
| `api/adapters-web` | Controladores y contrato de error | `application`, Spring Web | `adapters-persistence`, `adapters-security` |
| `api/app` | Arranque, cableado y seguridad HTTP | Todos los anteriores | — |

```
                 ┌──→ adapters-web ─────────┐
app ─────────────┼──→ adapters-persistence ─┼──→ application ──→ stock-domain
                 └──→ adapters-security ────┘
```

Los tres adaptadores **no se conocen entre sí**. Un controlador nunca llama a un repositorio: llama a
un caso de uso, y el caso de uso llama a un puerto.

No hay un módulo `domain` propio de la API: con el dominio compartido (D6), `stock-domain` **es** la
capa de dominio. Es Kotlin Multiplatform y se compila a bytecode de Java 17 para que también lo cargue
Android (`stock-domain/build.gradle.kts`).

### Cómo se verifica

- **El compilador**: cada `build.gradle.kts` declara solo lo que su capa puede ver
  (`api/*/build.gradle.kts`). Una dependencia prohibida no compila.
- **`verifyDomainPurity`** (`stock-domain/build.gradle.kts`): falla si el dominio declara o importa un
  framework.
- **`ArchitectureTest`** (`api/app/src/test/kotlin/dev/axzy/stock/app/ArchitectureTest.kt`): siete
  reglas sobre las clases compiladas. Repite lo anterior por si entra una dependencia transitiva.

## 2. Inyección de dependencias

**Regla: los objetos se crean en un solo lugar, `api/app`, y se pasan por constructor.**

- Los casos de uso y los adaptadores son clases Kotlin **sin anotaciones de Spring**. Reciben lo que
  necesitan en el constructor.
- Spring solo explora el paquete `dev.axzy.stock.app` (`StockApplication.kt`). Nada de otra capa se
  registra por llevar una anotación: todo es un `@Bean` explícito en `api/app/.../config/`.
- Las únicas clases fuera de `app` con anotaciones de Spring son los controladores
  (`@RestController`) y el manejador de errores (`@RestControllerAdvice`), y aun así los instancia `app`.
- `ArchitectureTest` falla si aparece un `@Configuration` o un `@Bean` fuera de `app`.

| Archivo de `api/app/.../config/` | Qué cablea |
|---|---|
| `PersistenceConfig.kt` | Los puertos de salida: transacciones, bitácora, repositorios |
| `IdentityConfig.kt` | Criptografía, motor de acceso y casos de uso de sesión |
| `WebConfig.kt` | Controladores, resolutores de argumentos y el filtro de `X-Request-Id` |
| `SecurityConfig.kt` | La cadena de seguridad HTTP |
| `SchemaGuard.kt` | La comprobación de versión del esquema al arrancar |
| `StockProperties.kt` | La configuración tipada (`stock.*` en `application.yml`) |
| `CatalogConfig.kt` | El catálogo (M01): puertos de entrada y rutas |
| `InventoryConfig.kt` | Existencias (M02): el motor, las consultas y las rutas |
| `ReportConfig.kt` | Los reportes de existencias y la exportación del kardex |
| `ReceivingConfig.kt` | Recepción (M03): facturas, captura y decisiones sobre discrepancias |
| `RoutePermissionAudit.kt` | I5: una ruta sin declarar qué exige impide el arranque |

Un caso de uso no sabe que existe Spring. `Login` se construye igual en una prueba que en producción:

```kotlin
// api/app/.../config/IdentityConfig.kt
@Bean
fun login(users: UserAccounts, attempts: LoginAttempts, hasher: PasswordHasher, sessions: SessionIssuer,
          codec: RefreshTokenCodec, tx: TransactionRunner, clock: Clock) =
    Login(users, attempts, hasher, sessions, codec, tx, clock)
```

## 3. Puertos

- **Entrada** (casos de uso): una clase por operación con un método `execute`. Verbo y objeto, en
  inglés: `Login`, `RefreshSession`, `ChangePassword` (`api/application/.../identity/`).
- **Salida**: interfaces en `application` con nombres del negocio: `UserAccounts`, `RefreshTokens`,
  `LoginAttempts`, `AuditRecorder`, `AccessCatalogStore`.
- Un puerto **no expone tipos de la base** (ni `ResultSet` ni filas).

## 4. Errores

- Un fallo esperado **se devuelve**, no se lanza (ADR-0005). Todo caso de uso devuelve
  `Outcome<A>`, que es `Either<Failure, A>` (`api/application/.../shared/Failure.kt`).
- `Failure` lleva un `kind` (qué clase de fallo es, sin hablar de HTTP), un `code` estable y un
  `message` para una persona.
- `DomainError.toFailure()` traduce los errores del dominio con un `when` **exhaustivo**: un error
  nuevo en el dominio deja de compilar hasta que alguien decida qué significa para el cliente.
- `adapters-web` traduce `kind` a un código de estado (`http/ErrorResponses.kt`) y responde siempre
  con el mismo cuerpo `{ code, message, details }` ([api/errores.md](../api/errores.md)).
- Lo que no es un fallo de un caso de uso también respeta el contrato (`http/GlobalErrorHandler.kt`):
  JSON ilegible, ruta inexistente, método no permitido y error inesperado. Este último responde
  `500 INTERNAL_ERROR` con un identificador de incidente y **sin** el detalle interno.
- Un fallo de programación (un estado imposible) **se lanza**: es un bug y tiene que verse.

## 5. Transacciones

- La frontera la pone el caso de uso con `TransactionRunner.inTransaction { }`
  (`api/application/.../shared/TransactionRunner.kt`).
- **Un `Left` revierte la transacción.** Lo implementa `JdbcTransactionRunner`
  (`api/adapters-persistence/.../shared/`) sobre `TransactionTemplate`.
- Lo que tiene que quedar escrito aunque el caso de uso falle va en una transacción aparte y se dice
  en el contrato del puerto. Hay dos casos: una denegación de acceso (`AccessDenialLog`,
  `PROPAGATION_REQUIRES_NEW`) y la revocación por reuso de un token (`RefreshSession`).
- `@Transactional` **no se usa** en ninguna capa.
- Un trabajo lento que no necesita la base se hace **antes** de abrir la transacción. `ChangePassword`
  calcula el hash Argon2id primero, para no retener una conexión mientras tanto.

## 6. Identidad y autorización

Son dos preguntas distintas y las responden capas distintas.

**¿Quién llama?** La cadena de seguridad HTTP (`api/app/.../config/SecurityConfig.kt`) verifica el
token y deja en el contexto un `TokenIdentity` con dos datos: el id del usuario y su sello de
credenciales. Todo exige identidad salvo cuatro rutas públicas declaradas en ese archivo.

**¿Qué puede hacer?** Lo decide el caso de uso con `AccessDecider`
(`api/application/.../access/AccessDecider.kt`), que usa el motor del dominio (`AccessEngine`):
identidad → RBAC → ABAC, denegando por defecto. El controlador no decide permisos.

Reglas que el código impone:

- **El token solo identifica.** No lleva roles ni permisos (`JwtAccessTokens.kt`). El `Actor` se
  reconstruye de la base en cada petición (`ActorArgumentResolver` → `ResolveActor`), así que quitar
  un rol o desactivar una cuenta surte efecto en la petición siguiente.
- **Sello de credenciales.** El token lleva el instante, con milisegundos, del último cambio de
  contraseña de la cuenta. Si ya no coincide con el de la base, el token deja de servir. Es una
  comparación exacta, no de fechas.
- **El `Actor` es un argumento.** Llega al caso de uso como parámetro, nunca desde un contexto global.
- **La zona horaria de las políticas es del servidor** (`stock.business-zone`). Una política de
  horario evaluada con la zona que diga el cliente se salta cambiando una cabecera.
- **Cada denegación queda en la bitácora**, en su propia transacción.

Flujos y pruebas en [flujos/autenticacion-y-permisos.md](../flujos/autenticacion-y-permisos.md).

## 7. Persistencia

- `adapters-persistence` usa `JdbcClient` con SQL escrito a mano. No hay ORM
  ([ADR-0015](../adr/0015-postgresql-flyway-motor-en-sql.md)).
- La API se conecta con un usuario miembro del rol `stock_api` (migración V11). Ese rol no puede
  crear ni alterar tablas, ni hacer `UPDATE` o `DELETE` sobre `kardex` y `audit_log`
  ([infraestructura.md](infraestructura.md) §3).
- Las escrituras de inventario llaman a las funciones del motor. La API nunca escribe
  `stock_by_location` ni `product_stock` directamente.
- La bitácora se escribe con `audit_log_record` (V12), que encadena el hash de cada registro
  (`audit/JdbcAuditRecorder.kt`).
- Una lista que se inserta o se sincroniza viaja como **un** documento JSON y la base la expande con
  `jsonb_to_recordset`: una sentencia por tabla, no una por fila (`identity/JdbcAccessCatalogStore.kt`).
- Las fechas las pone el reloj de la aplicación (`Clock`), no el de la base, cuando una regla depende
  de ellas (ventana de bloqueo, caducidad de tokens). Mezclar los dos relojes hace que la regla
  dependa de qué servidor va adelantado.

## 8. Estructura de paquetes

Un paquete por concepto de negocio dentro de cada módulo, no por tipo técnico:

```
dev.axzy.stock.domain.{access, audit, catalog, fulfillment, identity, inventory, receiving, transfer, …}
dev.axzy.stock.application.{access, audit, catalog, health, identity, shared}
dev.axzy.stock.adapters.persistence.{audit, catalog, health, identity, shared}
dev.axzy.stock.adapters.security
dev.axzy.stock.adapters.web.{catalog, health, http, identity, security}
dev.axzy.stock.app.config
```

## 9. Pruebas

| Nivel | Qué prueba | Dónde | Base de datos |
|---|---|---|---|
| Dominio | Reglas, estados, errores (407 pruebas) | `stock-domain/src/commonTest`, `jvmTest` | No |
| Aplicación | Casos de uso y reglas transversales (paginación, nombre visible de una ubicación; 11) | `api/application/src/test` | No |
| Persistencia | Adaptadores, funciones SQL, permisos del rol, presupuesto de consultas, concurrencia, día operativo y guardias de escritura (72) | `api/adapters-persistence/src/test` | PostgreSQL real |
| Web | Controladores, contrato de error y la exportación a CSV/XLSX, sin arrancar el servidor (13) | `api/adapters-web/src/test` | No |
| Criptografía | Argon2id, firma y verificación de tokens (11) | `api/adapters-security/src/test` | No |
| Arquitectura | Las reglas de §1 y §2, y que toda ruta declare su permiso (I5) | `api/app/src/test/.../ArchitectureTest.kt`, `RoutePermissionTest.kt` | No |
| Flujo completo | Los flujos de [flujos/](../flujos/README.md) por HTTP real (108) | `api/app/src/test` | PostgreSQL real |

**La base de pruebas** es PostgreSQL 16 en Testcontainers con las migraciones reales
(`api/adapters-persistence/src/testFixtures/.../TestDatabase.kt`). El código bajo prueba se conecta
con un usuario del rol `stock_api`, así que las pruebas ejercen los permisos de verdad.

**Las pruebas de flujo** (`ApiTest.kt`) arrancan la aplicación completa: Tomcat en un puerto libre,
la cadena de seguridad, el cableado real y la base real. Solo se sustituye el reloj, para adelantar el
tiempo.

**Una prueba que necesita la base falla si no la hay**; no se salta. Si Docker no está disponible,
Testcontainers lanza un error y el build se rompe.

```bash
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew test    # 622 pruebas el 2026-10-10
```

El build detecta el socket del contexto de Docker activo (Colima, OrbStack, Docker Desktop), así que
no hace falta exportar `DOCKER_HOST` (`build.gradle.kts`).

## 10. Lo que no se hace

- No hay un servicio por entidad que mezcle reglas y SQL.
- No hay capas de DTO que repitan el dominio: un DTO HTTP existe solo cuando el contrato lo necesita.
- No hay `Manager`, `Helper` ni `Util` con reglas de negocio.
- No hay sesión de servidor ni cookies: la API es sin estado.

## 11. Rendimiento y consistencia: reglas verificables

Salen de los hallazgos del legacy
([analisis/legacy-entradas-salidas-ajustes-traslados.md](../analisis/legacy-entradas-salidas-ajustes-traslados.md)).
Cada una tiene una prueba; las que aún no la tienen indican la fase.

| Regla | Forma incorrecta (legacy) | Forma correcta | Prueba |
|---|---|---|---|
| R-P1. Ninguna consulta dentro de un bucle | `findUnique` de usuario por línea (`invoice.service.ts:178`) | Una consulta con `JOIN`, y mapeo en memoria | `IdentityPersistenceTest`: cargar al actor son 2 consultas con 1 rol o con 3 |
| R-P2. Ningún listado sin límite | `findMany()` de toda la tabla y filtro en JavaScript (`kardex.service.ts:587`) | Filtro y `LIMIT` en la consulta | `CatalogPersistenceTest`: un listado son 2 sentencias con 1 fila o con 50 |
| R-P3. El saldo no se recalcula desde el historial | Sumar todo el kardex en cada operación (`kardex.service.ts:408`) | Leer la proyección | `InventoryPersistenceTest`: el total sale de `product_stock` |
| R-P4. Toda escritura de varios pasos va en una transacción | `addKardex` escribe el kardex y después el stock (`kardex.service.ts:241-258`) | Un solo `inTransaction { }` | `JdbcAuditRecorderTest` (U1): un `Left` no deja nada escrito |
| R-P5. Ninguna operación queda sin esperar | `orderDetail.map((item) => addKardexOut(...))` sin `await` (`order.service.ts:350`) | Llamadas secuenciales dentro de la transacción | Fase 7 |
| R-P6. El saldo lo cambia la base con bloqueo | Leer el saldo, calcular y escribir el valor absoluto | `UPDATE … SET quantity = quantity + :delta` en la función SQL | `InventoryEngineConcurrencyTest` (A3, A4) y `InventoryWriteGuardTest` (ningún adaptador escribe el saldo) |
| R-P7. Un movimiento no se modifica | `kardex.update` y `updateMany` que cambian `moveType` y `moveDate` | Solo `INSERT`; la base rechaza `UPDATE` y `DELETE` | `InventoryEngineConcurrencyTest` (A9) y `ApiRolePrivilegesTest` |
| R-P8. Ningún log contiene existencias completas | `console.log` con el estado de existencias (`order.service.ts:396`) | Log con identificadores y conteos | Revisión de la fase 5 |
| R-P9. Una sincronización es una sentencia por tabla | Una sentencia por permiso y por celda en cada arranque | `jsonb_to_recordset` | `IdentityPersistenceTest`: todo el sembrado son ≤ 12 sentencias |
| R-P10. La cadena de la bitácora no recorre la tabla | `ORDER BY sequence_no DESC LIMIT 1` sin índice: leía todas las filas en cada escritura | Índice por `sequence_no` (V11) | `ApiRolePrivilegesTest` comprueba que el índice existe; medición en [infraestructura.md](infraestructura.md) §4 |

**Criterio de rendimiento.** Cada listado y cada operación de inventario tiene un presupuesto de
sentencias SQL por llamada, que se fija con la primera medición del módulo. `StatementCounter`
(`testFixtures`) cuenta las sentencias en las pruebas. Las cifras de tiempo no se fijan antes de medir.
