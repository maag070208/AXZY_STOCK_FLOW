import { useCallback, useEffect, useState } from "react";
import { get, post } from "@shared/api";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * BANDEJA DE PENDIENTES DE APROBACIÓN
 *
 * **Pantalla nueva: el legacy no la tenía.** Sin ella, una línea con discrepancia
 * se quedaba bloqueada sin que nadie supiera que había que aprobarla, así que la
 * factura no se podía ubicar nunca. La regla B1 necesita esta pantalla para no ser
 * un callejón sin salida.
 *
 * **LAS RUTAS VAN SIN `/v1`, Y ES UN ERROR QUE COMETÍ AQUÍ.** `API_CONSTANTS.BASE_URL`
 * ya vale `/v1`, así que escribir `/v1/invoices/...` compone `/v1/v1/invoices/...` y el
 * backend responde 404. Lo encontró otro agente al copiar este archivo como patrón —yo
 * lo di por bueno porque pasaba `tsc` y `eslint`, y **ninguno de los dos comprueba una
 * URL en tiempo de ejecución**. Un error de ruta relativa solo se ve llamándola.
 *
 * Dos cosas que la pantalla NO hace, a propósito:
 *
 *  1. **No aprueba línea a línea** (decisión R-86). Aprobar de una en una obliga al
 *     supervisor a repetir el mismo juicio N veces e invita a aprobar lo propio sin
 *     darse cuenta. Una acción por factura.
 *  2. **No decide quién puede aprobar.** El backend comprueba la segregación de
 *     funciones y devuelve `SEGREGATION_OF_DUTIES` si el supervisor contó alguna de
 *     las líneas. Aquí solo se muestra el error: repetir esa regla en el cliente
 *     crearía dos versiones que se separarían con el tiempo.
 * ═══════════════════════════════════════════════════════════════════════════
 */

interface Pendiente {
  invoiceId: number;
  invoiceNumber: string;
  receiptDate: string;
  status: string;
  pendingLines: number;
  totalDifference: string;
}

const MOTIVOS = [
  { valor: "SHORTAGE", etiqueta: "Faltante" },
  { valor: "SUPPLIER_ERROR", etiqueta: "Error del proveedor" },
  { valor: "COUNT_ERROR", etiqueta: "Error de conteo" },
  { valor: "DAMAGE", etiqueta: "Daño" },
  { valor: "THEFT", etiqueta: "Robo" },
  { valor: "OTHER", etiqueta: "Otro" },
];

export default function PendingApprovalPage() {
  const [pendientes, setPendientes] = useState<Pendiente[]>([]);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Factura que se está aprobando, y los datos del formulario.
  const [aprobando, setAprobando] = useState<number | null>(null);
  const [motivo, setMotivo] = useState("SHORTAGE");
  const [nota, setNota] = useState("");
  const [enviando, setEnviando] = useState(false);
  const [aviso, setAviso] = useState<string | null>(null);

  const cargar = useCallback(async () => {
    setCargando(true);
    setError(null);
    try {
      const respuesta = await get<{ items: Pendiente[] }>("/invoices/pending-approval");
      setPendientes(respuesta.items ?? []);
    } catch (e) {
      setError(e instanceof Error ? e.message : "No se pudieron cargar los pendientes.");
    } finally {
      setCargando(false);
    }
  }, []);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  /**
   * Aprueba TODAS las líneas en discrepancia de una factura.
   *
   * El motivo es obligatorio y **no se envía con un valor por defecto silencioso**:
   * el formulario se abre antes de aprobar. Dentro de un año, un motivo que nadie
   * eligió no explica la diferencia que se aceptó.
   */
  async function aprobar(invoiceId: number) {
    setEnviando(true);
    setAviso(null);
    try {
      await post(`/invoices/${invoiceId}/approve`, {
        adjustmentReason: motivo,
        note: nota.trim() || null,
      });
      setAprobando(null);
      setNota("");
      setMotivo("SHORTAGE");
      await cargar();
    } catch (e) {
      // El mensaje del backend es el que sirve: nombra las líneas que contó el
      // propio aprobador para que sepa qué reasignar. Un mensaje genérico aquí
      // perdería esa información.
      setAviso(e instanceof Error ? e.message : "No se pudo aprobar la factura.");
    } finally {
      setEnviando(false);
    }
  }

  if (cargando) {
    return <p style={{ padding: 24 }}>Cargando pendientes…</p>;
  }

  if (error) {
    return (
      <div style={{ padding: 24 }}>
        <p role="alert" style={{ color: "#C0392B" }}>
          {error}
        </p>
        <button type="button" onClick={() => void cargar()}>
          Reintentar
        </button>
      </div>
    );
  }

  return (
    <div style={{ padding: 24 }}>
      <h1>Pendientes de aprobación</h1>
      <p style={{ color: "#4A5A72" }}>
        Facturas con diferencias entre lo facturado y lo contado.{" "}
        <strong>Mientras no se aprueben, sus líneas no se pueden ubicar</strong>: si se
        ubicaran, la diferencia desaparecería del inventario sin que nadie la autorice.
      </p>

      {aviso && (
        <p role="alert" style={{ color: "#C0392B", background: "#FDECEA", padding: 12 }}>
          {aviso}
        </p>
      )}

      {pendientes.length === 0 ? (
        <p style={{ color: "#2E7D5B", background: "#E8F5EE", padding: 16 }}>
          No hay discrepancias pendientes. Todo lo recibido está aprobado o cuadrado.
        </p>
      ) : (
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ background: "#E4EBF5", color: "#16315F", textAlign: "left" }}>
              <th style={{ padding: 10 }}>Factura</th>
              <th style={{ padding: 10 }}>Fecha</th>
              <th style={{ padding: 10 }}>Líneas</th>
              <th style={{ padding: 10 }}>Diferencia total</th>
              <th style={{ padding: 10 }} />
            </tr>
          </thead>
          <tbody>
            {pendientes.map((p) => (
              <tr key={p.invoiceId} style={{ borderBottom: "1px solid #E4EBF5" }}>
                <td style={{ padding: 10, fontWeight: 600 }}>{p.invoiceNumber}</td>
                <td style={{ padding: 10 }}>{p.receiptDate}</td>
                <td style={{ padding: 10 }}>{p.pendingLines}</td>
                <td style={{ padding: 10, color: "#C0392B", fontWeight: 600 }}>
                  {p.totalDifference}
                </td>
                <td style={{ padding: 10, textAlign: "right" }}>
                  {aprobando === p.invoiceId ? (
                    <div style={{ display: "grid", gap: 8, justifyItems: "end" }}>
                      <select
                        aria-label="Motivo del ajuste"
                        value={motivo}
                        onChange={(e) => setMotivo(e.target.value)}
                        style={{ padding: 6 }}
                      >
                        {MOTIVOS.map((m) => (
                          <option key={m.valor} value={m.valor}>
                            {m.etiqueta}
                          </option>
                        ))}
                      </select>

                      <textarea
                        aria-label="Nota"
                        placeholder="Explicación (obligatoria si el motivo es Robo u Otro)"
                        value={nota}
                        onChange={(e) => setNota(e.target.value)}
                        rows={2}
                        style={{ padding: 6, width: 260 }}
                      />

                      <div style={{ display: "flex", gap: 8 }}>
                        <button
                          type="button"
                          disabled={enviando}
                          onClick={() => void aprobar(p.invoiceId)}
                          style={{ background: "#16315F", color: "#fff", padding: "6px 14px" }}
                        >
                          {enviando ? "Aprobando…" : "Confirmar aprobación"}
                        </button>
                        <button type="button" onClick={() => setAprobando(null)}>
                          Cancelar
                        </button>
                      </div>
                    </div>
                  ) : (
                    <button
                      type="button"
                      onClick={() => setAprobando(p.invoiceId)}
                      style={{ background: "#F28C28", color: "#fff", padding: "6px 14px" }}
                    >
                      Aprobar
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
