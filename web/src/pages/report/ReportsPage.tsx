import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@shared/api";
import { fetchKardexVsProjection } from "@entities/report/api/reportApi";
import type { KardexVsProjectionReport } from "@shared/api";
import { PageShell } from "@shared/ui/page-shell";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * REPORTES — `GET /reports/kardex-vs-projection`
 *
 * **LAS RUTAS VAN SIN `/v1`.** `API_CONSTANTS.BASE_URL` ya vale `/v1`, así que
 * escribir `/v1/reports/...` compone `/v1/v1/reports/...` y el backend responde
 * 404. Es el mismo error que ya se cometió en este proyecto (queda documentado en
 * `PendingApprovalPage`), y ni `tsc` ni `eslint` lo detectan: **una URL solo se
 * comprueba llamándola**.
 *
 * Esta pantalla muestra el reporte que **el legacy no tenía**: compara el total de
 * cada producto contra la suma de sus ubicaciones (la función de BD
 * `comprobar_proyecciones()`). Sin él, un descuadre solo se descubría con un
 * conteo físico, es decir, cuando ya nadie sabía cuándo ni cómo se rompió. Por eso
 * el resultado se enseña como un **veredicto**, no como una tabla más:
 *
 *  - `balanced: true`  → verde y tranquilizador: no hay nada que investigar;
 *  - `balanced: false` → **rojo y alarmante**: significa que el inventario no
 *    cuadra, y las diferencias hay que investigarlas **antes** de seguir
 *    operando, porque cualquier ajuste posterior se calcularía sobre un total
 *    equivocado.
 *
 * Otras tres decisiones:
 *
 *  1. **Los totales se tipan como `string`, no como `number`.** El servidor los
 *     manda con `BigDecimal.toPlainString()` (`"5193.000"`), que en JSON es una
 *     cadena con 3 decimales. Leerlos como `number` compila igual y pinta mal el
 *     valor (como en el kardex, donde `units` también es cadena).
 *  2. **El tipo sigue lo que el servidor manda de verdad.** Estuvo mal en los dos
 *     lados: el contrato §9 prometía `productsChecked`/`ledgerNet`/`projected` y la
 *     ruta respondía `cuadra`/`totalEnBd`/`totalEsperado`/`diferencia`, unos campos
 *     en español que además incumplían la norma de idioma. Se corrigieron la ruta
 *     (migración `V8` más el DTO) y el contrato: hoy son
 *     `balanced`/`projectedTotal`/`ledgerTotal`/`difference`, y **este tipo tiene
 *     que coincidir con ellos**. Tipar el contrato en vez de la respuesta pinta
 *     `undefined`, y al revés rompe al cliente en el siguiente despliegue.
 *  3. **No se recalcula nada en el cliente.** Sumar ubicaciones en el navegador
 *     produciría un segundo cálculo que puede discrepar del primero, y entonces el
 *     descuadre sería el de la pantalla, no el del inventario.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Instante ISO en UTC → fecha y hora del reloj del usuario. */
function formatearFechaHora(iso: string): string {
  const fecha = new Date(iso);
  // Un instante ilegible se muestra crudo en vez de «Invalid Date»: ver el valor
  // original es lo que permite notar que el campo llegó mal.
  return Number.isNaN(fecha.getTime()) ? iso : fecha.toLocaleString();
}

export default function ReportsPage() {
  const [reporte, setReporte] = useState<KardexVsProjectionReport | null>(null);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Un 403 no es un fallo de red: es un permiso que falta. Se distingue para
  // decir cuál, porque el usuario no puede adivinar que la pantalla se abre con
  // `report.view` pero la ruta exige `inventory.reconcile`.
  const [sinPermiso, setSinPermiso] = useState(false);

  const comprobar = useCallback(async () => {
    setCargando(true);
    setError(null);
    setSinPermiso(false);
    try {
      // Repetir la comprobación es barato y útil: el reporte es una foto del
      // momento, no un histórico, así que se vuelve a pedir y no se cachea.
      const respuesta = await fetchKardexVsProjection();
      setReporte(respuesta);
    } catch (e) {
      // El veredicto anterior se descarta: dejarlo en pantalla después de un
      // fallo haría creer que el inventario se comprobó y está bien.
      setReporte(null);
      setSinPermiso(e instanceof ApiError && e.status === 403);
      setError(e instanceof Error ? e.message : "No se pudo comprobar la reconciliación.");
    } finally {
      setCargando(false);
    }
  }, []);

  useEffect(() => {
    void comprobar();
  }, [comprobar]);

  return (
    <PageShell
      title="Reportes"
      description="Reconciliación del inventario: el total de cada producto contra la suma de sus ubicaciones."
      breadcrumbLabel="Reportes"
    >
      {cargando && <p style={{ color: "#4A5A72" }}>Comprobando el inventario…</p>}

      {!cargando && error && (
        <div
          role="alert"
          style={{ background: "#FDECEA", color: "#C0392B", padding: 16, marginBottom: 16 }}
        >
          <div style={{ fontWeight: 600, marginBottom: 8 }}>
            No se pudo comprobar la reconciliación.
          </div>
          <div style={{ marginBottom: 8 }}>{error}</div>
          {sinPermiso && (
            // El código real es `PERMISSION_DENIED`/`SCOPE_INSUFFICIENT`. Se dice
            // el permiso que exige la ruta para que un administrador pueda
            // corregirlo sin leer el código del backend.
            <div style={{ marginBottom: 8 }}>
              Esta ruta exige el permiso <code>inventory.reconcile</code>, que la
              pantalla no comprueba al abrirse (su ruta se guarda con{" "}
              <code>report.view</code>). Pídeselo a un administrador.
            </div>
          )}
          <button type="button" onClick={() => void comprobar()}>
            Reintentar
          </button>
        </div>
      )}

      {!cargando && !error && reporte && (
        <>
          {/*
            El veredicto ocupa el sitio principal de la pantalla a propósito: es
            la única información que exige una acción (o la descarta). Un dato
            así escondido en una línea de texto no se lee.
          */}
          <section
            role={reporte.balanced ? "status" : "alert"}
            style={{
              background: reporte.balanced ? "#E8F5EE" : "#FDECEA",
              borderLeft: `6px solid ${reporte.balanced ? "#2E7D5B" : "#C0392B"}`,
              padding: 20,
              marginBottom: 16,
            }}
          >
            <h2
              style={{
                margin: 0,
                fontSize: 22,
                color: reporte.balanced ? "#2E7D5B" : "#C0392B",
              }}
            >
              {reporte.balanced ? "El inventario cuadra" : "El inventario NO cuadra"}
            </h2>
            <p
              style={{
                margin: "8px 0 0",
                color: reporte.balanced ? "#2E7D5B" : "#C0392B",
              }}
            >
              {reporte.balanced
                ? "El total de cada producto coincide con la suma de sus ubicaciones. No hay descuadre que investigar."
                : `El total de ${reporte.divergences} producto(s) no coincide con la suma de sus ubicaciones. Hay que investigar las diferencias de la tabla antes de seguir operando: un ajuste calculado sobre un total equivocado solo tapa el problema.`}
            </p>
          </section>

          <p style={{ color: "#4A5A72" }}>
            Comprobado el <strong>{formatearFechaHora(reporte.checkedAt)}</strong> (hora
            local de este equipo; el servidor lo sella en UTC).{" "}
            {/* Se explica por qué la pantalla no tiene histórico: el legacy no
                tenía esta comprobación en absoluto y no hay serie que enseñar. */}
            Es una foto del momento, no un histórico: el legacy no hacía esta
            comprobación, así que no existe serie previa con la que comparar.
            <button
              type="button"
              onClick={() => void comprobar()}
              style={{ marginLeft: 12, background: "#16315F", color: "#fff", padding: "6px 14px" }}
            >
              Volver a comprobar
            </button>
          </p>

          {!reporte.balanced && reporte.items.length === 0 && (
            // Cinturón y tirantes: si el servidor dice que hay divergencias pero
            // no manda ninguna fila, callarlo dejaría un «no cuadra» sin nada que
            // mirar. Es un fallo del reporte y se dice como tal.
            <p role="alert" style={{ color: "#C0392B", background: "#FDECEA", padding: 12 }}>
              El servidor informa {reporte.divergences} divergencia(s) pero no envió
              ninguna fila de detalle. El reporte está incompleto: no se puede saber
              qué productos revisar.
            </p>
          )}

          {reporte.items.length > 0 && (
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
              <thead>
                <tr style={{ background: "#E4EBF5", color: "#16315F", textAlign: "left" }}>
                  <th style={{ padding: 10 }}>Producto</th>
                  <th style={{ padding: 10, textAlign: "right" }}>Total en BD</th>
                  <th style={{ padding: 10, textAlign: "right" }}>Suma de ubicaciones</th>
                  <th style={{ padding: 10, textAlign: "right" }}>Diferencia</th>
                </tr>
              </thead>
              <tbody>
                {reporte.items.map((d) => (
                  <tr key={d.productId} style={{ borderBottom: "1px solid #E4EBF5" }}>
                    <td style={{ padding: 10 }}>
                      <div style={{ fontWeight: 600 }}>{d.productCode}</div>
                      {/* El id va como dato secundario: es lo que se pega en una
                          consulta para investigar el descuadre. */}
                      <div style={{ fontSize: 12, color: "#4A5A72" }}>id {d.productId}</div>
                    </td>
                    <td
                      style={{
                        padding: 10,
                        textAlign: "right",
                        fontVariantNumeric: "tabular-nums",
                        whiteSpace: "nowrap",
                      }}
                    >
                      {d.projectedTotal}
                    </td>
                    <td
                      style={{
                        padding: 10,
                        textAlign: "right",
                        fontVariantNumeric: "tabular-nums",
                        whiteSpace: "nowrap",
                      }}
                    >
                      {d.ledgerTotal}
                    </td>
                    <td
                      style={{
                        padding: 10,
                        textAlign: "right",
                        fontVariantNumeric: "tabular-nums",
                        whiteSpace: "nowrap",
                        color: "#C0392B",
                        fontWeight: 600,
                      }}
                    >
                      {d.difference}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <p style={{ marginTop: 16, fontSize: 12, color: "#4A5A72" }}>
            De los cuatro reportes del contrato, hoy solo está publicado este.{" "}
            <code>GET /v1/reports/inventory</code>,{" "}
            <code>GET /v1/reports/movements-summary</code> y{" "}
            <code>GET /v1/reports/kardex/export</code> responden 404, así que la
            pantalla no los ofrece: un botón que devuelve 404 es peor que un botón
            que no está.
          </p>
        </>
      )}
    </PageShell>
  );
}
