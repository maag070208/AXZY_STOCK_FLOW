package dev.axzy.stock.backend.config

import dev.axzy.stock.backend.application.transfer.TransferUseCases
import dev.axzy.stock.backend.infrastructure.outbound.persistence.audit.AuditWriter
import dev.axzy.stock.backend.infrastructure.outbound.persistence.transfer.JdbcTransferRepository
import dev.axzy.stock.backend.inventory.InventoryService
import dev.axzy.stock.backend.receiving.ReceivingService
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * COMPOSICIÓN — el único sitio donde se decide quién existe y cuántas veces
 *
 * Aquí se une cada caso de uso con el adaptador que cumple su puerto. **Es el único archivo
 * que conoce las dos mitades**: la aplicación no importa infraestructura, y la infraestructura
 * no decide con qué se monta. Si alguien pregunta «¿de qué depende este backend?», la
 * respuesta es este archivo.
 *
 * ## `by lazy` y no `val` a secas
 *
 * Unas dependencias necesitan a otras. Si se construyeran todas en el constructor, el orden de
 * las líneas decidiría si la dependencia ya existe, y reordenar dos propiedades rompería la
 * aplicación sin tocar la lógica. Con `by lazy` cada una se construye la primera vez que se
 * pide, y sigue siendo única: `lazy` cachea el resultado.
 *
 * ## `SYNCHRONIZED` (el valor por defecto) y no `NONE`
 *
 * Ktor atiende peticiones en paralelo. Con `LazyThreadSafetyMode.NONE`, dos peticiones
 * simultáneas podrían construir dos instancias y la unicidad se perdería justo cuando nadie
 * mira. El coste de `SYNCHRONIZED` se paga una vez, en la primera petición.
 *
 * ## Nunca `object`
 *
 * Un `object` no se puede sustituir en una prueba ni acepta un `DataSource`. Las pruebas montan
 * este mismo contenedor con su propia base, así que prueban el cableado real.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class AppDependencies(private val dataSource: DataSource) {

    /**
     * El escritor de auditoría: **uno para toda la aplicación**. La cadena de hash la garantiza
     * el `pg_advisory_xact_lock` de la base, no esta línea; pero una instancia única evita tener
     * que razonar sobre el problema dos veces.
     */
    val auditWriter: AuditWriter by lazy { AuditWriter(dataSource) }

    val inventoryService: InventoryService by lazy { InventoryService(dataSource, auditWriter) }

    val receivingService: ReceivingService by lazy { ReceivingService(dataSource, auditWriter) }

    val transfer: TransferUseCases by lazy { TransferUseCases(JdbcTransferRepository(dataSource, auditWriter)) }
}
