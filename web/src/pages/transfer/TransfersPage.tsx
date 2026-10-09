import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@shared/api";
import { usePermissionScope } from "@shared/auth";
import type { InTransitTransfer, Transfer } from "@shared/api";
import { PageShell } from "@shared/ui/page-shell";
import { PermissionScopeTag } from "@shared/ui/permission-scope-tag";
import {
  cancelTransfer,
  dispatchTransfer,
  fetchInTransit,
  fetchTransfers,
  receiveTransfer,
} from "@entities/transfer";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TRASLADOS — pantalla REAL contra la API (§7)
 *
 * **Esta pantalla era una maqueta honesta y ya no lo es.** Decía, con razón en su
 * momento: «El endpoint no existe. `GET /v1/transfers` responde 404, comprobado con
 * `curl`». Los endpoints se implementaron después y **la maqueta se quedó
 * desactualizada**: seguía afirmando que no había rutas cuando ya las había. Es la
 * clase de mentira que el proyecto persigue —una que era verdad y dejó de serlo— y
 * solo se corrige volviendo a comprobar.
 *
 * ── LA INVARIANTE, QUE ES LO QUE LA PANTALLA TIENE QUE HACER VISIBLE ───────
 *
 * > `Σ stock_by_location + Σ stock_in_transit` **se conserva en cada transición**.
 *
 * La mercancía que va en el camión **sigue contando** en el inventario. Por eso
 * despachar no hace desaparecer nada de la pantalla de existencias y recibir no la
 * hace crecer: el traslado se mueve **entre las dos secciones de esta pantalla**.
 *
 * ── LA SECCIÓN DE TRÁNSITO ES «DEUDA VISIBLE», NO UNA TABLA MÁS ────────────
 *
 * Va arriba y con el aviso en ámbar a propósito: **un traslado nunca recibido es
 * mercancía que falta y nadie está persiguiendo**. Es lo único de esta pantalla
 * que exige una acción, así que ocupa el sitio principal. Ponerlo abajo, después
 * del listado general, lo convertiría en un dato que se lee de pasada.
 *
 * `daysInTransit` lo calcula el SERVIDOR: con el reloj del equipo, dos personas
 * verían días distintos para el mismo traslado, y un dato de gestión que cambia
 * según quién mire no sirve para decidir a quién reclamar.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Estados del traslado con su texto para mostrar. */
const ESTADO: Record<string, string> = {
  DRAFT: "Borrador",
  DISPATCHED: "Despachado",
  IN_TRANSIT: "En tránsito",
  RECEIVED: "Recibido",
  CANCELLED: "Cancelado",
};

/** El color del estado: lo que exige acción va en ámbar. */
function colorEstado(status: string): string {
  switch (status) {
    case "RECEIVED":
      return "#2E7D5B";
    case "CANCELLED":
      return "#8A94A6";
    case "DISPATCHED":
    case "IN_TRANSIT":
      return "#F28C28";
    default:
      return "#16315F";
  }
}

export default function TransfersPage() {
  const scope = usePermissionScope("transfer.view");
  const [enTransito, setEnTransito] = useState<InTransitTransfer[] | null>(null);
  const [traslados, setTraslados] = useState<Transfer[] | null>(null);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  // Un 403 no es un fallo de red: es un permiso que falta. Se distingue para
  // decir cuál, porque el usuario no puede adivinarlo.
  const [sinPermiso, setSinPermiso] = useState(false);
  const [accionEnCurso, setAccionEnCurso] = useState<number | null>(null);

  const cargar = useCallback(async () => {
    setCargando(true);
    setError(null);
    setSinPermiso(false);
    try {
      // Las dos secciones a la vez: el tránsito es lo que exige acción y el
      // listado es el contexto. Pedirlas en serie dejaría la pantalla a medias.
      const [transito, lista] = await Promise.all([fetchInTransit(), fetchTransfers()]);
      setEnTransito(transito);
      setTraslados(lista.items);
    } catch (e) {
      // Se descartan los datos anteriores: dejarlos en pantalla después de un
      // fallo haría creer que el inventario se comprobó y está bien, que es el
      // mismo criterio que el informe de reconciliación.
      setEnTransito(null);
      setTraslados(null);
      setSinPermiso(e instanceof ApiError && e.status === 403);
      setError(e instanceof Error ? e.message : "No se pudieron cargar los traslados.");
    } finally {
      setCargando(false);
    }
  }, []);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  /**
   * Ejecuta una acción de transición y RECARGA.
   *
   * Se recarga en lugar de actualizar el estado local: el servidor es quien decide
   * si la transición es legal y quién queda como responsable. Actualizar en local
   * mostraría un traslado despachado que el servidor pudo rechazar.
   */
  const accion = useCallback(
    async (id: number, cual: "dispatch" | "receive" | "cancel") => {
      setAccionEnCurso(id);
      setError(null);
      setAviso(null);
      try {
        if (cual === "dispatch") await dispatchTransfer(id);
        else if (cual === "receive") await receiveTransfer(id);
        else await cancelTransfer(id);

        setAviso(
          cual === "dispatch"
            ? "Traslado despachado. La mercancía sigue contando: ahora está en tránsito."
            : cual === "receive"
              ? "Traslado recibido. La mercancía llegó a la bodega de destino."
              : "Traslado cancelado. La mercancía volvió a su ubicación de origen.",
        );
        await cargar();
      } catch (e) {
        // El `code` del contrato es lo que decide: `INVALID_TRANSITION` significa
        // que alguien movió el traslado mientras esta pantalla estaba abierta, y
        // hay que recargar para ver el estado real.
        const codigo = e instanceof ApiError ? e.code : null;
        setError(
          codigo === "INVALID_TRANSITION"
            ? "El traslado cambió de estado mientras mirabas la pantalla. Se recarga para " +
                "que veas el estado real."
            : e instanceof Error
              ? e.message
              : "No se pudo completar la acción.",
        );
        await cargar();
      } finally {
        setAccionEnCurso(null);
      }
    },
    [cargar],
  );

  return (
    <PageShell
      title="Traslados"
      description="Despacho, recepción y mercancía en tránsito entre bodegas."
      breadcrumbLabel="Surtido"
    >
      <p style={{ display: "flex", alignItems: "center", gap: 8, color: "#4A5A72" }}>
        Permiso de la pantalla:
        <PermissionScopeTag permission="transfer.view" scope={scope} />
      </p>

      {cargando && <p style={{ color: "#4A5A72" }}>Cargando traslados…</p>}

      {!cargando && error && (
        <div
          role="alert"
          style={{ background: "#FDECEA", borderLeft: "6px solid #C0392B", padding: 16, margin: "16px 0" }}
        >
          <div style={{ marginBottom: 8 }}>{error}</div>
          {sinPermiso && (
            <div style={{ marginBottom: 8 }}>
              Esta pantalla exige el permiso <code>transfer.view</code>. Pídeselo a un
              administrador.
            </div>
          )}
          <button type="button" onClick={() => void cargar()}>
            Reintentar
          </button>
        </div>
      )}

      {!cargando && aviso && (
        <p
          role="status"
          style={{ background: "#E8F5EE", borderLeft: "6px solid #2E7D5B", padding: 12, margin: "16px 0" }}
        >
          {aviso}
        </p>
      )}

      {!cargando && !error && enTransito && traslados && (
        <>
          {/*
            ── LA DEUDA VISIBLE, ARRIBA ────────────────────────────────────
            Un traslado que nunca se recibió es mercancía que falta y nadie está
            persiguiendo. Es lo único de esta pantalla que exige una acción, así
            que ocupa el sitio principal.
          */}
          <section
            role={enTransito.length > 0 ? "alert" : "status"}
            style={{
              background: enTransito.length > 0 ? "#FFF6E5" : "#E8F5EE",
              borderLeft: `6px solid ${enTransito.length > 0 ? "#F28C28" : "#2E7D5B"}`,
              padding: 20,
              margin: "16px 0",
            }}
          >
            <h2
              style={{
                margin: 0,
                fontSize: 20,
                color: enTransito.length > 0 ? "#8A5A00" : "#2E7D5B",
              }}
            >
              {enTransito.length > 0
                ? `${enTransito.length} traslado(s) en camino`
                : "No hay mercancía en tránsito"}
            </h2>
            <p style={{ margin: "8px 0 0", color: "#4A5A72" }}>
              {enTransito.length > 0
                ? "Esta mercancía salió de su bodega y todavía no llegó a la de destino. " +
                  "Sigue contando en el inventario: si se modelara como dos movimientos " +
                  "instantáneos, el total bajaría durante el viaje y los reportes mentirían."
                : "Todo lo despachado se ha recibido. No hay ninguna deuda pendiente de perseguir."}
            </p>
          </section>

          {enTransito.length > 0 && (
            <table style={{ width: "100%", borderCollapse: "collapse", marginBottom: 24 }}>
              <thead>
                <tr style={{ background: "#E4EBF5", color: "#16315F", textAlign: "left" }}>
                  <th style={{ padding: 10 }}>Código</th>
                  <th style={{ padding: 10 }}>Origen</th>
                  <th style={{ padding: 10 }}>Destino</th>
                  <th style={{ padding: 10, textAlign: "right" }}>Unidades</th>
                  <th style={{ padding: 10, textAlign: "right" }}>Días</th>
                  <th style={{ padding: 10 }}>Responsable</th>
                  <th style={{ padding: 10 }}>Acciones</th>
                </tr>
              </thead>
              <tbody>
                {enTransito.map((t) => (
                  <tr key={t.id} style={{ borderBottom: "1px solid #E4EBF5" }}>
                    <td style={{ padding: 10 }}>{t.code}</td>
                    <td style={{ padding: 10 }}>{t.fromWarehouseCode}</td>
                    <td style={{ padding: 10 }}>{t.toWarehouseCode}</td>
                    <td style={{ padding: 10, textAlign: "right" }}>{t.totalUnits}</td>
                    <td
                      style={{
                        padding: 10,
                        textAlign: "right",
                        // Un traslado de muchos días es el que hay que ir a buscar.
                        color: t.daysInTransit > 3 ? "#C0392B" : "#4A5A72",
                        fontWeight: t.daysInTransit > 3 ? 700 : 400,
                      }}
                    >
                      {t.daysInTransit}
                    </td>
                    <td style={{ padding: 10 }}>{t.dispatchedByName ?? "—"}</td>
                    <td style={{ padding: 10 }}>
                      <button
                        type="button"
                        disabled={accionEnCurso === t.id}
                        onClick={() => void accion(t.id, "receive")}
                      >
                        {accionEnCurso === t.id ? "Recibiendo…" : "Recibir"}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <h2 style={{ fontSize: 17, color: "#16315F" }}>Todos los traslados</h2>

          {traslados.length === 0 ? (
            <p style={{ color: "#4A5A72" }}>
              Todavía no hay ningún traslado. Un traslado se crea desde la API con su bodega
              de origen, la de destino y sus líneas; esta pantalla los consulta, los despacha
              y los recibe.
            </p>
          ) : (
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
              <thead>
                <tr style={{ background: "#E4EBF5", color: "#16315F", textAlign: "left" }}>
                  <th style={{ padding: 10 }}>Código</th>
                  <th style={{ padding: 10 }}>Ruta</th>
                  <th style={{ padding: 10 }}>Estado</th>
                  <th style={{ padding: 10, textAlign: "right" }}>Líneas</th>
                  <th style={{ padding: 10, textAlign: "right" }}>Unidades</th>
                  <th style={{ padding: 10 }}>Acciones</th>
                </tr>
              </thead>
              <tbody>
                {traslados.map((t) => (
                  <tr key={t.id} style={{ borderBottom: "1px solid #E4EBF5" }}>
                    <td style={{ padding: 10 }}>{t.code}</td>
                    <td style={{ padding: 10 }}>
                      {t.fromWarehouseCode} → {t.toWarehouseCode}
                    </td>
                    <td style={{ padding: 10, color: colorEstado(t.status), fontWeight: 600 }}>
                      {ESTADO[t.status] ?? t.status}
                    </td>
                    <td style={{ padding: 10, textAlign: "right" }}>{t.lineCount}</td>
                    <td style={{ padding: 10, textAlign: "right" }}>{t.totalUnits}</td>
                    <td style={{ padding: 10, display: "flex", gap: 8 }}>
                      {/* Cada botón solo aparece si la transición es LEGAL desde
                          ese estado. Ofrecer «Recibir» en un borrador daría un 422
                          que el usuario no puede evitar. La regla vive en el
                          dominio del servidor; aquí solo se refleja. */}
                      {t.status === "DRAFT" && (
                        <>
                          <button
                            type="button"
                            disabled={accionEnCurso === t.id}
                            onClick={() => void accion(t.id, "dispatch")}
                          >
                            Despachar
                          </button>
                          <button
                            type="button"
                            disabled={accionEnCurso === t.id}
                            onClick={() => void accion(t.id, "cancel")}
                          >
                            Cancelar
                          </button>
                        </>
                      )}
                      {t.status === "DISPATCHED" && (
                        <>
                          <button
                            type="button"
                            disabled={accionEnCurso === t.id}
                            onClick={() => void accion(t.id, "receive")}
                          >
                            Recibir
                          </button>
                          <button
                            type="button"
                            disabled={accionEnCurso === t.id}
                            onClick={() => void accion(t.id, "cancel")}
                          >
                            Cancelar
                          </button>
                        </>
                      )}
                      {/* `RECEIVED` y `CANCELLED` no ofrecen nada: lo recibido se
                          corrige con un traslado de vuelta, que deja rastro, y un
                          cancelado no se cancela dos veces. */}
                      {(t.status === "RECEIVED" || t.status === "CANCELLED") && (
                        <span style={{ color: "#8A94A6" }}>—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </PageShell>
  );
}
