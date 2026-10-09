import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ApiError } from "@shared/api";
import { usePermissionScope } from "@shared/auth";
import type { OrderDetail, OrderLine } from "@shared/api";
import { PageShell } from "@shared/ui/page-shell";
import { PermissionScopeTag } from "@shared/ui/permission-scope-tag";
import {
  confirmOrder,
  exportOrder,
  fetchOrder,
  packageOrderLine,
  registerOrderShortage,
} from "@entities/fulfillment";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * DETALLE DE ORDEN — empaque, faltante, confirmación y exportación (§6)
 *
 * ── LA REGLA QUE ESTA PANTALLA EXISTE PARA HACER CUMPLIR ───────────────────
 *
 * > `suppliedQuantity` es SIEMPRE **lo ya descontado del inventario**. Empacar
 * > descuenta lo empacado; confirmar descuenta **lo que FALTA**.
 *
 * **La interfaz no calcula ninguna de las dos cifras: las muestra.** El pendiente
 * se pide al servidor como la RESTA de lo que él mismo devuelve (`quantity` y
 * `suppliedQuantity`), y ese pendiente es lo que se envía al empacar. Una pantalla
 * que llevara su propia cuenta podría desincronizarse con el inventario real y
 * pedir «empacar 10» sobre una línea que ya tiene 7 descontadas.
 *
 * Y por eso mismo **no se puede confirmar sin ver las líneas**: confirmar descuenta
 * `quantity - suppliedQuantity` de cada una y dispara la exportación al ERP. Es un
 * acto con consecuencia sobre el inventario y sobre un sistema externo.
 *
 * ── LA SECUENCIA DE UN FALTANTE, QUE ES DE TRES PASOS ─────────────────────
 *
 * Cuando no hay existencias, el empacado devuelve `422 INSUFFICIENT_STOCK` con
 * **cuánto hay disponible**. La pantalla no se limita a mostrar el error: dice el
 * número exacto, porque «no hay bastante» sin la cifra obliga al operador a
 * contar el estante a mano. Con el número, puede empacar lo que hay y registrar el
 * faltante, que **es un ajuste con origen `FULFILLMENT_SHORTAGE`**, no un flujo
 * aparte.
 * ═══════════════════════════════════════════════════════════════════════════
 */

const ESTADO_LINEA: Record<string, string> = {
  PENDING: "Pendiente",
  ASSIGNED: "Asignada",
  SUPPLIED: "Surtida",
  VERIFIED: "Verificada",
  CANCELLED: "Cancelada",
};

/**
 * El pendiente de una línea: `pedido − descontado`.
 *
 * **Se calcula sobre las dos cifras que devuelve el SERVIDOR**, y con tres
 * decimales porque las cantidades vienen como cadena decimal (§1.7). Comparar
 * cadenas daría `"10.000" < "7.000"` mal ordenado; convertirlas a `Number` pierde
 * los milésimos, y aquí un milésimo es una unidad contable.
 *
 * Se trabaja en milésimas enteras —que es como el dominio guarda las cantidades—
 * y solo se formatea al final.
 */
function pendienteDe(linea: OrderLine): number {
  const milesimas = (valor: string): number => {
    const n = Number(valor);
    return Number.isFinite(n) ? Math.round(n * 1000) : 0;
  };
  return Math.max(0, milesimas(linea.quantity) - milesimas(linea.suppliedQuantity));
}

/** Milésimas → la cadena decimal de 3 decimales que espera la API. */
function formatear(milesimas: number): string {
  return (milesimas / 1000).toFixed(3);
}

/** Cadena decimal → milésimas enteras. */
function aMilesimas(valor: string): number {
  const n = Number(valor.replace(",", "."));
  return Number.isFinite(n) ? Math.round(n * 1000) : 0;
}

export default function OrderDetailPage() {
  const { id } = useParams<{ id: string }>();
  const orderId = Number(id);
  const scope = usePermissionScope("order.package");

  const [orden, setOrden] = useState<OrderDetail | null>(null);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const [sinPermiso, setSinPermiso] = useState(false);
  const [ocupado, setOcupado] = useState<number | "confirmar" | "exportar" | null>(null);
  // Lo que el operador escribe para empacar, por línea.
  const [aEmpacar, setAEmpacar] = useState<Record<number, string>>({});

  const cargar = useCallback(async () => {
    if (!Number.isFinite(orderId)) {
      setError("La dirección no lleva un identificador de orden válido.");
      setCargando(false);
      return;
    }
    setCargando(true);
    setError(null);
    setSinPermiso(false);
    try {
      setOrden(await fetchOrder(orderId));
    } catch (e) {
      setOrden(null);
      setSinPermiso(e instanceof ApiError && e.status === 403);
      setError(e instanceof Error ? e.message : "No se pudo cargar la orden.");
    } finally {
      setCargando(false);
    }
  }, [orderId]);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  /** Empaqueta lo escrito para una línea, validando contra el PENDIENTE. */
  const empacar = useCallback(
    async (linea: OrderLine) => {
      const escrito = aEmpacar[linea.id] ?? "";
      const milesimas = aMilesimas(escrito);
      const pendiente = pendienteDe(linea);

      if (milesimas <= 0) {
        setError("Escribe cuánto se empaca. Tiene que ser mayor que cero.");
        return;
      }
      // **Se valida contra el PENDIENTE antes de llamar.** El servidor también lo
      // valida y su rechazo es la autoridad, pero comprobarlo aquí convierte un
      // viaje de ida y vuelta con `422` en un mensaje inmediato que dice cuánto
      // queda — y evita que un doble clic mande dos veces la misma cantidad.
      if (milesimas > pendiente) {
        setError(
          `No se puede empacar ${formatear(milesimas)}: a esta línea le quedan ` +
            `${formatear(pendiente)} por empacar.`,
        );
        return;
      }

      setOcupado(linea.id);
      setError(null);
      setAviso(null);
      try {
        setOrden(
          await packageOrderLine(orderId, linea.id, {
            quantity: formatear(milesimas),
            locationId: linea.locationId,
          }),
        );
        setAEmpacar((previo) => ({ ...previo, [linea.id]: "" }));
        setAviso(
          `Empacadas ${formatear(milesimas)} de ${linea.productName}. El inventario ya lo ` +
            `refleja y el kardex tiene su salida FULFILLMENT_OUT.`,
        );
      } catch (e) {
        // ── El caso que el contrato marca, y se trata aparte ────────────────
        //
        // `422 INSUFFICIENT_STOCK` trae `details.available`. **Se dice el número.**
        // «No hay bastante» sin la cifra obliga a contar el estante a mano, que es
        // justo el trabajo que el sistema tiene que ahorrar.
        if (e instanceof ApiError && e.code === "INSUFFICIENT_STOCK") {
          const disponible = String(e.details?.available ?? "—");
          setError(
            `No hay existencias suficientes en esa ubicación. Disponible: ${disponible}. ` +
              `Puedes empacar esa cantidad y registrar el faltante.`,
          );
        } else {
          setError(e instanceof Error ? e.message : "No se pudo empacar la línea.");
        }
        // Se recarga: si el fallo fue por un cambio hecho desde otro sitio, el
        // estado en pantalla ya no es el del servidor.
        await cargar();
      } finally {
        setOcupado(null);
      }
    },
    [aEmpacar, orderId, cargar],
  );

  /** El faltante: delega en la regla única de ajuste, no en un flujo nuevo. */
  const faltante = useCallback(
    async (linea: OrderLine) => {
      const pendiente = pendienteDe(linea);
      if (pendiente <= 0) {
        setError("Esta línea no tiene nada pendiente: no hay faltante que registrar.");
        return;
      }

      setOcupado(linea.id);
      setError(null);
      setAviso(null);
      try {
        setOrden(
          await registerOrderShortage(orderId, {
            lineId: linea.id,
            quantity: formatear(pendiente),
            reason: "SHORTAGE",
            note: "Faltante registrado durante el surtido",
          }),
        );
        setAviso(
          `Faltante de ${formatear(pendiente)} registrado como ajuste con origen ` +
            `FULFILLMENT_SHORTAGE. No es un flujo aparte: es la misma regla de ajuste ` +
            `que usa el inventario.`,
        );
      } catch (e) {
        setError(e instanceof Error ? e.message : "No se pudo registrar el faltante.");
        await cargar();
      } finally {
        setOcupado(null);
      }
    },
    [orderId, cargar],
  );

  const confirmar = useCallback(async () => {
    setOcupado("confirmar");
    setError(null);
    setAviso(null);
    try {
      setOrden(await confirmOrder(orderId));
      setAviso(
        "Orden confirmada. Se descontó LO QUE FALTABA de cada línea —no la cantidad " +
          "entera, porque lo empacado ya se había descontado— y se disparó la exportación.",
      );
    } catch (e) {
      // `409 ORDER_ALREADY_CONFIRMED` es un conflicto, no un fallo del usuario: ya
      // estaba. Recargar es lo correcto, porque el descuento ya ocurrió una vez y
      // repetirlo bajaría el inventario el doble.
      if (e instanceof ApiError && e.code === "ORDER_ALREADY_CONFIRMED") {
        setError("Esta orden ya estaba confirmada. No se ha descontado nada de nuevo.");
      } else {
        setError(e instanceof Error ? e.message : "No se pudo confirmar la orden.");
      }
      await cargar();
    } finally {
      setOcupado(null);
    }
  }, [orderId, cargar]);

  const exportar = useCallback(async () => {
    setOcupado("exportar");
    setError(null);
    setAviso(null);
    try {
      const resultado = await exportOrder(orderId);
      setAviso(
        `Intento ${resultado.attempts} de exportación: ${resultado.status}.` +
          (resultado.lastError ? ` El ERP respondió: ${resultado.lastError}` : ""),
      );
    } catch (e) {
      if (e instanceof ApiError && e.code === "ORDER_NOT_CONVERTED") {
        setError("La orden todavía no está confirmada: no hay nada que exportar todavía.");
      } else {
        setError(e instanceof Error ? e.message : "No se pudo exportar la orden.");
      }
    } finally {
      await cargar();
      setOcupado(null);
    }
  }, [orderId, cargar]);

  const lineasPendientes = orden?.lines.filter((l) => pendienteDe(l) > 0).length ?? 0;

  return (
    <PageShell
      title={orden ? `Orden ${orden.orderNumber}` : "Detalle de orden"}
      description="Empaque por línea, faltantes, confirmación y reintento de exportación."
      breadcrumbLabel="Surtido"
    >
      <p style={{ display: "flex", alignItems: "center", gap: 8, color: "#4A5A72" }}>
        Permiso de la pantalla:
        <PermissionScopeTag permission="order.package" scope={scope} />
        <Link to="/orders">← Volver a órdenes</Link>
      </p>

      {cargando && <p style={{ color: "#4A5A72" }}>Cargando la orden…</p>}

      {!cargando && error && (
        <div
          role="alert"
          style={{
            background: "#FDECEA",
            borderLeft: "6px solid #C0392B",
            padding: 16,
            margin: "16px 0",
          }}
        >
          <div style={{ marginBottom: 8 }}>{error}</div>
          {sinPermiso && (
            <div style={{ marginBottom: 8 }}>
              Esta pantalla exige el permiso <code>order.package</code>. Pídeselo a un
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
          style={{
            background: "#E8F5EE",
            borderLeft: "6px solid #2E7D5B",
            padding: 12,
            margin: "16px 0",
          }}
        >
          {aviso}
        </p>
      )}

      {!cargando && orden && (
        <>
          <section style={{ display: "flex", gap: 24, flexWrap: "wrap", margin: "16px 0" }}>
            <div>
              <strong>Estado:</strong> {orden.status}
            </div>
            <div>
              <strong>Fecha de empaque:</strong> {orden.packagingDate ?? "sin fecha"}
            </div>
            <div>
              <strong>Cliente:</strong> {orden.customerCode ?? "—"}
            </div>
            <div>
              <strong>Líneas completas:</strong> {orden.packagedLines} / {orden.totalLines}
            </div>
          </section>

          {/*
            La explicación de la regla, EN PANTALLA y antes de los botones.
            Confirmar descuenta stock: quien lo pulse tiene que saber exactamente
            qué se va a descontar, y no es la cantidad pedida.
          */}
          <section
            style={{
              background: "#F2F6FC",
              borderLeft: "6px solid #16315F",
              padding: 20,
              margin: "16px 0",
            }}
          >
            <h2 style={{ margin: 0, fontSize: 18, color: "#16315F" }}>
              Pendiente total: {formatear(orden.lines.reduce((suma, l) => suma + pendienteDe(l), 0))}
            </h2>
            <p style={{ margin: "8px 0 0", color: "#4A5A72" }}>
              Cada línea muestra <strong>lo pedido</strong> y{" "}
              <strong>lo ya descontado del inventario</strong>. La diferencia es el
              pendiente. Confirmar descuenta <strong>esa diferencia</strong>: lo empacado ya se
              descontó al empacar, y volver a descontar la cantidad entera dejaría el inventario
              corto — y el descuadre se confundiría con una merma.
            </p>
          </section>

          <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead>
              <tr style={{ background: "#E4EBF5", color: "#16315F", textAlign: "left" }}>
                <th style={{ padding: 10 }}>Producto</th>
                <th style={{ padding: 10, textAlign: "right" }}>Pedido</th>
                <th style={{ padding: 10, textAlign: "right" }}>Descontado</th>
                <th style={{ padding: 10, textAlign: "right" }}>Pendiente</th>
                <th style={{ padding: 10 }}>Estado</th>
                <th style={{ padding: 10 }}>Empacar</th>
              </tr>
            </thead>
            <tbody>
              {orden.lines.map((linea) => {
                const pendiente = pendienteDe(linea);
                const completo = pendiente === 0;
                return (
                  <tr key={linea.id} style={{ borderBottom: "1px solid #E4EBF5" }}>
                    <td style={{ padding: 10 }}>
                      <div>{linea.productName}</div>
                      <div style={{ color: "#8A94A6", fontSize: 13 }}>
                        {linea.productCode}
                        {linea.locationDisplayName ? ` · ${linea.locationDisplayName}` : ""}
                      </div>
                    </td>
                    <td style={{ padding: 10, textAlign: "right" }}>{linea.quantity}</td>
                    <td style={{ padding: 10, textAlign: "right" }}>{linea.suppliedQuantity}</td>
                    <td
                      style={{
                        padding: 10,
                        textAlign: "right",
                        fontWeight: 600,
                        color: completo ? "#2E7D5B" : "#F28C28",
                      }}
                    >
                      {formatear(pendiente)}
                    </td>
                    <td style={{ padding: 10 }}>
                      {ESTADO_LINEA[linea.status] ?? linea.status}
                      {linea.assignedUserName ? ` · ${linea.assignedUserName}` : ""}
                    </td>
                    <td style={{ padding: 10 }}>
                      {completo ? (
                        <span style={{ color: "#2E7D5B" }}>Completa</span>
                      ) : (
                        <span style={{ display: "flex", gap: 6, alignItems: "center" }}>
                          <input
                            type="number"
                            // El máximo se pone al PENDIENTE: el campo no deja
                            // pedir de más, y el mensaje de error explica por qué.
                            max={pendiente / 1000}
                            min={0}
                            step="0.001"
                            aria-label={`Cantidad a empacar de ${linea.productName}`}
                            placeholder={formatear(pendiente)}
                            value={aEmpacar[linea.id] ?? ""}
                            onChange={(e) =>
                              setAEmpacar((previo) => ({ ...previo, [linea.id]: e.target.value }))
                            }
                            disabled={ocupado === linea.id}
                            style={{ width: 100 }}
                          />
                          <button
                            type="button"
                            disabled={ocupado === linea.id}
                            onClick={() => void empacar(linea)}
                          >
                            {ocupado === linea.id ? "Empacando…" : "Empacar"}
                          </button>
                          {/*
                            El faltante solo se ofrece cuando NO hay nada
                            descontado todavía... no: se ofrece siempre que quede
                            pendiente, porque un faltante parcial es lo normal.
                          */}
                          <button
                            type="button"
                            disabled={ocupado === linea.id}
                            onClick={() => void faltante(linea)}
                          >
                            Faltante
                          </button>
                        </span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>

          <section style={{ margin: "24px 0", display: "flex", gap: 16, alignItems: "center" }}>
            <button
              type="button"
              disabled={ocupado !== null || orden.converted}
              onClick={() => void confirmar()}
            >
              {ocupado === "confirmar" ? "Confirmando…" : "Confirmar y exportar"}
            </button>

            {/*
              Confirmar con líneas pendientes NO se bloquea: el contrato permite
              confirmar descuenta lo que falte —incluso todo, si no se empacó nada—.
              Pero se AVISA de cuántas quedan, porque un clic aquí descuenta stock
              de todas ellas y dispara la exportación.
            */}
            {lineasPendientes > 0 && (
              <span style={{ color: "#F28C28" }}>
                {lineasPendientes} línea(s) con pendiente. Al confirmar se descontará lo que
                falta de cada una y se exportará la orden.
              </span>
            )}

            {orden.converted && (
              <span style={{ color: "#2E7D5B" }}>Ya confirmada y recibida por el ERP.</span>
            )}
          </section>

          {/*
            El reintento de exportación aparece SOLO cuando ya se confirmó y el ERP
            no la tiene. Ofrecerlo antes daría `409 ORDER_NOT_CONVERTED`, que es un
            error que el usuario no puede evitar.
          */}
          {orden.converted && (
            <section
              style={{
                background: "#F2F6FC",
                borderLeft: "6px solid #16315F",
                padding: 20,
                margin: "16px 0",
              }}
            >
              <h2 style={{ margin: 0, fontSize: 17, color: "#16315F" }}>Exportación al ERP</h2>
              <p style={{ margin: "8px 0", color: "#4A5A72" }}>
                Intentos: {orden.exportAttempts}
                {orden.exportLastError ? ` · último error: ${orden.exportLastError}` : ""}
              </p>
              <p style={{ margin: "8px 0", color: "#4A5A72" }}>
                Reintentar es <strong>idempotente</strong>: la clave es el número de orden ya
                convertido, así que no puede crear una segunda salida.
              </p>
              <button type="button" disabled={ocupado !== null} onClick={() => void exportar()}>
                {ocupado === "exportar" ? "Exportando…" : "Reintentar exportación"}
              </button>
            </section>
          )}
        </>
      )}
    </PageShell>
  );
}
