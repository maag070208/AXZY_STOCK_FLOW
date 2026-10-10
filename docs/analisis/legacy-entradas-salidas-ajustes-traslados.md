# Análisis del legacy: entradas, salidas, ajustes y traslados
> **Referencia.** Hallazgos de rendimiento e integridad en el código de `BASE/backend` (Node + Prisma). Revisión del 9 de octubre de 2026. Cada hallazgo cita su archivo y línea; **se verificó por lectura del código, no ejecutando el legacy**, salvo donde se indica. Su función es justificar requisitos del [plan](../plan/PLAN-REHACER.md) (Q6–Q10).

## 1. Alcance y método

Se leyeron los servicios que escriben inventario: `invoice.service.ts` (entradas), `order.service.ts`
(salidas), `kardex.service.ts` y `location.service.ts` (ajustes y ubicaciones), y el controlador de
importación `kardo.controller.ts`. Se buscaron los patrones que más daño hacen en un sistema de
inventario: escrituras sin transacción, lectura y escritura separadas (lectura-modificación-escritura),
llamadas `await` dentro de bucles, promesas sin esperar y modificaciones de historial.

**El legacy no tiene traslados entre bodegas.** Lo más parecido son los movimientos de ubicación
(`LOCATION`, `PENDING-TO-LOCATE`, `ADJUSTMENT`). `getDataTransfers` (`kardo.service.ts`) importa facturas
de SQL Server; no es un traslado. Ambos se tratan aquí.

## 2. Resumen por severidad

| Severidad | Qué significa | Cantidad |
|---|---|---|
| **Crítico** | Puede dejar el inventario descuadrado sin que nadie lo note | 6 |
| **Alto** | Rendimiento que empeora con el tiempo o por cada operación | 4 |
| **Medio** | Frágil o confuso; no rompe hoy, da problemas al cambiar | 3 |

## 3. Hallazgos críticos

### C1 — El kardex se modifica en lugar de crecer (ajustes y salidas)

- `updateKardex` actualiza `units` de la fila existente (`kardex.service.ts:183-215`).
- Al sacar en `addKardex` tipo OUT, se restan unidades de las filas `INITIAL` (`kardex.service.ts:296-310`)
  y luego `updateMany` cambia el `moveType` a `OUT` y el `moveDate` a la hora actual
  (`kardex.service.ts:313-325`). **La fecha real del movimiento se pierde.**
- Las ubicaciones se mueven de la misma forma: `removeUnitsFromLocation` restringe la fila `INITIAL`
  (`location.service.ts:656-680`).
- El borrado es lógico y sobre el kardex (`order.service.ts:236` y `:245` con `deleted: true`).

**Efecto.** El libro de movimientos no reconstruye la historia: el saldo de hoy no se puede explicar con
las filas que hay. Es lo contrario de lo que justificó el diseño anterior.

**En el diseño nuevo.** El kardex solo se inserta; corregir es añadir un movimiento compensatorio
(Q6). Ver [existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) §3 y prueba A9.

### C2 — El stock se calcula con lectura-modificación-escritura y sin transacción

- `updateStoreStock` lee `store.stock`, calcula en JavaScript el valor nuevo y escribe el absoluto
  (`kardex.service.ts:552-585`). No valida que el resultado no sea negativo.
- Se llama desde `addKardex` después de crear la fila de kardex (`kardex.service.ts:241-258`): dos
  escrituras sin transacción entre ambas.

**Efecto.** Dos operaciones concurrentes sobre el mismo producto se pierden una a otra: es la misma
pérdida de actualización reproducida en el motor nuevo antes de corregirla (ver
[existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) §5).

**En el diseño nuevo.** El saldo lo cambia la base con un bloqueo, nunca la aplicación con un valor
absoluto (Q1, Q7).

### C3 — Salida sin esperar las escrituras, y líneas marcadas como surtidas sin surtir

`updateOrderDetails` (`order.service.ts:324-365`) hace dos cosas graves:

1. Marca **todas** las líneas de la orden como `verified` con `updateMany` (`order.service.ts:330-335`),
   surtidas o no.
2. Registra la salida con `orderDetail.map((item) => addKardexOut(...))` (`order.service.ts:350-358`)
   **sin `await`**. La función devuelve antes de que se escriba el kardex, y los errores de esas promesas
   no se capturan.
3. La salida usa `Number(item.quantity)` (`order.service.ts:354`): la cantidad **pedida**, no la
   surtida. Una línea de 10 con 7 empacadas descuenta 10.

**Efecto.** El inventario baja por lo pedido y no por lo entregado; el descuadre solo aparece al contar.
Un fallo al escribir el kardex no lo ve nadie.

**En el diseño nuevo.** La salida es `quantity − supplied_quantity` dentro de una transacción que
bloquea la orden (Q8). Ver [surtido-y-erp.md](../flujos/surtido-y-erp.md) §4 y prueba S2.

### C4 — El empaque comprueba disponibilidad con una foto y escribe después

`updateStoreLocationWithOrder` (`order.service.ts:367-500`):

- Valida `productExistence.total < units` con datos leídos antes de escribir (`order.service.ts:410`).
- Luego, en pasos separados y sin transacción: `addKardex` en `order.service.ts:458` (kardex y stock), `physicalAssignItems` como
  lectura y escritura (`order.service.ts:475`) y el cambio de estado (`order.service.ts:489`).
- Llama a `getKardexByProductCode` en cada empaque (`order.service.ts:385`; ver A1 en §4).

**Efecto.** Dos operarios que empacan a la vez pasan la misma validación y pueden sacar más de lo que hay.
Si falla una de las escrituras intermedias, queda un estado a medias.

**En el diseño nuevo.** La disponibilidad se comprueba dentro de la transacción, con el saldo bloqueado
por la base (prueba A3 de [existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) §7).

### C5 — Ajuste de ubicación con lectura-modificación-escritura

`removeUnitsFromLocation` lee la fila `INITIAL`, resta en JavaScript y escribe el valor absoluto
(`location.service.ts:656-680`), sin bloqueo ni transacción. Mismo defecto que C2, en el camino de los
ajustes de ubicación.

### C6 — Confirmar una orden no comprueba ni mueve nada

`confirmOrder` (`order.service.ts:255-296`) busca la orden, verifica que tenga líneas y cambia su estado a
`verified`. **No comprueba cantidades, no comprueba existencias y no descuenta.** El descuento ocurre
después, en `updateOrderDetails` (ver C3). Dos rutas de «confirmar» con reglas distintas: un operario que
use la equivocada cree haber confirmado algo que no descontó.

## 4. Hallazgos de rendimiento

### A1 — Historial completo del producto en cada operación

`getKardexByProductCode` (`kardex.service.ts:408-`) carga **todas** las filas de kardex del producto
(`findMany` con `where: { productCode, deleted: false }`, sin límite) y las agrega en memoria. Se llama en
cada empaque (`order.service.ts:385`). El coste crece con cada movimiento, aunque la consulta pida solo
el saldo.

**En el diseño nuevo.** El saldo sale de la proyección (`stock_by_location`, `product_stock`), que se
mantiene en la base; ninguna operación recorre el historial.

### A2 — Carga completa de la tabla de existencias

`getStoreByIdRange` (`kardex.service.ts:587-595`) hace `store.findMany()` sin filtro y descarta filas en
JavaScript. Trae el catálogo entero para devolver un rango.

### A3 — Consulta por línea (N+1) en listados de facturas y órdenes

- `getInvoiceDetail` (`invoice.service.ts:178-186`) hace un `user.findUnique` por cada línea.
- `getOrderDetail` (`order.service.ts:92-115`) hace lo mismo.

Con una factura de 200 líneas son 201 consultas por cada apertura de pantalla.

### A4 — Búsqueda por producto dentro de bucles en la ubicación

`location.service.ts:276` y `:298` llaman a `getProductByCode` dentro de un bucle `for` por cada elemento del
estante. Cada llamada es una consulta más.

**Además**, la importación de facturas (`kardo.controller.ts:22-70`) busca cada factura con `findFirst` en
un `map` y luego marca `Estatus = 1` con un bucle secuencial de `await` por registro.

## 5. Hallazgos medios

| # | Dónde | Qué pasa | Por qué importa |
|---|---|---|---|
| M1 | `invoice.service.ts:322` | `item.quantity === item.physicalQuantity` compara lo que llegue en el JSON | Si el cliente envía `"5"` y no `5`, la línea pasa a `discrepancy` sin haberlo sido. No reproducido; la comparación depende del tipo |
| M2 | `order.service.ts:396` y `:449` | `console.log` con el estado completo de existencias del producto | Escribe datos de inventario en el log de producción en cada operación |
| M3 | `kardo.controller.ts:22-70` | Importación y marca de procesado en pasos separados | Si el proceso se corta a mitad, la siguiente ejecución vuelve a leer los que faltan; la protección depende de un `findFirst`, no de una clave única |

## 6. Cómo se corrige en el diseño nuevo

| Hallazgo | Requisito del plan | Dónde se prueba |
|---|---|---|
| C1 kardex modificado | Q6: el kardex solo crece; corregir es un movimiento nuevo | `existencias-y-ajustes.md` A9 |
| C2, C5 lectura-modificación-escritura | Q7: el saldo lo cambia la base con bloqueo | `existencias-y-ajustes.md` A3, A4 |
| C3 salida sin await y cantidad pedida | Q8: salida = pendiente de surtir, en transacción | `surtido-y-erp.md` S2 |
| C4 empaque con foto previa | Q9: disponibilidad dentro de la transacción | `existencias-y-ajustes.md` A3 |
| C6 dos rutas de confirmar | Q8: una sola operación de confirmación | `surtido-y-erp.md` §4 |
| A1 historial completo | Q10: el saldo viene de la proyección | Prueba de rendimiento con 10 000 movimientos (fase 5) |
| A2, A3, A4 consultas en bucle | Q10: un listado es una consulta con los datos ya unidos | Prueba de número de consultas por listado (fase 5) |
| M1 comparación frágil | Validación de tipos en la entrada (la API rechaza tipos incorrectos) | Prueba de contrato (fase 2) |
| M2 log de datos | Ningún log contiene existencias ni cantidades completas | Revisión en la fase 5 |
| M3 importación no idempotente | Clave única por registro importado | `catalogo.md` (importación) |

## 7. Lo que no se afirma

- No se reprodujeron ejecutando el legacy los fallos de C2 y C4 bajo concurrencia real. La pérdida
  de actualización sí se reprodujo sobre el motor de PostgreSQL del diseño nuevo
  ([existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) §5), que tiene el mismo patrón.
- No se midió el tiempo de respuesta de A1–A4. Las cifras de «200 líneas» son ilustrativas.
- El legacy tiene datos reales en producción; cualquier corrección sobre sus datos se decide aparte.
