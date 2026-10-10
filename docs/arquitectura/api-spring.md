# API: Kotlin con Spring Boot, en capas e inyección por constructor
> **Normativo.** Cómo se organiza la API nueva. Decisiones en [ADR-0014](../adr/0014-api-spring-boot-clean-architecture.md) y [ADR-0015](../adr/0015-postgresql-flyway-motor-en-sql.md). Última revisión: 2026-10-09.

## 1. Módulos y dependencias

La API es un proyecto Gradle multimódulo. Cada módulo es una capa, y la dependencia solo apunta
hacia el dominio:

| Módulo | Contiene | Puede depender de | No puede depender de |
|---|---|---|---|
| `domain` | Entidades, estados, reglas, `DomainError` | Kotlin stdlib, `stock-domain` compartido (si D6 se aprueba) | Spring, JDBC, Jackson, cualquier framework |
| `application` | Casos de uso, puertos de entrada y salida, `TransactionRunner`, DTO de aplicación | `domain` | Spring, JDBC, adaptadores |
| `adapters-persistence` | Repositorios JDBC, mapeo de filas a `domain`, llamadas a funciones SQL | `application`, `domain`, Spring JDBC | `adapters-web` |
| `adapters-web` | Controladores REST, DTO HTTP, manejo de errores HTTP | `application`, `domain`, Spring Web | `adapters-persistence` |
| `app` | `@SpringBootApplication`, configuración que **crea** los casos de uso, seguridad HTTP | Todos los anteriores | — |

```
app ──→ adapters-web ──┐
 │                      ├──→ application ──→ domain
 └──→ adapters-persistence ┘
```

`adapters-web` y `adapters-persistence` **no se conocen entre sí**. Un controlador nunca llama
a un repositorio: llama a un caso de uso, y el caso de uso llama a un puerto de salida que
implementa `adapters-persistence`.

### Cómo se verifica

- **El compilador**: cada módulo solo declara como `implementation` lo que puede ver. Una
  dependencia prohibida no compila.
- **ArchUnit** (prueba en `app`): repite las reglas de §1 contra las clases compiladas, por si
  alguien añade una dependencia transitiva. Falla el build si una clase de `domain` importa
  `org.springframework` o `java.sql`.

## 2. Inyección de dependencias

**Regla: los objetos se crean en un solo lugar, `app`, y se pasan por constructor.**

- Los casos de uso son clases Kotlin sin anotaciones de Spring. Reciben sus puertos en el
  constructor.
- Los adaptadores (`@Repository`, `@RestController`) son los únicos con anotaciones de Spring,
  además de la configuración de `app`.
- Está prohibido `@Autowired` sobre campos y `ApplicationContext.getBean()` fuera de `app`.

```kotlin
// app/src/main/kotlin/.../config/ApplicationConfig.kt  (ilustrativo)
@Configuration
class ApplicationConfig {
    @Bean
    fun registerReceivedInvoice(
        invoices: InvoiceRepository,      // puerto, implementado por adapters-persistence
        audit: AuditRecorder,             // puerto, implementado por adapters-persistence
        access: AccessDecider,            // puerto, implementado en application con el motor de accesos
        tx: TransactionRunner,
    ) = RegisterReceivedInvoice(invoices, audit, access, tx)
}
```

El caso de uso no sabe que existe Spring: `RegisterReceivedInvoice` se construye igual en una
prueba unitaria, con fakes en lugar de los puertos.

## 3. Puertos

- **Entrada** (casos de uso): una clase por operación, con un método `execute`. Nombres en
  inglés, verbo y objeto: `RegisterReceivedInvoice`, `ConfirmOrder`, `ExportOrderToErp`.
- **Salida** (repositorios y servicios externos): interfaces definidas en `application`, con
  nombres del negocio: `InvoiceRepository`, `StockLedger`, `ErpGateway` (ver
  [flujos/surtido-y-erp.md](../flujos/surtido-y-erp.md)).
- Un puerto **no expone tipos de la base** (ni `ResultSet`, ni filas, ni ids internos sin
  significado de negocio).

## 4. Errores

- El dominio devuelve `Either<DomainError, T>`. `DomainError` es un catálogo cerrado `sealed`
  con un `code` estable (ver [api/errores.md](../api/errores.md)).
- Un `SQLException` **no sube** de `adapters-persistence`: se traduce allí a un fallo tipado o
  a un error de infraestructura explícito.
- `adapters-web` tiene un único `@RestControllerAdvice` que traduce `DomainError` a HTTP
  (400, 401, 403, 404, 409, 422) y los errores de infraestructura a 500 con un identificador de
  incidente, sin detalles internos.
- Un fallo de programación (un `when` sin cubrir, un estado imposible) **se lanza**, no se
  devuelve: es un bug y tiene que verse.

## 5. Transacciones

- La frontera de transacción la pone el caso de uso, llamando a `TransactionRunner.inTransaction { }`.
  Un caso de uso que escribe varias cosas (el movimiento y su bitácora, por ejemplo) hace todo
  dentro de un solo bloque: o se confirman juntas o ninguna.
- `adapters-persistence` implementa `TransactionRunner` con `TransactionTemplate` de Spring.
- `@Transactional` **no se usa** en `application` ni en `domain`.

## 6. Autorización

- La identidad se verifica en `adapters-web` (filtro de seguridad, token JWT). El usuario llega al
  caso de uso como un argumento `Actor`, nunca como estado global ni como `SecurityContextHolder`.
- **El permiso lo decide el caso de uso**, con el puerto `AccessDecider`. El controlador no
  decide permisos por su cuenta.
- Denegar por defecto: si no hay decisión explícita, se niega. Reglas en
  [flujos/autenticacion-y-permisos.md](../flujos/autenticacion-y-permisos.md).

## 7. Persistencia

- `adapters-persistence` usa `JdbcClient` de Spring con SQL escrito a mano. No se usa un ORM para
  el inventario ([ADR-0015](../adr/0015-postgresql-flyway-motor-en-sql.md)).
- Las escrituras de inventario llaman a funciones SQL (`aplicar_movimiento_a_ubicacion`,
  `recalcular_total_producto`, `registrar_movimiento`). La API nunca escribe `stock_by_location`
  ni `product_stock` directamente.
- Las consultas de listado y su conteo usan **el mismo filtro** (un total sin filtrar miente).
- Los identificadores de operación idempotente (`Idempotency-Key`, `client_uuid`) son obligatorios
  en los endpoints que pueden reintentarse.

## 8. Estructura de paquetes

Un paquete por concepto de negocio dentro de cada módulo, no un paquete por tipo técnico:

```
domain/            inventory/  receiving/  fulfillment/  transfer/  labels/  access/  audit/
application/       inventory/  receiving/  fulfillment/  transfer/  labels/  access/  audit/
adapters-persistence/  …
adapters-web/      …
```

## 9. Pruebas

| Nivel | Qué prueba | Dónde | Base de datos |
|---|---|---|---|
| Dominio | Reglas, estados, errores | `domain/src/test` | No |
| Aplicación | Casos de uso con fakes de los puertos | `application/src/test` | No |
| Persistencia | Adaptadores y funciones SQL, incluida la prueba de concurrencia Q1 | `adapters-persistence/src/test` (Testcontainers) | Sí, PostgreSQL real |
| Web | Contrato HTTP, errores, autenticación | `adapters-web/src/test` | Sí, con el caso de uso real |
| Arquitectura | Reglas de §1 | `app/src/test` (ArchUnit) | No |
| Flujo completo | Los flujos de [flujos/](../flujos/README.md) de punta a punta | `app/src/test` | Sí |

**Las pruebas que necesitan PostgreSQL fallan si no hay base**; no se saltan. Una prueba saltada
cuenta como aprobada en el recuento y eso ocultó huecos antes.

## 10. Lo que no se hace

- No hay un servicio por entidad que mezcle reglas y SQL.
- No hay capas de DTO que repitan el dominio sin necesidad: un DTO HTTP existe solo cuando el
  contrato público lo necesita.
- No hay clases `Manager`, `Helper` ni `Util` con reglas de negocio.
