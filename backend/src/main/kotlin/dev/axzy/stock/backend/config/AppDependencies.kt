package dev.axzy.stock.backend.config

import dev.axzy.stock.backend.audit.AuditWriter
import dev.axzy.stock.backend.inventory.InventoryService
import dev.axzy.stock.backend.receiving.ReceivingService
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CONTENEDOR DE DEPENDENCIAS — instancias ÚNICAS, construidas en un solo sitio
 *
 * **POR QUÉ EXISTE.** Antes, cada servicio construía lo que necesitaba por su cuenta:
 *
 *     class InventoryService(dataSource) {
 *         private val audit = AuditWriter(dataSource)   // ← su propia instancia
 *     }
 *     class ReceivingService(dataSource) {
 *         private val audit = AuditWriter(dataSource)   // ← y otra
 *     }
 *
 * Con dos servicios eran **dos `AuditWriter`**. Con cinco, cinco. Y ninguna era un
 * error visible —`AuditWriter` no guarda estado, así que funcionaba— pero el día que
 * necesitara caché, una métrica o un contador, **cada copia tendría el suyo** y el
 * total sería la suma de partes que no se ven entre sí.
 *
 * Eso es exactamente lo que el patrón **Singleton** evita, y no se consigue con
 * `object`: se consigue **decidiendo en un sitio quién existe y cuántas veces**.
 *
 * **El contenedor es la lista de lo que la aplicación necesita para funcionar.** Si
 * alguien pregunta «¿de qué depende este backend?», la respuesta es este archivo.
 *
 * ---
 *
 * ## `by lazy` Y NO `val` A SECAS: el orden de construcción importa
 *
 * Los servicios dependen del `AuditWriter`. Si se construyeran todos en el constructor
 * del contenedor, el orden de las líneas decidiría si la dependencia ya existe — y
 * **reordenar dos propiedades rompería la aplicación sin tocar la lógica**.
 *
 * Con `by lazy` cada dependencia se construye **la primera vez que se pide**, así que el
 * orden deja de importar. Y sigue siendo única: `lazy` cachea el resultado.
 *
 * ## `SYNCHRONIZED` (el valor por defecto) Y NO `NONE`
 *
 * Ktor atiende peticiones en paralelo. Con `LazyThreadSafetyMode.NONE`, dos peticiones
 * simultáneas podrían construir **dos** instancias y el singleton se perdería justo
 * cuando nadie mira. El coste de `SYNCHRONIZED` se paga **una vez**, en la primera
 * petición, y a cambio la unicidad está garantizada.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class AppDependencies(private val dataSource: DataSource) {

    /**
     * El escritor de auditoría: **uno para toda la aplicación**.
     *
     * Es el que más importa que sea único. Si hubiera varias instancias y algún día
     * llevara un contador de registros o una caché del último hash, cada una tendría su
     * propia verdad — y la cadena de auditoría depende de que **haya una sola**.
     *
     * (La cadena en sí la garantiza el `pg_advisory_xact_lock` de la base, no esta
     * línea. Pero tener una instancia única evita tener que razonar sobre el problema
     * dos veces.)
     */
    val auditWriter: AuditWriter by lazy { AuditWriter(dataSource) }

    val inventoryService: InventoryService by lazy {
        InventoryService(dataSource, auditWriter)
    }

    val receivingService: ReceivingService by lazy {
        ReceivingService(dataSource, auditWriter)
    }
}
