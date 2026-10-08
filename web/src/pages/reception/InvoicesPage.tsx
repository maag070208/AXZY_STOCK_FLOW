import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { get } from "@shared/api";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * FACTURAS — ENTRADA A LA RECEPCIÓN
 *
 * **El contrato NO tiene endpoint de listado, y esta pantalla no lo inventa.**
 * El backend solo expone `GET /v1/invoices/{id}` y
 * `GET /v1/invoices/pending-approval` (`ReceivingRoutes.kt`). `GET /v1/invoices`
 * responde **405**: esa ruta existe, pero solo acepta `POST` (alta de factura).
 * Pedir un listado paginado que nadie ha implementado daría 404 en producción.
 *
 * DECISIÓN: **opción (a)** — mostrar la bandeja de pendientes (única fuente de
 * facturas que el backend sabe enumerar hoy) **más** un buscador que abre el
 * detalle por número o por id.
 *
 * Por qué (a) y no (b) —«reutilizar `pending-approval` sin nada más»—: la bandeja
 * solo contiene facturas con discrepancias sin aprobar. Una factura cuyas líneas
 * cuadran (`CAPTURED`) o que ya se aprobó desaparece de ella, y con (b) no habría
 * manera de volver a su detalle, que es donde vive el histórico de quién contó y
 * quién aprobó. El buscador cubre ese hueco **sin inventar API**: usa
 * `GET /v1/invoices/{id}`, que sí existe.
 *
 * ── Sobre la ruta que se le pasa a `get` ────────────────────────────────────
 * **Sin el prefijo `/v1`.** `API_CONSTANTS.BASE_URL` ya es `/v1` y axios
 * concatena: pedir `/v1/invoices/3` produce `/v1/v1/invoices/3`, que el backend
 * responde 404 (verificado con curl a través del proxy de Vite).
 *
 * ── Sobre la «diferencia total» de la bandeja ───────────────────────────────
 * El backend la calcula con `SUM(ABS(l.difference))`, así que aquí llega la
 * **magnitud** (20.000), sin signo. El signo —faltante o sobrante— está en cada
 * línea, en el detalle de la factura. No se reescribe ni se recalcula en el
 * cliente: se muestra el número del servidor y se explica abajo qué significa.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Fila real de `GET /v1/invoices/pending-approval` (`PendienteDto`). */
interface FacturaPendiente {
  invoiceId: number;
  invoiceNumber: string;
  receiptDate: string;
  status: string;
  pendingLines: number;
  totalDifference: string;
}

/**
 * El backend devuelve los estados como texto del enum de la base. Se traducen
 * aquí y, si llegara uno nuevo, se muestra el código tal cual en vez de
 * esconderlo: un estado desconocido es información, no un hueco.
 */
const ETIQUETA_ESTADO: Record<string, string> = {
  PENDING: "Pendiente",
  IN_PROGRESS: "En proceso",
  PENDING_TO_LOCATE: "Pendiente de ubicar",
  COMPLETED: "Completada",
  CANCELLED: "Cancelada",
};

const etiquetaEstado = (estado: string): string => ETIQUETA_ESTADO[estado] ?? estado;

export default function InvoicesPage() {
  const navigate = useNavigate();

  const [pendientes, setPendientes] = useState<FacturaPendiente[]>([]);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Estado del buscador. `busquedaError` es distinto de `error`: uno es «no se
  // pudo leer la bandeja» y el otro «lo que escribiste no se puede resolver».
  const [busqueda, setBusqueda] = useState("");
  const [busquedaError, setBusquedaError] = useState<string | null>(null);

  const cargar = useCallback(async () => {
    setCargando(true);
    setError(null);
    try {
      const respuesta = await get<{ items: FacturaPendiente[] }>("/invoices/pending-approval");
      setPendientes(respuesta.items ?? []);
    } catch (e) {
      setError(e instanceof Error ? e.message : "No se pudieron cargar las facturas pendientes.");
    } finally {
      setCargando(false);
    }
  }, []);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  /**
   * Abre el detalle de una factura.
   *
   * El id numérico es **la única clave de búsqueda que el backend acepta**, así
   * que se navega directo. Si el número no es un id, se resuelve contra las
   * facturas que la bandeja ya trajo; si no está entre ellas se dice por qué en
   * lugar de fingir que se buscó en todo el histórico: no hay endpoint que
   * permita buscar en el histórico completo.
   */
  function abrirFactura(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault();
    const texto = busqueda.trim();

    if (!texto) {
      setBusquedaError("Escribe el número de factura o su id.");
      return;
    }

    setBusquedaError(null);

    if (/^\d+$/.test(texto)) {
      // El detalle avisa con `INVOICE_NOT_FOUND` si esa factura no existe; no
      // se comprueba aquí para no hacer dos peticiones de lo mismo.
      navigate(`/invoices/${texto}`);
      return;
    }

    const encontrada = pendientes.find(
      (p) => p.invoiceNumber.toLowerCase() === texto.toLowerCase()
    );

    if (encontrada) {
      navigate(`/invoices/${encontrada.invoiceId}`);
      return;
    }

    setBusquedaError(
      `No hay ninguna factura pendiente con el número «${texto}». ` +
        "El backend todavía no expone búsqueda por número —solo el detalle por id—, " +
        "así que hoy únicamente se pueden abrir por número las facturas de esta bandeja."
    );
  }

  return (
    <div style={{ padding: 24 }}>
      <h1 style={{ color: "#16315F", marginBottom: 4 }}>Facturas</h1>
      <p style={{ color: "#4A5A72", maxWidth: 760 }}>
        Recepción de mercancía. Esta pantalla muestra las facturas que{" "}
        <strong>esperan una aprobación</strong> y permite abrir cualquier factura por su
        número o su id.
      </p>

      {/* ── Buscador ─────────────────────────────────────────────────────── */}
      <form
        onSubmit={abrirFactura}
        style={{
          background: "#F2F6FC",
          border: "1px solid #E4EBF5",
          padding: 16,
          marginTop: 16,
          display: "flex",
          gap: 8,
          alignItems: "flex-end",
          flexWrap: "wrap",
        }}
      >
        <label style={{ display: "grid", gap: 4 }}>
          <span style={{ color: "#16315F", fontWeight: 600, fontSize: 13 }}>
            Número de factura o id
          </span>
          <input
            value={busqueda}
            onChange={(e) => {
              setBusqueda(e.target.value);
              // El aviso anterior ya no corresponde a lo que hay escrito.
              setBusquedaError(null);
            }}
            placeholder="FAC-WEB-1  ó  3"
            style={{ padding: "8px 10px", border: "1px solid #C7D3E6", minWidth: 260 }}
          />
        </label>
        <button
          type="submit"
          style={{ background: "#16315F", color: "#fff", padding: "9px 18px", border: 0 }}
        >
          Abrir factura
        </button>
      </form>

      {busquedaError && (
        <p role="alert" style={{ color: "#C0392B", marginTop: 12 }}>
          {busquedaError}
        </p>
      )}

      {/* ── Bandeja de pendientes ────────────────────────────────────────── */}
      <div
        style={{
          marginTop: 24,
          display: "flex",
          justifyContent: "space-between",
          alignItems: "baseline",
          gap: 12,
          flexWrap: "wrap",
        }}
      >
        <h2 style={{ color: "#16315F", margin: 0 }}>Pendientes de aprobación</h2>
        <Link
          to="/invoices/pending-approval"
          style={{ color: "#16315F", fontWeight: 600 }}
        >
          Aprobar diferencias →
        </Link>
      </div>
      <p style={{ color: "#4A5A72", maxWidth: 760 }}>
        Facturas con diferencias entre lo facturado y lo contado.{" "}
        <strong>Mientras no se aprueben, sus líneas no se pueden ubicar.</strong> La
        aprobación se hace en la bandeja, no aquí.
      </p>

      {cargando && <p style={{ padding: 8 }}>Cargando facturas…</p>}

      {!cargando && error && (
        <div style={{ padding: 8 }}>
          <p role="alert" style={{ color: "#C0392B" }}>
            {error}
          </p>
          <button type="button" onClick={() => void cargar()}>
            Reintentar
          </button>
        </div>
      )}

      {!cargando && !error && pendientes.length === 0 && (
        <p style={{ color: "#2E7D5B", background: "#E8F5EE", padding: 16 }}>
          No hay facturas pendientes de aprobación. Para abrir una factura ya resuelta,
          usa el buscador de arriba con su número o su id.
        </p>
      )}

      {!cargando && !error && pendientes.length > 0 && (
        <>
          <table style={{ width: "100%", borderCollapse: "collapse", marginTop: 8 }}>
            <thead>
              <tr style={{ background: "#E4EBF5", color: "#16315F", textAlign: "left" }}>
                <th style={{ padding: 10 }}>Factura</th>
                <th style={{ padding: 10 }}>Fecha</th>
                <th style={{ padding: 10 }}>Estado</th>
                <th style={{ padding: 10 }}>Líneas por aprobar</th>
                <th style={{ padding: 10 }}>Diferencia total</th>
                <th style={{ padding: 10 }} />
              </tr>
            </thead>
            <tbody>
              {pendientes.map((p) => (
                <tr key={p.invoiceId} style={{ borderBottom: "1px solid #E4EBF5" }}>
                  <td style={{ padding: 10, fontWeight: 600 }}>{p.invoiceNumber}</td>
                  <td style={{ padding: 10 }}>{p.receiptDate}</td>
                  <td style={{ padding: 10 }}>{etiquetaEstado(p.status)}</td>
                  <td style={{ padding: 10 }}>{p.pendingLines}</td>
                  <td
                    style={{ padding: 10, color: "#C0392B", fontWeight: 600 }}
                    title="Suma de los valores absolutos de las diferencias pendientes"
                  >
                    {p.totalDifference}
                  </td>
                  <td style={{ padding: 10, textAlign: "right" }}>
                    <Link to={`/invoices/${p.invoiceId}`} style={{ color: "#16315F" }}>
                      Abrir detalle
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <p style={{ color: "#4A5A72", fontSize: 13, marginTop: 10 }}>
            La diferencia total suma el <strong>valor absoluto</strong> de cada diferencia
            (así la calcula el backend), por eso aquí se ve la magnitud. El signo —faltante
            o sobrante— y la cantidad contada están línea por línea en el detalle.
          </p>
        </>
      )}
    </div>
  );
}
