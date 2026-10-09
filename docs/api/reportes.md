# Reportes
> **Referencia.** Existencias con totales, exportación del kardex, resumen de
> movimientos y reconciliación entre el ledger y la proyección.
> Última verificación: 2026-10-09.

Implementado en
`backend/src/main/kotlin/dev/axzy/stock/backend/reports/ReportRoutes.kt` y
`ReportService.kt`, con los DTO en `ReportDtos.kt` y la exportación a CSV/XLSX en
`ReportExport.kt`. La reconciliación vive en
`backend/src/main/kotlin/dev/axzy/stock/backend/inventory/InventoryRoutes.kt`. La
paginación está en [convenciones.md](convenciones.md); los códigos, en
[errores.md](errores.md).

Los tres listados exigen `report.view`; la exportación, `report.export`. **Son los
mismos permisos que los guards de las pantallas**: un permiso distinto hace que el
usuario entre a la pantalla y reciba un `403` al cargarla.

## `GET /v1/reports/inventory`

Existencias por producto y ubicación, con totales. Filtros: `warehouseCode`,
`productCode` y `onlyWithStock` (`true` por defecto: un reporte que liste miles de
filas a `0.000` no lo lee nadie). Paginación por `page` y `size`.

```json
{
  "items": [ { "productCode": "ABRAA1B", "name": "...", "locationDisplayName": "04-B-C",
               "quantity": "100.000", "warehouseCode": "PRINCIPAL" } ],
  "totals": { "distinctProducts": 1398, "totalUnits": "10914130.000" },
  "page": 0, "size": 50, "totalItems": 1398, "totalPages": 28
}
```

`totals` se calcula sobre el **filtro entero**, no sobre la página: un total que
ignora el filtro es peor que no tenerlo, porque el usuario decide con él.
`distinctProducts` cuenta productos **distintos**: uno en tres estantes es uno, no
tres.

`locationDisplayName` lo compone el servidor (`04-B-C`, `RECEPCION`, `SIN-UBICAR`).

## `GET /v1/reports/kardex/export?format=xlsx|csv`

Permiso: `report.export`. Devuelve el archivo con `Content-Disposition:
attachment` y `Content-Type: text/csv` o el de hoja de cálculo según `format`.
`format` es obligatorio y solo admite `csv` o `xlsx` (`400 INVALID_PARAMETER` con
`details.allowed`).

Filtros: `productCode`, `moveType`, `from`, `to`. **Los filtros son los mismos que
los del listado**: si el usuario ve 200 filas filtradas y exporta, debe recibir esas
200. Un archivo con otro filtro obliga a reconciliar dos vistas de los mismos datos.

En XLSX los importes van como celda **numérica** con 3 decimales (en JSON van como
texto): una columna de texto no se suma y Excel la contaría como cero.

## `GET /v1/reports/movements-summary?from=&to=&groupBy=day|week|month|product|location`

Permiso: `report.view`. `from` y `to` son obligatorios (`AAAA-MM-DD`); `groupBy`
por defecto es `day`. No usa sobre de paginación.

```json
{
  "from": "2026-10-01", "to": "2026-10-31", "groupBy": "day",
  "totals": { "inUnits": "12500.000", "outUnits": "8300.000", "netUnits": "4200.000", "movements": 412 },
  "items": [
    { "key": "2026-10-07", "keyLabel": "2026-10-07",
      "inUnits": "1200.000", "outUnits": "300.000", "netUnits": "900.000", "movements": 18 }
  ]
}
```

`items[].key` es el valor por el que se agrupa —una fecha ISO, un `productCode` o un
`locationDisplayName`— y `keyLabel` su forma para mostrar. Se separan porque una
fecha se ordena como texto ISO y se muestra formateada según el idioma, y **el
cliente no debe tener que saber cuál es cuál**.

`LOCATE` y `MOVE_WITHIN_WAREHOUSE` **no suman en ninguna columna**, y es lo
correcto: mueven mercancía entre ubicaciones, así que el total no cambia. Contarlos
como salida inflaría la cifra y no cuadraría con el kardex. `PENDING_OUT` tampoco
suma: es una intención de salida.

`groupBy` inválido → `400 INVALID_PARAMETER` con los valores admitidos en
`details.allowed`. Un rango invertido → `400 INVALID_FILTER_RANGE`; una fecha mal
formada → `400 INVALID_FILTER_DATE`; `from` o `to` ausentes → `400 FIELD_REQUIRED`.

## `GET /v1/reports/kardex-vs-projection`

Permiso: `report.view`. **El reporte de reconciliación.** Compara lo que dice el
ledger con lo que dicen las proyecciones.

```json
{
  "checkedAt": "2026-10-08T03:24:02.709620Z", "balanced": true,
  "divergences": 0,
  "items": [ { "productId": 1, "productCode": "ABRAA1B",
               "projectedTotal": "5193.000", "ledgerTotal": "5193.000",
               "difference": "0.000" } ]
}
```

| Campo | Tipo | Significado |
|---|---|---|
| `balanced` | booleano | `true` cuando **no hay ninguna divergencia**. Es el veredicto del servidor |
| `divergences` | entero | Cuántos productos están descuadrados |
| `items` | arreglo | **Solo los productos descuadrados**, no todos los comprobados |
| `items[].projectedTotal` | cadena decimal | Lo que dice la proyección `product_stock` (valor derivado) |
| `items[].ledgerTotal` | cadena decimal | Lo que dice el ledger, que es la **fuente de verdad** |
| `items[].difference` | cadena decimal | `projectedTotal - ledgerTotal`. El signo importa |

**`items` vacío con `balanced: true` es el estado sano**, y es la respuesta normal.
El veredicto lo da el servidor y no se recalcula en el cliente: contar el arreglo en
la pantalla sería un segundo cálculo que puede discrepar del primero, y entonces el
descuadre sería el de la pantalla y no el del inventario.

Los nombres de campo van **en inglés** porque son campos de la API pública
(`docs/historico/ARQUITECTURA.md` §5.1). Hasta la migración `V8` la ruta devolvía `cuadra`,
`totalEnBd`, `totalEsperado` y `diferencia` —las columnas del `RETURNS TABLE` de
`comprobar_proyecciones()`—, que no coincidían con lo que el contrato prometía. La
misma migración renombró las columnas y alineó las dos partes. Los nombres de
columna en inglés tienen que coincidir con el `RETURNS TABLE`; un mapper que los
lee por cadena falla en silencio si no coinciden, y por eso el mapeo está cubierto
por `DivergenceMapperTest.kt`.

El permiso es **`report.view`**, no `inventory.reconcile`: es el mismo que el guard
de la pantalla `/reports`, y esa coincidencia es obligatoria.

En el legacy esta comprobación no existía, y el descuadre solo se descubría con un
conteo físico.

## Verificación

| Archivo | Qué cubre |
|---|---|
| `backend/src/test/kotlin/dev/axzy/stock/backend/reports/ReportServiceTest.kt` | las consultas de los reportes |
| `backend/src/test/kotlin/dev/axzy/stock/backend/reports/ReportExportTest.kt` | la exportación a CSV/XLSX |
| `backend/src/test/kotlin/dev/axzy/stock/backend/inventory/DivergenceMapperTest.kt` | el mapeo de la reconciliación |
| `backend/src/test/kotlin/dev/axzy/stock/backend/ApiE2ETest.kt` | las rutas de reportes contra la API montada |
