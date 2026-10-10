# Dominio — KMP puro (`stock-domain/`)
> **Normativo.** Reglas de pureza, contenido y por qué las reglas de negocio viven aquí.
> Última verificación: 2026-10-09.

## 4.1 La regla: KMP puro, sin dependencias

**Solo puede usar `kotlin-stdlib` + `kotlinx-datetime`, `kotlinx-serialization` y
`kotlinx-coroutines`.** Nada más. Es lo que permite que el mismo código tome las mismas
decisiones en el backend, en Android y en iOS.

**Está impuesto por una guardia de arquitectura**, no por buena voluntad:

- `verifyDomainPurity` (`stock-domain/build.gradle.kts`) falla el build si alguna
  configuración de producción declara un framework prohibido (`ktor`, `exposed`,
  `sqldelight`, `compose`, `androidx`, `hikari`, `postgresql`, `flyway`, `logback`, `koin`,
  `spring`, `micrometer`, `jackson`, `okhttp`, `retrofit`) o si algún archivo importa uno.
  Se ejecuta sobre las dependencias **declaradas** y sobre los imports de `commonMain`,
  `jvmMain`, `androidMain` e `iosMain`.
- `forbidJvmMainSourceSet` falla si aparece código en `src/jvmMain`, `src/androidMain` o
  `src/iosMain`: el dominio debe ser común, y `jvmMain` es la puerta por la que entraría
  Exposed.

Las dos cuelgan de `check`, así que no se pueden saltar por descuido, y dejan constancia:

```
✓ stock-domain es Kotlin puro (guardia de arquitectura OK)
✓ stock-domain no tiene código específico de plataforma
```

**Comprueba además que no se use una anotación solo de JVM sin calificar.** `@JvmInline`
compila en JVM y **rompe el build de iOS** con «Unresolved reference 'JvmInline'»; la forma
correcta y multiplataforma es `@kotlin.jvm.JvmInline`. La guardia detecta lo mismo que
encontró Kotlin/Native, sin necesidad de tener Xcode instalado.

**`androidMain` no tiene dependencias, y es deliberado.** El contrato del escáner
(`ScanWindow`, `ScanState`, `ScanOutcome`) y el modelo de qué hacer con lo escaneado viven
en el dominio; la implementación con CameraX y ML Kit va en un módulo de móvil. Añadir aquí
las dependencias de la cámara sería una dependencia engañosa: parecería que el dominio sabe
de cámara y no es así.

## 4.2 Qué vive aquí

| Carpeta | Contenido |
|---|---|
| `value/` | `Id`, `Quantity`, `ProductCode`, `LocationCode`, `BoxQuantity`, `UnitsPerBox` — tipos que **no se pueden construir mal** |
| `common/` | `Either`, `DomainResult` |
| `error/` | `DomainError` — el catálogo de errores de negocio |
| `inventory/` | `StockMovement`, `InventoryLedger`, `Adjustment`, `LocationContent` |
| `receiving/` | `InvoiceLine`, la máquina de estados |
| `access/` | `PermissionCatalog`, `AccessEngine`, `AccessModel`, `RoleDefinitions`, `StockPolicies` — RBAC + ABAC |
| `audit/` | `AuditModel`, `AccessDecisionTrace`, `AuditableUseCase` |
| `catalog/` | `CatalogImporter` — el modelo de la importación de catálogo |
| `fulfillment/` | `Order`, la máquina de estados del surtido |
| `identity/` | `PasswordPolicy`, `SessionModel` |
| `labeling/` | `LabelTemplate` |
| `scanner/` | `ScanModel` — el contrato del escáner |
| `transfer/` | `Transfer`, la máquina de estados del traslado |

## 4.3 Por qué las reglas viven en el dominio

**Una regla en el dominio se puede probar en milisegundos y se cumple en las tres partes.**
Una regla en un endpoint se prueba por HTTP y se olvida en el siguiente.

Ejemplo real: `AdjustmentRequest.create`
(`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/inventory/Adjustment.kt`) exige
cantidad **positiva**: rechaza `quantity.isZero || quantity.isNegative`. Si solo se
comprobara `isZero`, una cantidad **negativa** con dirección `DECREASE` **sumaría inventario
sin necesitar aprobación**. La comprobación vive en la fábrica del tipo y no en el endpoint,
porque **la próxima ruta que se añada tendría el mismo agujero**.

**Corolario:** si puedes construir un objeto inválido, el tipo está mal diseñado. Se usan
constructores privados con fábricas que devuelven `Either`.

La regla de acceso —RBAC + ABAC y denegación por defecto— vive en `access/` y se documenta en
[roles-permisos.md](../seguridad/roles-permisos.md); la decisión, en
[ADR 0006](../adr/0006-rbac-mas-abac-denegar-por-defecto.md).
