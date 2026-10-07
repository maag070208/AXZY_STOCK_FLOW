package dev.axzy.stock.mobile.core.data

import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PUERTOS DE DATOS DE LA APP MÓVIL
 *
 * Son INTERFACES y son `suspend` a propósito, aunque hoy las implemente un objeto
 * en memoria. Los dos motivos:
 *
 *  · `suspend` porque cuando llegue `stock-network` las implementaciones harán
 *    E/S. Si estas firmas fueran síncronas, integrar la red obligaría a cambiar
 *    todas las pantallas que ya las usan — es decir, a rehacer el trabajo dos
 *    veces. Declararlas `suspend` desde ahora cuesta cero.
 *
 *  · INTERFAZ porque así la app arranca y es revisable **sin backend**, que es lo
 *    que permite trabajar en paralelo con quien construye `stock-network` sin
 *    bloquearse mutuamente.
 *
 * LO QUE ESTOS PUERTOS NO SON: el contrato HTTP. La traducción entre el JSON y
 * estos tipos es responsabilidad de `stock-network` (ver mobile-app/README.md,
 * sección «Qué se necesita de stock-network», con las firmas exactas).
 * ═══════════════════════════════════════════════════════════════════════════
 */
interface CatalogRepository {
    suspend fun searchProducts(query: String): List<ProductView>
    suspend fun productByCode(code: ProductCode): ProductView?
    suspend fun productById(id: Long): ProductView?

    suspend fun locations(): List<LocationView>
    suspend fun locationByCode(code: LocationCode): LocationView?
    suspend fun locationContent(locationId: Long): List<StockLevelView>
}

interface InventoryRepository {
    suspend fun stockForProduct(productId: Long): List<StockLevelView>
    suspend fun kardexForProduct(code: ProductCode): List<KardexEntryView>
}

interface ReceivingRepository {
    suspend fun invoices(): List<InvoiceView>
    suspend fun invoice(id: Long): InvoiceView?
    suspend fun invoiceLines(invoiceId: Long): List<InvoiceLineView>

    /**
     * Confirma la captura física de una línea.
     *
     * La `idempotencyKey` NO es opcional ni tiene valor por defecto: el contrato
     * la exige en toda operación que mueve inventario o crea documentos, y el móvil
     * **retienta sin señal**. Sin clave, un reintento duplica un movimiento. Hacerla
     * obligatoria en la firma impide que una pantalla nueva se olvide de pasarla.
     */
    suspend fun captureLine(
        invoiceId: Long,
        lineId: Long,
        physicalQuantity: Double,
        idempotencyKey: String,
    ): Result<InvoiceLineView>
}

interface FulfillmentRepository {
    suspend fun orders(): List<OrderView>
    suspend fun order(id: Long): OrderView?
}

interface LabelingRepository {
    suspend fun templates(): List<LabelTemplateView>
    suspend fun printJobs(status: String? = null): List<PrintJobView>
    suspend fun print(templateId: Long, copies: Int, data: List<Map<String, String>>): Result<List<Long>>
}

interface HomeRepository {
    suspend fun categories(): List<HomeCategory>
}

/**
 * Puertos de la app, en un solo objeto para no repetir siete parámetros en cada
 * ViewModel. Es **inyección manual**: Koin llega en la Fase 6 completa y montarlo
 * ahora por dos dependencias sería ceremonia sin beneficio.
 */
data class AppRepositories(
    val catalog: CatalogRepository,
    val inventory: InventoryRepository,
    val receiving: ReceivingRepository,
    val fulfillment: FulfillmentRepository,
    val labeling: LabelingRepository,
    val home: HomeRepository,
)
