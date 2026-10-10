# ADR-0015 — PostgreSQL 16 con Flyway, y el motor de inventario en funciones SQL
> **Normativo.** Decisión aceptada.

| | |
|---|---|
| **Estado** | Aceptada |
| **Fecha** | 2026-10-09 |
| **Reemplaza a** | — |
| **Reemplazada por** | — |

## Contexto

Las reglas de conservación del inventario (que el saldo nunca es negativo, que el total cuadra
con las ubicaciones y el tránsito, que el kardex solo crece) viven hoy en funciones de PostgreSQL
(`aplicar_movimiento_a_ubicacion`, `recalcular_total_producto`, `registrar_movimiento`). Esa
decisión dio la garantía más fuerte del sistema: cualquier escritura, venga de donde venga,
pasa por la misma regla. La revisión del 9 de octubre encontró que esa garantía tenía un hueco
de concurrencia (el total se perdía con dos escrituras simultáneas), y que se corrige en la
propia base con un bloqueo, no en el código de la aplicación.

## Decisión

1. **PostgreSQL 16** como única base de datos.
2. **Flyway** versiona el esquema. Las migraciones son archivos `V<n>__descripcion.sql` en
   `app` o en un módulo de infraestructura, **inmutables una vez aplicadas**: una corrección es
   una migración nueva (como la `V10`), nunca una edición de una existente.
3. **El motor de inventario sigue en funciones SQL.** La API llama a esas funciones con
   `JdbcClient` (Spring), no con un ORM que escriba las tablas directamente. El ORM no se usa
   para el inventario.
4. **Bloqueo explícito donde hay escrituras concurrentes.** Recalcular el total de un producto toma
   `SELECT … FOR UPDATE` sobre el producto. Confirmar una orden bloquea la orden. Ver
   [flujos/existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) y
   [flujos/surtido-y-erp.md](../flujos/surtido-y-erp.md).
5. **Las migraciones `V1`–`V10` se reutilizan** como esquema inicial de Flyway (plan D7). Se
   aplican en una base nueva con historial limpio; no se editan.

## Consecuencias

- Las reglas críticas se verifican con pruebas contra PostgreSQL real. Una prueba que se salta
  cuando no hay base da un verde vacío: las pruebas de integración fallan si la base no está.
- Un cambio de regla del inventario es una migración SQL, con su prueba, y no solo código
  Kotlin. Quien cambie el motor debe leer la migración anterior que lo define.
- La lógica en SQL es menos portable que en Kotlin. Se acepta porque PostgreSQL es el único
  destino previsto.

## Alternativas descartadas

- **Mover la regla del inventario a Kotlin** (con JPA o JDBC plano). Quedaría una regla que
  cualquier escritura directa a las tablas puede saltarse, que es justo lo que las funciones evitan.
- **Liquibase** en lugar de Flyway. Ambos sirven; Flyway ya estaba en uso y el historial de
  `V1`–`V10` se conserva tal cual.
- **Un ORM para el inventario** (Hibernate). El flush y el orden de escritura del ORM no
  coinciden con el motor, y la concurrencia se volvería implícita.
