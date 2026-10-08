import { useCallback, useEffect, useState } from "react";
import { get, type Page } from "@shared/api";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EXISTENCIAS POR UBICACIÓN — `GET /inventory/stock`
 *
 * Se lee la proyección `stock_by_location`, que es el saldo real. El legacy
 * recalculaba el saldo sumando movimientos ya mutados (`GET /get-location-content`)
 * y de ahí venían los descuadres: aquí el número se lee, no se deduce.
 *
 * Tres decisiones que no son de estilo:
 *
 *  1. **Los filtros vacíos no se envían.** El backend decide con
 *     `if (productCode != null)`, así que `productCode=` (cadena vacía) es un
 *     filtro real por la cadena vacía y devuelve **cero filas**. Los parámetros
 *     se añaden solo cuando el usuario escribió algo.
 *  2. **`size` se elige de una lista cerrada.** Por encima de 200 el servidor
 *     responde `400 INVALID_PAGINATION` y no recorta en silencio; con una lista
 *     cerrada la interfaz no puede construir una petición que vaya a fallar.
 *  3. **No se pinta un total, y no depende de `totalPages`.** El sobre de esta
 *     ruta llegó a construirse con `items.size` como `totalItems`, así que el
 *     «total» era el tamaño de la página (con 1.398 productos decía «50»).
 *     Mientras se escribía esto el backend se estaba corrigiendo y ya devuelve
 *     el total real. La pantalla **no se apoya en ese número de ninguna de las
 *     dos formas**: informa de la página y de las filas que vinieron. Así es
 *     correcta tanto si el arreglo está desplegado como si no.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Campos que el servidor devuelve de verdad.
 *
 * **No se reutiliza `StockItem` de `@shared/api`**: ese tipo declara
 * `quantity: number` y no tiene `productName` ni `warehouseCode`, pero el
 * backend manda la cantidad como **cadena** con 3 decimales
 * (`"quantity": "5193.000"`) y sí añade ambos campos. Tiparlo como `number`
 * compilaría y luego rompería al formatear, así que se declara aquí lo que
 * llega por el cable.
 */
interface Existencia {
  productId: number;
  productCode: string;
  productName: string;
  locationId: number;
  locationDisplayName: string;
  warehouseId: number;
  warehouseCode: string;
  /** Decimal de 3 posiciones serializado como texto, tal cual llega. */
  quantity: string;
}

/**
 * Tamaños ofrecidos. El tope de 200 es del contrato
 * (`PageRequest.TAMANO_MAXIMO`): por encima, la petición se rechaza.
 */
const TAMANOS = [25, 50, 100, 200];

export default function InventoryPage() {
  const [filas, setFilas] = useState<Existencia[]>([]);
  const [pagina, setPagina] = useState(0);
  const [tamano, setTamano] = useState(50);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState<string | null>(null);

  /**
   * El borrador de los inputs y el filtro ya aplicado van separados: escribir no
   * debe consultar. Sin esta separación cada tecla dispararía una petición, y
   * con `size=200` eso es una consulta por pulsación.
   */
  const [borradorProducto, setBorradorProducto] = useState("");
  const [borradorUbicacion, setBorradorUbicacion] = useState("");
  const [filtroProducto, setFiltroProducto] = useState("");
  const [filtroUbicacion, setFiltroUbicacion] = useState("");

  const cargar = useCallback(async () => {
    setCargando(true);
    setError(null);
    try {
      const respuesta = await get<Page<Existencia>>("/inventory/stock", {
        params: {
          page: pagina,
          size: tamano,
          // El servidor ya asume `true`, pero se declara: la pantalla no tiene
          // sentido con las filas en cero.
          onlyWithStock: true,
          // `undefined` desaparece de la query; la cadena vacía viajaría como
          // filtro real y vaciaría la tabla.
          productCode: filtroProducto || undefined,
          locationCode: filtroUbicacion || undefined,
        },
      });
      setFilas(respuesta.items ?? []);
    } catch (e) {
      setError(e instanceof Error ? e.message : "No se pudieron cargar las existencias.");
    } finally {
      setCargando(false);
    }
  }, [pagina, tamano, filtroProducto, filtroUbicacion]);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  /** Aplica lo escrito y vuelve a la página 0: la página 7 de un filtro nuevo
   *  no existe, y el `OFFSET` resultante devolvería una tabla vacía. */
  function buscar() {
    setPagina(0);
    setFiltroProducto(borradorProducto.trim());
    setFiltroUbicacion(borradorUbicacion.trim());
  }

  // «Siguiente» se habilita con la página llena, que es una señal que nunca
  // miente, en lugar de con `totalPages`: ese campo llegó a valer 1 siempre
  // (ver cabecera) y la pantalla seguiría siendo correcta si volviera a estarlo.
  // Consecuencia asumida: si el total es múltiplo exacto de `size`, la última
  // página se alcanza y la siguiente sale vacía; el paginador sigue visible
  // para poder volver.
  const haySiguiente = filas.length === tamano;
  const primeraFila = pagina * tamano + (filas.length > 0 ? 1 : 0);
  const ultimaFila = pagina * tamano + filas.length;

  return (
    <div style={{ padding: 24 }}>
      <h1>Existencias</h1>
      <p style={{ color: "#4A5A72" }}>
        Saldo por producto y ubicación, leído de la proyección{" "}
        <code>stock_by_location</code>. Solo se listan las existencias con saldo positivo.
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
          marginBottom: 8,
        }}
      >
        <label style={{ display: "grid", gap: 4 }}>
          <span style={{ fontSize: 13, color: "#4A5A72" }}>Código de producto</span>
          <input
            aria-label="Código de producto"
            value={borradorProducto}
            onChange={(e) => setBorradorProducto(e.target.value)}
            placeholder="ABRAA1B"
            style={{ padding: 6 }}
          />
        </label>

        <label style={{ display: "grid", gap: 4 }}>
          <span style={{ fontSize: 13, color: "#4A5A72" }}>Código de ubicación</span>
          <input
            aria-label="Código de ubicación"
            value={borradorUbicacion}
            onChange={(e) => setBorradorUbicacion(e.target.value)}
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

      {/*
        Las dos formas de nombrar una ubicación están comprobadas con `curl`:
        el nombre visible (`SIN-UBICAR`) y las tres partes concatenadas
        (`SINUBICAR`) devuelven filas. Un valor que no corresponde a ninguna
        ubicación devuelve cero, así que el filtro sí se aplica.
      */}
      <p style={{ fontSize: 12, color: "#4A5A72", marginTop: 0 }}>
        El filtro de ubicación acepta el nombre visible («SIN-UBICAR») o el código
        interno sin guiones («SINUBICAR»).
      </p>

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
        <p style={{ padding: 16, color: "#4A5A72" }}>Cargando existencias…</p>
      ) : (
        <>
          {filas.length === 0 ? (
            <p style={{ color: "#2E7D5B", background: "#E8F5EE", padding: 16 }}>
              No hay existencias que cumplan el filtro.
            </p>
          ) : (
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
              <thead>
                <tr style={{ background: "#E4EBF5", color: "#16315F", textAlign: "left" }}>
                  <th style={{ padding: 10 }}>Código</th>
                  <th style={{ padding: 10 }}>Producto</th>
                  <th style={{ padding: 10 }}>Ubicación</th>
                  <th style={{ padding: 10, textAlign: "right" }}>Existencia</th>
                </tr>
              </thead>
              <tbody>
                {filas.map((f) => (
                  // La clave es producto+ubicación porque un mismo producto
                  // aparece una vez por cada ubicación donde tenga saldo; usar
                  // `productId` solo colapsaría filas distintas.
                  <tr key={`${f.productId}-${f.locationId}`} style={{ borderBottom: "1px solid #E4EBF5" }}>
                    <td style={{ padding: 10, fontWeight: 600 }}>{f.productCode}</td>
                    <td style={{ padding: 10 }}>{f.productName}</td>
                    <td style={{ padding: 10 }}>
                      {f.locationDisplayName}
                      <span style={{ color: "#4A5A72", fontSize: 12 }}> · {f.warehouseCode}</span>
                    </td>
                    <td
                      style={{
                        padding: 10,
                        textAlign: "right",
                        // Cifras alineadas: comparar saldos de un vistazo exige
                        // que las columnas de dígitos coincidan.
                        fontVariantNumeric: "tabular-nums",
                      }}
                    >
                      {f.quantity}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {/*
            El paginador se pinta también con la tabla vacía, y no es un detalle
            estético: si se avanzara a una página sin filas y el paginador
            desapareciera, el usuario se quedaría encerrado en una pantalla en
            blanco sin forma de volver.
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
            </span>
            <button type="button" disabled={!haySiguiente || cargando} onClick={() => setPagina((p) => p + 1)}>
              Siguiente →
            </button>
          </div>
        </>
      )}
    </div>
  );
}
