package dev.axzy.stock.network.api

import dev.axzy.stock.network.client.StockHttpClient
import dev.axzy.stock.network.client.decode
import dev.axzy.stock.network.client.stockJson
import dev.axzy.stock.network.dto.common.ListQuery
import dev.axzy.stock.network.dto.common.Page
import dev.axzy.stock.network.dto.product.CreateProductRequest
import dev.axzy.stock.network.dto.product.DeactivateProductRequest
import dev.axzy.stock.network.dto.product.ImportReportDto
import dev.axzy.stock.network.dto.product.ProductDto
import dev.axzy.stock.network.dto.product.UpdateProductRequest
import dev.axzy.stock.network.error.ApiOutcome
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.encodeToString

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * `/v1/products` — catálogo
 *
 * Contrato: docs/historico/CONTRATO_API.md §3.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ProductsApi internal constructor(private val http: StockHttpClient) {

    /**
     * Lista paginada. `sort` admitido: `productCode`, `name`, `createdAt`.
     *
     * El listado **ya trae `stock`**: es el dato que más se consulta y evitar una
     * petición por producto importa con 1.398 referencias.
     */
    suspend fun list(query: ListQuery = ListQuery()): ApiOutcome<Page<ProductDto>> =
        http.getText("/products", query.toParams()).decode()

    suspend fun get(id: Long): ApiOutcome<ProductDto> = http.getText("/products/$id").decode()

    suspend fun create(request: CreateProductRequest): ApiOutcome<ProductDto> =
        http.postText("/products", stockJson.encodeToString(request)).decode()

    suspend fun update(id: Long, request: UpdateProductRequest): ApiOutcome<ProductDto> =
        http.putText("/products/$id", stockJson.encodeToString(request)).decode()

    /**
     * Baja **lógica** (`active = false`) con motivo obligatorio.
     *
     * Si el producto tiene stock, el servidor responde `409
     * PRODUCT_HAS_STOCK` con `details.stock` y hay que forzar explícitamente.
     * El cliente no fuerza por su cuenta: forzar es una decisión del usuario, y
     * tomarla en silencio desactivaría una referencia con existencias.
     */
    suspend fun deactivate(id: Long, request: DeactivateProductRequest): ApiOutcome<Unit> =
        http.deleteNoContent("/products/$id", stockJson.encodeToString(request))

    /**
     * Importa un CSV o XLSX.
     *
     * Con `dryRun = true` devuelve el informe **sin escribir nada**: es la
     * primera ejecución sobre datos reales, y sin esa simulación el import es un
     * acto de fe.
     *
     * El archivo viaja como `multipart/form-data` con el campo `file`, tal como
     * lo declara el contrato. **No se convierte a base64 dentro de un JSON**:
     * cambiar la forma del transporte por comodidad del cliente es cómo los
     * contratos dejan de ser un contrato.
     */
    suspend fun importFile(
        fileBytes: ByteArray,
        fileName: String,
        dryRun: Boolean,
        contentType: String = "text/csv",
    ): ApiOutcome<ImportReportDto> =
        http.postMultipart("/products/import") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            key = "file",
                            value = fileBytes,
                            headers = Headers.build {
                                append(HttpHeaders.ContentType, contentType)
                                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                            },
                        )
                        append("dryRun", dryRun.toString())
                    }
                )
            )
        }.decode()
}
