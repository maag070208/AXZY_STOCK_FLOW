import { useCallback, useEffect, useState } from "react";
import { get, type Page } from "@shared/api";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * KARDEX — `GET /inventory/kardex`
 *
 * El libro de inventario. Dos cosas que la pantalla **no** hace, y son decisión
 * de diseño, no un olvido:
 *
 *  1. **No hay botones de editar ni de borrar, y no deben añadirse.** El ledger
 *     es *append-only*: un movimiento no se corrige, se compensa con otro. Por
 *     eso el backend no expone `PUT` ni `DELETE` sobre el kardex. Un botón de
 *     borrar aquí prometería algo que la API no puede cumplir y, si algún día
 *     pudiera, borraría la única prueba de lo que pasó.
 *  2. **No se recalcula el saldo en el cliente.** `quantityAfter` lo guarda el
 *     servidor al escribir el movimiento; sumar el ledger en el navegador
 *     produciría un segundo cálculo que puede discrepar del primero.
 *
 * Sobre los filtros: los parámetros vacíos **no se envían**. `moveType=` vacío
 * no significa «todos», significa castear `''` a `movement_type`, y eso revienta
 * con `500 INTERNAL_ERROR` (comprobado con `curl`). Una lista desplegable
 * cerrada lo hace imposible.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Asiento del kardex, con los campos que el servidor manda de verdad.
 *
 * **No se reutiliza `KardexEntry` de `@shared/api`** por dos motivos:
 *  - `units` y `quantityAfter` son **cadenas** con 3 decimales
 *    (`"units": "1008.000"`), no `number`;
 *  - el tipo compartido usa un `MoveType` que no es el del dominio.
 * Los campos que el servidor omite cuando son nulos van marcados como
 * opcionales: un movimiento `INITIAL` no trae `fromLocationDisplayName`,
 * `adjustmentReason` ni `userName`, y leerlos como si existieran daría
 * `undefined` pintado en la tabla.
 */
interface Asiento {
  id: number;
  movedAt: string;
  productCode: string;
  productName: string;
  moveType: string;
  units: string;
  quantityAfter: string;
  fromLocationDisplayName?: string | null;
  toLocationDisplayName?: string | null;
  container?: string | null;
  orderNumber?: string | null;
  adjustmentReason?: string | null;
  adjustmentOrigin?: string | null;
  userId?: number | null;
  userName?: string | null;
  note?: string | null;
}

/**
 * Tipos de movimiento, copiados de `MovementType`
 * (`stock-domain/.../inventory/StockMovement.kt`). **No se inventan.**
 *
 * Importa más de lo que parece: el servidor castea el valor a `movement_type`,
 * así que un tipo desconocido **no** devuelve `400`, devuelve
 * `500 INTERNAL_ERROR` (comprobado con `moveType=NOEXISTE`). Una lista cerrada
 * garantiza que la interfaz solo pueda pedir tipos que existen.
 *
 * El `MoveType` de `@shared/api` no sirve para esto: le faltan `INITIAL` y
 * `PENDING_OUT` e incluye un `MOVE` que el backend no conoce.
 */
const TIPOS = [
  { valor: "INITIAL", etiqueta: "Carga inicial" },
  { valor: "RECEIPT_IN", etiqueta: "Entrada por recepción" },
  { valor: "ADJUSTMENT_IN", etiqueta: "Ajuste de entrada" },
  { valor: "FULFILLMENT_OUT", etiqueta: "Salida por surtido" },
  { valor: "ADJUSTMENT_OUT", etiqueta: "Ajuste de salida (merma)" },
  { valor: "LOCATE", etiqueta: "Ubicación" },
  { valor: "MOVE_WITHIN_WAREHOUSE", etiqueta: "Traslado entre estantes" },
  { valor: "TRANSFER_OUT", etiqueta: "Salida a otra bodega" },
  { valor: "TRANSFER_IN", etiqueta: "Entrada desde otra bodega" },
  { valor: "PENDING_OUT", etiqueta: "Reserva de salida" },
];

const ETIQUETA_POR_TIPO = new Map(TIPOS.map((t) => [t.valor, t.etiqueta]));

const TAMANOS = [25, 50, 100, 200];

/**
 * Indica si el rango pedido no puede devolver nada.
 *
 * El servidor acepta un rango invertido sin decir nada y responde cero filas,
 * que se lee como «no hubo movimientos» cuando lo que pasa es que las fechas
 * están al revés. Se detecta para no mentir con un vacío.
 */
function esRangoInvertido(desde: string, hasta: string): boolean {
  return desde !== "" && hasta !== "" && desde > hasta;
}

/** Instante ISO en UTC → fecha y hora del reloj del usuario. */
function formatearFecha(iso: string): string {
  const fecha = new Date(iso);
  // Un instante ilegible se muestra crudo en vez de «Invalid Date»: es más
  // fácil ver que el dato llegó mal si se ve el valor original.
  return Number.isNaN(fecha.getTime()) ? iso : fecha.toLocaleString();
}

export default function KardexPage() {
  const [filas, setFilas] = useState<Asiento[]>([]);
  const [totalServidor, setTotalServidor] = useState(0);
  const [pagina, setPagina] = useState(0);
  const [tamano, setTamano] = useState(50);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Borrador (lo que se escribe) y filtro aplicado (lo que se consulta), para
  // que teclear no dispare una consulta por pulsación.
  const [borradorProducto, setBorradorProducto] = useState("");
  const [borradorTipo, setBorradorTipo] = useState("");
  const [borradorDesde, setBorradorDesde] = useState("");
  const [borradorHasta, setBorradorHasta] = useState("");

  const [filtroProducto, setFiltroProducto] = useState("");
  const [filtroTipo, setFiltroTipo] = useState("");
  const [filtroDesde, setFiltroDesde] = useState("");
  const [filtroHasta, setFiltroHasta] = useState("");

  const cargar = useCallback(async () => {
    // Un rango invertido no se consulta: la respuesta sería vacía y el vacío
    // diría «no hubo movimientos» en lugar de «las fechas están al revés».
    if (esRangoInvertido(filtroDesde, filtroHasta)) {
      setFilas([]);
      setCargando(false);
      setError(null);
      return;
    }
    setCargando(true);
    setError(null);
    try {
      const respuesta = await get<Page<Asiento>>("/inventory/kardex", {
        params: {
          page: pagina,
          size: tamano,
          // Ver cabecera: la cadena vacía no es «sin filtro» en este backend,
          // es un filtro por "" (y en `moveType` acaba en un 500).
          productCode: filtroProducto || undefined,
          moveType: filtroTipo || undefined,
          from: filtroDesde || undefined,
          to: filtroHasta || undefined,
        },
      });
      setFilas(respuesta.items ?? []);
      setTotalServidor(respuesta.totalItems ?? 0);
    } catch (e) {
      setError(e instanceof Error ? e.message : "No se pudo cargar el kardex.");
    } finally {
      setCargando(false);
    }
  }, [pagina, tamano, filtroProducto, filtroTipo, filtroDesde, filtroHasta]);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  /** Aplica los filtros y vuelve a la página 0: la página 7 de un filtro nuevo
   *  no existe. */
  function buscar() {
    setPagina(0);
    setFiltroProducto(borradorProducto.trim());
    setFiltroTipo(borradorTipo);
    setFiltroDesde(borradorDesde);
    setFiltroHasta(borradorHasta);
  }

  const rangoInvertido = esRangoInvertido(filtroDesde, filtroHasta);

  /**
   * `totalPages`/`totalItems` solo son fiables si **no** se filtró por fecha:
   * `contarKardex` recibe `productCode` y `tipo`, pero no `desde`/`hasta`
   * (ver `InventoryService.contarKardex`). Comprobado con `curl`: con
   * `from=2030-01-01` llegan 0 filas y `totalItems` sigue diciendo 1064. Por eso
   * «Siguiente» se habilita con la página llena, que nunca miente, y el total
   * solo se enseña cuando no hay rango de fechas.
   */
  const hayRangoDeFechas = filtroDesde !== "" || filtroHasta !== "";
  const haySiguiente = filas.length === tamano;
  const primeraFila = pagina * tamano + (filas.length > 0 ? 1 : 0);
  const ultimaFila = pagina * tamano + filas.length;

  return (
    <div style={{ padding: 24 }}>
      <h1>Kardex</h1>
      <p style={{ color: "#4A5A72" }}>
        Libro de movimientos de inventario. Es <strong>append-only</strong>: un movimiento
        no se edita ni se borra, se compensa con otro. Por eso esta pantalla no tiene
        acciones por fila.
      </p>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          buscar();
        }}
        style={{
          display: "flex",
          flexWrap: "wrap",
          gap: 12,
          alignItems: "flex-end",
          background: "#F2F6FC",
          padding: 16,
          marginBottom: 16,
        }}
      >
        <label style={{ display: "grid", gap: 4 }}>
          <span style={{ fontSize: 13, color: "#4A5A72" }}>Código de producto</span>
          <input
            aria-label="Código de producto"
            value={borradorProducto}
            onChange={(e) => setBorradorProducto(e.target.value)}
            placeholder="VSINFIN7161"
            style={{ padding: 6 }}
          />
        </label>

        <label style={{ display: "grid", gap: 4 }}>
          <span style={{ fontSize: 13, color: "#4A5A72" }}>Tipo de movimiento</span>
          <select
            aria-label="Tipo de movimiento"
            value={borradorTipo}
            onChange={(e) => setBorradorTipo(e.target.value)}
            style={{ padding: 6 }}
          >
            {/* La opción vacía no se envía: quitarla es «sin filtro», no un
                filtro por cadena vacía. */}
            <option value="">Todos</option>
            {TIPOS.map((t) => (
              <option key={t.valor} value={t.valor}>
                {t.etiqueta}
              </option>
            ))}
          </select>
        </label>

        <label style={{ display: "grid", gap: 4 }}>
          <span style={{ fontSize: 13, color: "#4A5A72" }}>Desde</span>
          <input
            aria-label="Desde"
            type="date"
            value={borradorDesde}
            onChange={(e) => setBorradorDesde(e.target.value)}
            style={{ padding: 6 }}
          />
        </label>

        <label style={{ display: "grid", gap: 4 }}>
          <span style={{ fontSize: 13, color: "#4A5A72" }}>Hasta</span>
          <input
            aria-label="Hasta"
            type="date"
            value={borradorHasta}
            onChange={(e) => setBorradorHasta(e.target.value)}
            style={{ padding: 6 }}
          />
        </label>

        <label style={{ display: "grid", gap: 4 }}>
          <span style={{ fontSize: 13, color: "#4A5A72" }}>Filas por página</span>
          <select
            aria-label="Filas por página"
            value={tamano}
            onChange={(e) => {
              setTamano(Number(e.target.value));
              setPagina(0);
            }}
            style={{ padding: 6 }}
          >
            {TAMANOS.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </label>

        <button
          type="submit"
          style={{
            background: "#F28C28",
            color: "#fff",
            padding: "8px 18px",
            border: "none",
            cursor: "pointer",
            fontWeight: 600,
          }}
        >
          Buscar
        </button>
      </form>

      {rangoInvertido && (
        <p role="alert" style={{ color: "#C0392B", background: "#FDECEA", padding: 12 }}>
          El rango de fechas está invertido: «Desde» es posterior a «Hasta», así que la
          consulta no puede devolver nada.
        </p>
      )}

      {error && (
        <div
          role="alert"
          style={{ background: "#FDECEA", color: "#C0392B", padding: 12, marginBottom: 16 }}
        >
          <div style={{ marginBottom: 8 }}>{error}</div>
          <button type="button" onClick={() => void cargar()}>
            Reintentar
          </button>
        </div>
      )}

      {cargando ? (
        <p style={{ padding: 16, color: "#4A5A72" }}>Cargando movimientos…</p>
      ) : rangoInvertido ? null : (
        <>
          {filas.length === 0 ? (
            <p style={{ color: "#2E7D5B", background: "#E8F5EE", padding: 16 }}>
              No hay movimientos que cumplan el filtro.
            </p>
          ) : (
          <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead>
              <tr style={{ background: "#E4EBF5", color: "#16315F", textAlign: "left" }}>
                <th style={{ padding: 10 }}>Fecha</th>
                <th style={{ padding: 10 }}>Producto</th>
                <th style={{ padding: 10 }}>Tipo</th>
                <th style={{ padding: 10, textAlign: "right" }}>Unidades</th>
                <th style={{ padding: 10 }}>Origen → destino</th>
                <th style={{ padding: 10 }}>Usuario</th>
              </tr>
            </thead>
            <tbody>
              {filas.map((a) => (
                <tr key={a.id} style={{ borderBottom: "1px solid #E4EBF5" }}>
                  <td style={{ padding: 10, whiteSpace: "nowrap" }}>{formatearFecha(a.movedAt)}</td>
                  <td style={{ padding: 10 }}>
                    <div style={{ fontWeight: 600 }}>{a.productCode}</div>
                    <div style={{ fontSize: 12, color: "#4A5A72" }}>{a.productName}</div>
                  </td>
                  <td style={{ padding: 10 }}>
                    <div>{ETIQUETA_POR_TIPO.get(a.moveType) ?? a.moveType}</div>
                    {/* Código crudo del contrato: quien audita lo busca tal cual,
                        y el nombre traducido puede cambiar sin avisar. */}
                    <div style={{ fontSize: 11, color: "#4A5A72" }}>{a.moveType}</div>
                    {/* El motivo es lo que hace auditable un ajuste; sin él, un
                        ADJUSTMENT_OUT es un agujero. Se pinta cuando existe. */}
                    {a.adjustmentReason && (
                      <div style={{ fontSize: 12, color: "#F28C28" }}>
                        Motivo: {a.adjustmentReason}
                      </div>
                    )}
                  </td>
                  <td
                    style={{
                      padding: 10,
                      textAlign: "right",
                      fontVariantNumeric: "tabular-nums",
                      whiteSpace: "nowrap",
                    }}
                  >
                    <div style={{ fontWeight: 600 }}>{a.units}</div>
                    {/* Saldo resultante que guarda el servidor. Se enseña como
                        dato secundario de la misma celda para no inventar una
                        columna que el contrato de la pantalla no pide. */}
                    <div style={{ fontSize: 11, color: "#4A5A72" }}>saldo: {a.quantityAfter}</div>
                  </td>
                  <td style={{ padding: 10 }}>
                    {(a.fromLocationDisplayName ?? "—") + " → " + (a.toLocationDisplayName ?? "—")}
                  </td>
                  <td style={{ padding: 10 }}>
                    {a.userName ?? (a.userId != null ? `#${a.userId}` : "—")}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          )}

          {/*
            El paginador se pinta también con la tabla vacía: si se avanzara a
            una página sin filas y el paginador desapareciera, el usuario se
            quedaría encerrado en una pantalla en blanco sin forma de volver.
          */}
          <div
            style={{
              display: "flex",
              gap: 12,
              alignItems: "center",
              marginTop: 16,
              color: "#4A5A72",
            }}
          >
            <button type="button" disabled={pagina === 0 || cargando} onClick={() => setPagina((p) => p - 1)}>
              ← Anterior
            </button>
            <span>
              {filas.length === 0
                ? `Página ${pagina + 1} · sin filas`
                : `Página ${pagina + 1} · filas ${primeraFila}–${ultimaFila}`}
              {!hayRangoDeFechas && totalServidor > 0 && ` de ${totalServidor} movimientos`}
            </span>
            <button type="button" disabled={!haySiguiente || cargando} onClick={() => setPagina((p) => p + 1)}>
              Siguiente →
            </button>
          </div>

          {hayRangoDeFechas && (
            // Se explica en vez de enseñar un total que no corresponde al rango.
            <p style={{ fontSize: 12, color: "#4A5A72" }}>
              Con un rango de fechas no se muestra el total: el servidor lo cuenta sin
              aplicar las fechas, así que no corresponde a lo que se está viendo.
            </p>
          )}
        </>
      )}
    </div>
  );
}
