import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ApiError } from "@shared/api";
import { usePermissionScope } from "@shared/auth";
import { useCan } from "@entities/access";
import type { OrderSummary } from "@shared/api";
import { PageShell } from "@shared/ui/page-shell";
import { PermissionScopeTag } from "@shared/ui/permission-scope-tag";
import { createOrder, fetchOrdersByStatus } from "@entities/fulfillment";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ÓRDENES DE SURTIDO — pantalla REAL contra la API (§6)
 *
 * **Esta pantalla era una maqueta honesta y ya no lo es.** Decía «El endpoint no
 * existe. `GET /v1/orders` responde 404, comprobado con `curl`». Era verdad, y
 * dejó de serlo cuando se implementó el módulo: la maqueta siguió afirmándolo. Es
 * la misma deuda que tenía Traslados.
 *
 * ── LO QUE ESTA PANTALLA TIENE QUE HACER VISIBLE ───────────────────────────
 *
 * El contrato marca el surtido con una regla que **no se ve en los números hasta
 * que el almacén descuadra**:
 *
 * > `suppliedQuantity` es SIEMPRE lo ya descontado del inventario. Empacar
 * > descuenta lo empacado; confirmar descuenta **lo que FALTA**.
 *
 * Por eso el detalle muestra las DOS cifras de cada línea —lo pedido y lo ya
 * descontado— y no una barra de progreso: **la resta es la que decide**. Una línea
 * de 10 con 7 empacadas tiene pendiente 3, y quien vaya a confirmar tiene que ver
 * ese 3 antes de pulsar, no descubrirlo cuadrando el almacén.
 *
 * ── LO QUE NO SE MUESTRA, Y POR QUÉ ───────────────────────────────────────
 *
 * No hay botón de «confirmar» en el LISTADO. Confirmar descuenta stock de todas
 * las líneas pendientes y dispara la exportación: es un acto con consecuencia
 * sobre el inventario, y va en el detalle, donde se ven las líneas antes. Un botón
 * en la fila del listado se pulsa sin haber mirado nada.
 *
 * Y aquí solo se dice el estado de la exportación, sin botón de reintentar: eso es
 * un acto deliberado y vive en el detalle.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Los estados de una orden con su texto. */
const ESTADO: Record<string, string> = {
  PENDING: "Pendiente",
  IN_PROGRESS: "En proceso",
  PACKAGED: "Empacada",
  PENDING_TO_OUT: "Por confirmar",
  COMPLETED: "Completada",
  CANCELLED: "Cancelada",
};

function colorEstado(status: string): string {
  switch (status) {
    case "COMPLETED":
      return "#2E7D5B";
    case "CANCELLED":
      return "#8A94A6";
    case "PENDING_TO_OUT":
      return "#F28C28";
    default:
      return "#16315F";
  }
}

/** Los filtros que la API acepta, para no inventar estados que no existen. */
const FILTROS: Array<{ valor: string; texto: string }> = [
  { valor: "", texto: "Todas" },
  { valor: "PENDING", texto: "Pendientes" },
  { valor: "IN_PROGRESS", texto: "En proceso" },
  { valor: "PACKAGED", texto: "Empacadas" },
  { valor: "PENDING_TO_OUT", texto: "Por confirmar" },
  { valor: "COMPLETED", texto: "Completadas" },
];

export default function OrdersPage() {
  const scope = usePermissionScope("order.view");
  const [ordenes, setOrdenes] = useState<OrderSummary[] | null>(null);
  const [total, setTotal] = useState(0);
  const [estado, setEstado] = useState("");
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const [sinPermiso, setSinPermiso] = useState(false);

  // ── El alta de órdenes ─────────────────────────────────────────────────
  //
  // **Existe el permiso `order.create`, así que la pantalla lo respeta:** el botón
  // no se pinta a quien no lo tiene. No es cosmético — el servidor lo rechazaría
  // igual con `403`— pero enseñar un botón que devuelve `403` es hacer perder el
  // tiempo a quien lo pulsa.
  const puedeCrear = useCan("order.create");
  const [altaAbierta, setAltaAbierta] = useState(false);
  const [concepto, setConcepto] = useState("");
  const [cliente, setCliente] = useState("");
  const [lineas, setLineas] = useState<Array<{ productCode: string; quantity: string }>>([
    { productCode: "", quantity: "" },
  ]);
  const [creando, setCreando] = useState(false);

  const cargar = useCallback(async () => {
    setCargando(true);
    setError(null);
    setSinPermiso(false);
    try {
      const pagina = await fetchOrdersByStatus(estado || undefined);
      setOrdenes(pagina.items);
      // El total lo cuenta el SERVIDOR sobre el filtro. Usar `items.length` daría
      // el tamaño de la página y el usuario creería que hay 50 órdenes cuando
      // puede haber 400.
      setTotal(pagina.totalItems);
    } catch (e) {
      // Se descartan las filas anteriores: dejarlas tras un fallo haría creer que
      // la lista está comprobada y al día.
      setOrdenes(null);
      setSinPermiso(e instanceof ApiError && e.status === 403);
      setError(e instanceof Error ? e.message : "No se pudieron cargar las órdenes.");
    } finally {
      setCargando(false);
    }
  }, [estado]);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  const crear = useCallback(async () => {
    // Las líneas vacías se descartan ANTES de enviar: el usuario añade una fila de
    // más y la deja en blanco con frecuencia, y mandarla daría
    // `422 PRODUCT_NOT_FOUND` por un código vacío que nadie escribió.
    const utiles = lineas.filter((l) => l.productCode.trim().length > 0);

    if (utiles.length === 0) {
      setError(
        "Hace falta al menos una línea con su código de producto. Una orden sin líneas " +
          "no se puede empacar ni confirmar.",
      );
      return;
    }
    // **Se valida la cantidad antes de mandarla.** La API la exige positiva y con
    // tres decimales; descubrirlo con un `422` después de rellenar el formulario
    // obliga a repetir el trabajo.
    const sinCantidad = utiles.filter((l) => {
      const n = Number(l.quantity.replace(",", "."));
      return !Number.isFinite(n) || n <= 0;
    });
    if (sinCantidad.length > 0) {
      setError(
        `Estas líneas no llevan una cantidad válida: ${sinCantidad
          .map((l) => l.productCode)
          .join(", ")}. Tiene que ser mayor que cero.`,
      );
      return;
    }

    setCreando(true);
    setError(null);
    setAviso(null);
    try {
      // La cantidad se normaliza a 3 decimales, que es la forma del contrato (§1.7).
      const nueva = await createOrder({
        concept: concepto.trim() || null,
        customerCode: cliente.trim() || null,
        lines: utiles.map((l) => ({
          productCode: l.productCode.trim(),
          quantity: Number(l.quantity.replace(",", ".")).toFixed(3),
        })),
      });

      setAviso(
        `Orden ${nueva.orderNumber} creada con ${nueva.lines.length} línea(s). ` +
          `Nace PENDIENTE y **no toca el inventario**: el stock sale al empacar y al confirmar, ` +
          `que son actos con responsable.`,
      );
      setAltaAbierta(false);
      setConcepto("");
      setCliente("");
      setLineas([{ productCode: "", quantity: "" }]);
      await cargar();
    } catch (e) {
      setError(e instanceof Error ? e.message : "No se pudo crear la orden.");
    } finally {
      setCreando(false);
    }
  }, [concepto, cliente, lineas, cargar]);

  return (
    <PageShell
      title="Órdenes"
      description="Órdenes de surtido: empaque por línea, confirmación y exportación al ERP."
      breadcrumbLabel="Surtido"
    >
      <p style={{ display: "flex", alignItems: "center", gap: 8, color: "#4A5A72" }}>
        Permiso de la pantalla:
        <PermissionScopeTag permission="order.view" scope={scope} />
      </p>

      <div style={{ display: "flex", alignItems: "center", gap: 8, margin: "16px 0" }}>
        <label htmlFor="filtro-estado">Estado:</label>
        <select
          id="filtro-estado"
          value={estado}
          onChange={(e) => setEstado(e.target.value)}
          disabled={cargando}
        >
          {FILTROS.map((f) => (
            <option key={f.valor} value={f.valor}>
              {f.texto}
            </option>
          ))}
        </select>
        {!cargando && !error && <span style={{ color: "#4A5A72" }}>{total} orden(es)</span>}
      </div>

      {aviso && (
        <p
          role="status"
          style={{ background: "#E8F5EE", borderLeft: "6px solid #2E7D5B", padding: 12, margin: "16px 0" }}
        >
          {aviso}
        </p>
      )}

      {puedeCrear && (
        <div style={{ margin: "16px 0" }}>
          <button type="button" onClick={() => setAltaAbierta((v) => !v)}>
            {altaAbierta ? "Cancelar el alta" : "Nueva orden"}
          </button>
        </div>
      )}

      {puedeCrear && altaAbierta && (
        <section
          style={{ background: "#F2F6FC", borderLeft: "6px solid #16315F", padding: 20, margin: "16px 0" }}
        >
          <h2 style={{ margin: 0, fontSize: 17, color: "#16315F" }}>Nueva orden de surtido</h2>
          <p style={{ margin: "8px 0", color: "#4A5A72" }}>
            La orden nace <strong>Pendiente</strong> y <strong>no toca el inventario</strong>. El
            stock sale al empacar y al confirmar. El número de orden lo genera el servidor
            (<code>ORD-AAAA-NNNN</code>): que lo invente un cliente permitiría dos órdenes con el
            mismo número.
          </p>

          <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
            <label style={{ display: "flex", flexDirection: "column" }}>
              <span style={{ fontSize: 13, color: "#4A5A72" }}>Cliente</span>
              <input
                type="text"
                aria-label="Código de cliente"
                value={cliente}
                onChange={(e) => setCliente(e.target.value)}
              />
            </label>
            <label style={{ display: "flex", flexDirection: "column", flexGrow: 1 }}>
              <span style={{ fontSize: 13, color: "#4A5A72" }}>Concepto</span>
              <input
                type="text"
                aria-label="Concepto de la orden"
                value={concepto}
                onChange={(e) => setConcepto(e.target.value)}
              />
            </label>
          </div>

          <h3 style={{ fontSize: 15, color: "#16315F", marginTop: 16 }}>Líneas</h3>
          {lineas.map((linea, indice) => (
            <div key={indice} style={{ display: "flex", gap: 8, marginBottom: 8 }}>
              <input
                type="text"
                aria-label={`Código del producto ${indice + 1}`}
                placeholder="Código de producto"
                value={linea.productCode}
                onChange={(e) =>
                  setLineas((previo) =>
                    previo.map((l, i) => (i === indice ? { ...l, productCode: e.target.value } : l)),
                  )
                }
                style={{ flexGrow: 1 }}
              />
              <input
                type="text"
                aria-label={`Cantidad de la línea ${indice + 1}`}
                placeholder="Cantidad"
                value={linea.quantity}
                onChange={(e) =>
                  setLineas((previo) =>
                    previo.map((l, i) => (i === indice ? { ...l, quantity: e.target.value } : l)),
                  )
                }
                style={{ width: 120 }}
              />
              {lineas.length > 1 && (
                <button
                  type="button"
                  onClick={() => setLineas((previo) => previo.filter((_, i) => i !== indice))}
                >
                  Quitar
                </button>
              )}
            </div>
          ))}

          <div style={{ display: "flex", gap: 12, marginTop: 8 }}>
            <button
              type="button"
              onClick={() => setLineas((previo) => [...previo, { productCode: "", quantity: "" }])}
            >
              Añadir línea
            </button>
            <button type="button" disabled={creando} onClick={() => void crear()}>
              {creando ? "Creando…" : "Crear orden"}
            </button>
          </div>
        </section>
      )}

      {cargando && <p style={{ color: "#4A5A72" }}>Cargando órdenes…</p>}

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
              Esta pantalla exige el permiso <code>order.view</code>. Pídeselo a un
              administrador.
            </div>
          )}
          <button type="button" onClick={() => void cargar()}>
            Reintentar
          </button>
        </div>
      )}

      {!cargando && !error && ordenes && ordenes.length === 0 && (
        <p style={{ color: "#4A5A72" }}>
          {estado
            ? "No hay ninguna orden en ese estado."
            : "Todavía no hay órdenes de surtido. Se crean desde la API con su fecha de " +
              "empaque y sus líneas; esta pantalla las consulta, las empaca y las confirma."}
        </p>
      )}

      {!cargando && !error && ordenes && ordenes.length > 0 && (
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ background: "#E4EBF5", color: "#16315F", textAlign: "left" }}>
              <th style={{ padding: 10 }}>Orden</th>
              <th style={{ padding: 10 }}>Cliente</th>
              <th style={{ padding: 10 }}>Estado</th>
              <th style={{ padding: 10, textAlign: "right" }}>Líneas</th>
              <th style={{ padding: 10, textAlign: "right" }}>Completas</th>
              <th style={{ padding: 10 }}>ERP</th>
              <th style={{ padding: 10 }}>Detalle</th>
            </tr>
          </thead>
          <tbody>
            {ordenes.map((o) => (
              <tr key={o.id} style={{ borderBottom: "1px solid #E4EBF5" }}>
                <td style={{ padding: 10 }}>{o.orderNumber}</td>
                <td style={{ padding: 10 }}>{o.customerCode ?? "—"}</td>
                <td style={{ padding: 10, color: colorEstado(o.status), fontWeight: 600 }}>
                  {ESTADO[o.status] ?? o.status}
                </td>
                <td style={{ padding: 10, textAlign: "right" }}>{o.totalLines}</td>
                <td
                  style={{
                    padding: 10,
                    textAlign: "right",
                    // Cuando no están todas, el número va destacado: es lo que
                    // falta por empacar y lo que decide si se puede confirmar.
                    color: o.packagedLines < o.totalLines ? "#F28C28" : "#2E7D5B",
                    fontWeight: 600,
                  }}
                >
                  {o.packagedLines} / {o.totalLines}
                </td>
                <td style={{ padding: 10 }}>
                  {/*
                    La exportación NO es un botón de esta tabla: reintentarla es un
                    acto deliberado y va en el detalle. Aquí solo se dice en qué
                    estado está y cuántos intentos lleva, que es lo que permite
                    detectar una orden atascada sin abrirla.
                  */}
                  {o.converted ? (
                    <span style={{ color: "#2E7D5B" }}>Recibida</span>
                  ) : o.exportAttempts > 0 ? (
                    <span style={{ color: "#C0392B" }}>
                      {o.exportAttempts} intento(s)
                      {o.exportLastError ? `: ${o.exportLastError}` : ""}
                    </span>
                  ) : (
                    <span style={{ color: "#8A94A6" }}>—</span>
                  )}
                </td>
                <td style={{ padding: 10 }}>
                  <Link to={`/orders/${o.id}`}>Abrir</Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </PageShell>
  );
}
