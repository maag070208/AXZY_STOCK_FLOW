# Convenciones transversales: idioma, comentarios y estándares
> **Normativo.** Obligatorias en las cuatro partes del monorepo. Última verificación: 2026-10-09.

La decisión sobre el idioma está en
[ADR 0004](../adr/0004-nombres-en-ingles-comentarios-en-espanol.md).

## 1. Idioma

> **Variables, métodos, constantes, clases, tablas y propiedades: en inglés.
> Solo los comentarios en español.**

```kotlin
// ✅
fun approveInvoice(invoiceId: Long, reason: AdjustmentReason)

// ❌
fun aprobarFactura(facturaId: Long, motivo: AdjustmentReason)
```

**Lo que no se traduce nunca:**

| Qué | Por qué |
|---|---|
| **Los comentarios** | Son para el equipo, y el equipo piensa en español |
| **Los mensajes de error** | `"El producto no existe"` **lo lee el operador** |
| **Los `displayName`** | Es texto de interfaz |
| **Los valores de enum** | Están en la base y en el contrato (`SHORTAGE`, `PENDING`) |
| **Los códigos de error** | `INSUFFICIENT_STOCK` es **API pública** |

**Los nombres de tabla y columna ya están en inglés** y no se tocan. La excepción consciente
son los nombres de las funciones PL/pgSQL del motor de inventario
(`aplicar_movimiento_a_ubicacion`, `recalcular_total_producto`, `registrar_movimiento`,
`comprobar_proyecciones`), que son un detalle interno de la base y no se filtran al contrato
HTTP (`backend/src/main/resources/db/migration/`).

## 2. Comentarios: el estilo de la casa

**Explican el por qué, no el qué.** El qué ya lo dice el código.

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

**Se documentan los tropiezos.** Un comentario que dice «esto se intentó y rompía X» vale más
que diez que describen lo obvio: evita que el siguiente lo repita.

## 3. Estándares de código — la checklist obligatoria

**Cada punto se revisa antes de dar una tarea por terminada.** No es una guía de estilo: es lo
que se comprueba.

| # | Regla | El fallo que evita |
|---|---|---|
| 1 | **Visibilidad explícita** (`private`, `internal`, `public`) | Todo es `public` por defecto y cualquier archivo puede tocar cualquier cosa |
| 2 | **Inmutabilidad por defecto**: `val`, colecciones de **solo lectura** | Un `MutableList` compartido cambia «solo» desde otro sitio |
| 3 | **Tipo de retorno explícito en lo público** | Cambiar el tipo de retorno de una `fun` pública **sin tocar la firma** rompe a quien la llama |
| 4 | **Seguridad frente a nulos**: `?.`, `?:`, smart casts | El `NullPointerException` en producción |
| 5 | **`!!` prohibido** | Un `!!` es una **afirmación sin prueba**: convierte un dato ausente en una caída |
| 6 | **Funciones de extensión** para utilidades | Utilidades sueltas en clases `Utils` que nadie sabe dónde viven |
| 7 | **Inyección por constructor** ([api-ktor.md](../historico/API-KTOR.md) §1.8) | Dependencias escondidas y clases que no se pueden probar |
| 8 | **Todo tiene que ser testeable** | Código que solo se puede probar arrancando el servidor |

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
