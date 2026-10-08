import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { get, post } from "@shared/api";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * DETALLE DE FACTURA — CAPTURA FÍSICA Y ESTADO DE LA REGLA B1
 *
 * Tres reglas que esta pantalla respeta y no vuelve a implementar:
 *
 *  1. **La `difference` la calcula la BASE**, con una columna generada
 *     (`physical_quantity - quantity`, ver `V3__inventory_domain.sql`). El legacy
 *     la declaraba en modelo, DTO, mapper y respuesta, y siempre valía 0 (R-58).
 *     Aquí solo se pinta el número que llega; no se recalcula nunca.
 *  2. **Una línea en `DISCREPANCY` no se puede ubicar hasta aprobar** (regla B1).
 *     Por eso, si la factura tiene discrepancias, se avisa y se enlaza a la
 *     bandeja de aprobación: sin ese aviso, el usuario vería el botón de ubicar
 *     bloqueado sin saber qué falta.
 *  3. **Aprobar es por factura, no por línea** (R-86), así que aquí no hay ningún
 *     botón de aprobar: eso vive en `/invoices/pending-approval`.
 *
 * La captura **no manda una cantidad por defecto**: el formulario se abre vacío y
 * hay que escribir el conteo. Un valor sugerido que nadie revisó sería un conteo
 * que nadie hizo, y dentro de un año explicaría una diferencia sin fundamento.
 *
 * ── Sobre las rutas que se pasan a `get`/`post` ─────────────────────────────
 * **Sin el prefijo `/v1`**: `API_CONSTANTS.BASE_URL` ya es `/v1` y axios concatena.
 * `/v1/invoices/3` acaba pidiendo `/v1/v1/invoices/3`, que el backend responde 404.
 *
 * ── Sobre los campos ausentes en la respuesta ───────────────────────────────
 * El backend serializa con `explicitNulls = false`: un campo sin valor **no
 * aparece** en el JSON (comprobado con curl: una línea `PENDING` llega sin
 * `physicalQuantity`, sin `difference` y sin `capturedAt`). Por eso todo lo que
 * puede ser nulo se tipa opcional: ausente y `null` son lo mismo aquí.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** `LineaDto` de `ReceivingService.kt`, tal cual llega por HTTP. */
interface LineaDto {
  id: number;
  productId: number;
  productCode: string;
  productName: string;
  container?: string | null;
  /** Cantidades como texto decimal ("500.000"): el backend manda `BigDecimal`. */
  quantity: string;
  boxQuantity: string;
  unitsPerBox?: string | null;
  physicalQuantity?: string | null;
  physicalBoxQuantity?: string | null;
  locatedQuantity: string;
  /** `physical_quantity - quantity`, columna generada. Nula mientras no se cuente. */
  difference?: string | null;
  status: string;
  capturedBy?: number | null;
  capturedByName?: string | null;
  capturedAt?: string | null;
  approvedBy?: number | null;
  approvedByName?: string | null;
  approvedAt?: string | null;
  adjustmentReason?: string | null;
  locationDisplayName?: string | null;
}

/** `FacturaDetalle` de `ReceivingService.kt`. */
interface FacturaDetalle {
  id: number;
  invoiceNumber: string;
  receiptDate: string;
  invoiceType: string;
  status: string;
  customerCode?: string | null;
  concept?: string | null;
  totalLines: number;
  pendingApprovalLines: number;
  lines: LineaDto[];
}

const ETIQUETA_ESTADO: Record<string, string> = {
  PENDING: "Sin contar",
  CAPTURED: "Contada (coincide)",
  DISCREPANCY: "Discrepancia",
  PENDING_APPROVAL: "Pendiente de aprobación",
  APPROVED: "Aprobada",
  ASSIGNED: "Asignada",
  LOCATED: "Ubicada",
  CANCELLED: "Cancelada",
};

const ETIQUETA_FACTURA: Record<string, string> = {
  PENDING: "Pendiente",
  IN_PROGRESS: "En proceso",
  PENDING_TO_LOCATE: "Pendiente de ubicar",
  COMPLETED: "Completada",
  CANCELLED: "Cancelada",
};

const ETIQUETA_TIPO: Record<string, string> = {
  NATIONAL: "Nacional",
  IMPORT: "Importación",
  SERVICE: "Servicio",
};

const ETIQUETA_MOTIVO: Record<string, string> = {
  SHORTAGE: "Faltante",
  SUPPLIER_ERROR: "Error del proveedor",
  COUNT_ERROR: "Error de conteo",
  DAMAGE: "Daño",
  THEFT: "Robo",
  RETURN_TO_SUPPLIER: "Devolución al proveedor",
  EXPIRED: "Caducado",
  OTHER: "Otro",
};

/**
 * Estados en los que el backend **rechaza** volver a capturar: `capturarLinea`
 * lanza `409 LINE_ALREADY_RESOLVED` para `APPROVED` y `LOCATED`. Se replica
 * exactamente esa lista —ni más ni menos— para no ofrecer un botón que va a
 * fallar, pero sin inventar prohibiciones que el servidor no tiene.
 */
const ESTADOS_SIN_CAPTURA = new Set(["APPROVED", "LOCATED"]);

const etiqueta = (mapa: Record<string, string>, codigo: string): string =>
  mapa[codigo] ?? codigo;

/** La `difference` llega nula mientras no se cuenta: no es un cero. */
const formatoDiferencia = (diferencia: string | null | undefined): string =>
  diferencia === null || diferencia === undefined ? "—" : diferencia;

const esDiferenciaNoCero = (diferencia: string | null | undefined): boolean =>
  diferencia !== null && diferencia !== undefined && Number(diferencia) !== 0;

/** Marca de tiempo del servidor (ISO UTC) a hora local legible. */
const fechaHora = (iso: string | null | undefined): string => {
  if (!iso) return "";
  const fecha = new Date(iso);
  if (Number.isNaN(fecha.getTime())) return iso;
  return fecha.toLocaleString("es-MX", { dateStyle: "short", timeStyle: "short" });
};

export default function InvoiceDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [detalle, setDetalle] = useState<FacturaDetalle | null>(null);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Captura: qué línea tiene el formulario abierto y lo que se ha escrito.
  const [lineaEnCaptura, setLineaEnCaptura] = useState<number | null>(null);
  const [cantidadFisica, setCantidadFisica] = useState("");
  const [cajasFisicas, setCajasFisicas] = useState("");
  const [enviando, setEnviando] = useState(false);
  const [errorCaptura, setErrorCaptura] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);

  const cargar = useCallback(async () => {
    // El id llega de la URL: si no es un número, no hay petición que valga.
    if (!id || !/^\d+$/.test(id)) {
      setError("La dirección no trae un id de factura válido.");
      setCargando(false);
      return;
    }

    setCargando(true);
    setError(null);
    try {
      const factura = await get<FacturaDetalle>(`/invoices/${id}`);
      setDetalle(factura);
    } catch (e) {
      // El mensaje del backend ya dice cuál falta ("La factura 3 no existe."),
      // que es más útil que un "error al cargar" genérico.
      setError(e instanceof Error ? e.message : "No se pudo cargar la factura.");
    } finally {
      setCargando(false);
    }
  }, [id]);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  function abrirCaptura(lineaId: number) {
    setLineaEnCaptura(lineaId);
    setCantidadFisica("");
    setCajasFisicas("");
    setErrorCaptura(null);
    setAviso(null);
  }

  /**
   * Registra el conteo físico de una línea.
   *
   * `physicalQuantity` es obligatorio y **se valida antes de enviar**: el
   * formulario se abre vacío, así que llegar aquí con el campo en blanco significa
   * que no se contó nada y no se debe inventar un cero (un cero es un faltante
   * total, no un conteo pendiente).
   */
  async function capturar(facturaId: number, lineaId: number) {
    const texto = cantidadFisica.trim();
    const valor = Number(texto);

    if (texto === "" || !Number.isFinite(valor)) {
      setErrorCaptura("Escribe la cantidad contada antes de guardar.");
      return;
    }
    if (valor < 0) {
      setErrorCaptura("La cantidad contada no puede ser negativa.");
      return;
    }

    const textoCajas = cajasFisicas.trim();
    const cajas = textoCajas === "" ? null : Number(textoCajas);
    if (cajas !== null && (!Number.isFinite(cajas) || cajas < 0)) {
      setErrorCaptura("Las cajas contadas deben ser un número no negativo.");
      return;
    }

    setEnviando(true);
    setErrorCaptura(null);
    setAviso(null);
    try {
      // La respuesta es la línea completa recalculada por el servidor, y se
      // aprovecha para decir de inmediato en qué estado quedó: es la información
      // que decide si hay que aprobar algo.
      const linea = await post<LineaDto>(`/invoices/${facturaId}/lines/${lineaId}/capture`, {
        physicalQuantity: valor,
        ...(cajas === null ? {} : { physicalBoxQuantity: cajas }),
      });

      setLineaEnCaptura(null);
      setCantidadFisica("");
      setCajasFisicas("");
      setAviso(
        `Conteo guardado. La línea quedó en ${etiqueta(ETIQUETA_ESTADO, linea.status)} ` +
          `con diferencia ${formatoDiferencia(linea.difference)}.`
      );

      // Se recarga la factura porque también cambia su estado agregado
      // (`status` y `pendingApprovalLines`), no solo la línea capturada.
      await cargar();
    } catch (e) {
      // 409 LINE_ALREADY_RESOLVED, 422 de la cantidad… el mensaje del backend es
      // el que explica el caso; sustituirlo por uno genérico perdería el motivo.
      setErrorCaptura(e instanceof Error ? e.message : "No se pudo guardar el conteo.");
    } finally {
      setEnviando(false);
    }
  }

  if (cargando) {
    return <p style={{ padding: 24 }}>Cargando factura…</p>;
  }

  if (error || !detalle) {
    return (
      <div style={{ padding: 24 }}>
        <p role="alert" style={{ color: "#C0392B" }}>
          {error ?? "No se pudo cargar la factura."}
        </p>
        <div style={{ display: "flex", gap: 8 }}>
          <button type="button" onClick={() => void cargar()}>
            Reintentar
          </button>
          <button type="button" onClick={() => navigate("/invoices")}>
            Volver al listado
          </button>
        </div>
      </div>
    );
  }

  // `pendingApprovalLines` lo cuenta el servidor; la lista solo sirve para decir
  // QUÉ productos están bloqueados, no para decidir si hay bloqueo.
  const bloqueadas = detalle.lines.filter((l) => l.status === "DISCREPANCY");

  return (
    <div style={{ padding: 24 }}>
      <button
        type="button"
        onClick={() => navigate("/invoices")}
        style={{ background: "none", border: 0, color: "#16315F", padding: 0, cursor: "pointer" }}
      >
        ← Volver al listado
      </button>

      <h1 style={{ color: "#16315F", marginBottom: 4 }}>Factura {detalle.invoiceNumber}</h1>
      <p style={{ color: "#4A5A72", marginTop: 0 }}>
        {detalle.receiptDate} · {etiqueta(ETIQUETA_TIPO, detalle.invoiceType)} ·{" "}
        <strong>{etiqueta(ETIQUETA_FACTURA, detalle.status)}</strong> · {detalle.totalLines}{" "}
        {detalle.totalLines === 1 ? "línea" : "líneas"}
        {detalle.customerCode ? ` · Cliente ${detalle.customerCode}` : ""}
        {detalle.concept ? ` · ${detalle.concept}` : ""}
      </p>

      {/* ── Regla B1: el aviso que evita el callejón sin salida ───────────── */}
      {detalle.pendingApprovalLines > 0 && (
        <div
          role="alert"
          style={{
            background: "#FDECEA",
            border: "1px solid #F5C6C0",
            borderLeft: "4px solid #C0392B",
            color: "#C0392B",
            padding: 14,
            marginBottom: 16,
          }}
        >
          <strong>
            {detalle.pendingApprovalLines}{" "}
            {detalle.pendingApprovalLines === 1 ? "línea" : "líneas"} con discrepancia: no
            se pueden ubicar todavía.
          </strong>
          <p style={{ margin: "6px 0 0" }}>
            Lo contado no coincide con lo facturado, así que la diferencia tiene que
            autorizarla un supervisor <strong>antes</strong> de ubicar la mercancía. Si se
            ubicara ahora, la diferencia desaparecería del inventario sin que nadie la
            aprobara.
          </p>
          {bloqueadas.length > 0 && (
            <p style={{ margin: "6px 0 0" }}>
              Bloqueadas:{" "}
              {bloqueadas
                .map((l) => `${l.productCode} (${formatoDiferencia(l.difference)})`)
                .join(", ")}
              .
            </p>
          )}
          <p style={{ margin: "10px 0 0" }}>
            <Link to="/invoices/pending-approval" style={{ color: "#16315F", fontWeight: 600 }}>
              Ir a la bandeja de aprobación →
            </Link>
          </p>
        </div>
      )}

      {aviso && (
        <p role="status" style={{ color: "#2E7D5B", background: "#E8F5EE", padding: 12 }}>
          {aviso}
        </p>
      )}

      <h2 style={{ color: "#16315F", marginTop: 20 }}>Líneas</h2>
      <table style={{ width: "100%", borderCollapse: "collapse" }}>
        <thead>
          <tr style={{ background: "#E4EBF5", color: "#16315F", textAlign: "left" }}>
            <th style={{ padding: 10 }}>Producto</th>
            <th style={{ padding: 10 }}>Facturado</th>
            <th style={{ padding: 10 }}>Contado</th>
            <th style={{ padding: 10 }}>Diferencia</th>
            <th style={{ padding: 10 }}>Estado</th>
            <th style={{ padding: 10 }}>Capturó</th>
            <th style={{ padding: 10 }}>Aprobó</th>
            <th style={{ padding: 10 }} />
          </tr>
        </thead>
        <tbody>
          {detalle.lines.map((linea) => {
            const sinCaptura = ESTADOS_SIN_CAPTURA.has(linea.status);
            return (
              <tr key={linea.id} style={{ borderBottom: "1px solid #E4EBF5" }}>
                <td style={{ padding: 10 }}>
                  <div style={{ fontWeight: 600 }}>{linea.productCode}</div>
                  <div style={{ color: "#4A5A72", fontSize: 13 }}>{linea.productName}</div>
                  {linea.container && (
                    <div style={{ color: "#4A5A72", fontSize: 12 }}>
                      Contenedor: {linea.container}
                    </div>
                  )}
                </td>

                <td style={{ padding: 10 }}>
                  {linea.quantity}
                  {linea.unitsPerBox && (
                    <div style={{ color: "#4A5A72", fontSize: 12 }}>
                      {linea.unitsPerBox} por caja
                    </div>
                  )}
                </td>

                <td style={{ padding: 10 }}>
                  {linea.physicalQuantity ?? "—"}
                  {linea.physicalBoxQuantity && (
                    <div style={{ color: "#4A5A72", fontSize: 12 }}>
                      {linea.physicalBoxQuantity} en cajas
                    </div>
                  )}
                </td>

                {/* `difference` es la columna generada del servidor. En rojo si no
                    es cero; nunca se recalcula aquí. */}
                <td
                  style={{
                    padding: 10,
                    fontWeight: 600,
                    color: esDiferenciaNoCero(linea.difference) ? "#C0392B" : "#4A5A72",
                  }}
                >
                  {formatoDiferencia(linea.difference)}
                </td>

                <td style={{ padding: 10 }}>
                  {etiqueta(ETIQUETA_ESTADO, linea.status)}
                  {linea.locationDisplayName && (
                    <div style={{ color: "#4A5A72", fontSize: 12 }}>
                      Ubicación: {linea.locationDisplayName}
                    </div>
                  )}
                </td>

                <td style={{ padding: 10 }}>
                  {linea.capturedByName ?? "—"}
                  {linea.capturedAt && (
                    <div style={{ color: "#4A5A72", fontSize: 12 }}>
                      {fechaHora(linea.capturedAt)}
                    </div>
                  )}
                </td>

                <td style={{ padding: 10 }}>
                  {linea.approvedByName ?? "—"}
                  {linea.adjustmentReason && (
                    <div style={{ color: "#4A5A72", fontSize: 12 }}>
                      {etiqueta(ETIQUETA_MOTIVO, linea.adjustmentReason)}
                    </div>
                  )}
                  {linea.approvedAt && (
                    <div style={{ color: "#4A5A72", fontSize: 12 }}>
                      {fechaHora(linea.approvedAt)}
                    </div>
                  )}
                </td>

                <td style={{ padding: 10, textAlign: "right", verticalAlign: "top" }}>
                  {sinCaptura ? (
                    <span style={{ color: "#4A5A72", fontSize: 13 }}>
                      {linea.status === "APPROVED"
                        ? "Aprobada: ya no se recuenta."
                        : "Ubicada: ya no se recuenta."}
                    </span>
                  ) : lineaEnCaptura === linea.id ? (
                    <form
                      onSubmit={(e: FormEvent<HTMLFormElement>) => {
                        e.preventDefault();
                        void capturar(detalle.id, linea.id);
                      }}
                      style={{ display: "grid", gap: 6, justifyItems: "end" }}
                    >
                      <label style={{ display: "grid", gap: 2, fontSize: 12 }}>
                        <span>Cantidad contada</span>
                        <input
                          type="number"
                          step="0.001"
                          min="0"
                          value={cantidadFisica}
                          onChange={(e) => setCantidadFisica(e.target.value)}
                          aria-label={`Cantidad contada de ${linea.productCode}`}
                          autoFocus
                          style={{ padding: 6, width: 130, border: "1px solid #C7D3E6" }}
                        />
                      </label>
                      <label style={{ display: "grid", gap: 2, fontSize: 12 }}>
                        <span>Cajas (opcional)</span>
                        <input
                          type="number"
                          step="0.001"
                          min="0"
                          value={cajasFisicas}
                          onChange={(e) => setCajasFisicas(e.target.value)}
                          aria-label={`Cajas contadas de ${linea.productCode}`}
                          style={{ padding: 6, width: 130, border: "1px solid #C7D3E6" }}
                        />
                      </label>
                      <div style={{ display: "flex", gap: 8 }}>
                        <button
                          type="submit"
                          disabled={enviando}
                          style={{ background: "#16315F", color: "#fff", padding: "6px 14px", border: 0 }}
                        >
                          {enviando ? "Guardando…" : "Guardar conteo"}
                        </button>
                        <button type="button" onClick={() => setLineaEnCaptura(null)}>
                          Cancelar
                        </button>
                      </div>
                      {errorCaptura && (
                        <p role="alert" style={{ color: "#C0392B", margin: 0, maxWidth: 240 }}>
                          {errorCaptura}
                        </p>
                      )}
                    </form>
                  ) : (
                    <button
                      type="button"
                      onClick={() => abrirCaptura(linea.id)}
                      style={{ background: "#F28C28", color: "#fff", padding: "6px 14px", border: 0 }}
                    >
                      {linea.physicalQuantity ? "Recapturar" : "Capturar conteo"}
                    </button>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      <p style={{ color: "#4A5A72", fontSize: 13, marginTop: 10 }}>
        La diferencia la calcula el servidor (<code>cantidad contada − cantidad facturada</code>):
        negativa es faltante y positiva, sobrante. Una línea ya aprobada o ubicada no se
        puede volver a contar.
      </p>
    </div>
  );
}
