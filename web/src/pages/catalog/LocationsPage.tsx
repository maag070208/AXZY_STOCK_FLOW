import { useCallback, useEffect, useState, type CSSProperties } from "react";
import { get, type Page } from "@shared/api";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * UBICACIONES (estantes y bodegas)
 *
 * Pantalla 7/13 del legacy (`Locations.tsx`, 645 LOC): la peor del frontend
 * anterior, porque concentraba cinco flujos en un archivo (lista, alta/edición,
 * borrado, buscar, mover ubicación, mover producto y etiquetas). Aquí sólo se
 * consulta el catálogo físico; mover stock es de `InventoryPage`.
 *
 * Cuatro decisiones que conviene no «mejorar» sin leer esto:
 *
 *  1. **La ruta va sin `/v1`.** La URL base del cliente ya es `/v1`, así que
 *     `/v1/locations` compondría `/v1/v1/locations` y daría 404.
 *
 *  2. **El nombre visible lo compone el SERVIDOR** (`displayName`). El backend
 *     une las tres partes sin repetirlas (`04` + `B` + `C` → `04-B-C`, y
 *     `RECEPCION` en lugar de `RECEPCION-RECEPCION-RECEPCION`). Repetir esa
 *     regla aquí crearía dos versiones que se separarían en cuanto cambie una.
 *     Por eso esta pantalla nunca concatena `hall`/`code`/`position`.
 *
 *  3. **`/v1/locations` SÍ está paginado**, al contrario de lo que se suponía.
 *     Devuelve el mismo sobre `PageResponse` que el resto de listados
 *     (`items`, `page`, `size`, `totalItems`, `totalPages`), comprobado contra
 *     el backend en marcha. Leerlo como si fuera una lista directa daría
 *     `undefined` en lugar de las filas. La paginación se pide con un tamaño
 *     alto (200, el tope) porque el catálogo es pequeño, pero se respeta
 *     `totalPages` por si deja de serlo: pedir 200 y mostrar los primeros 200 en
 *     silencio es justo el fallo que el contrato evita.
 *
 *  4. **`kind=UNASSIGNED` no se ofrece como filtro.** El backend valida el
 *     filtro contra un enumerado que sólo conoce `SHELF` y `RECEIVING`, y
 *     responde `422 INVALID_LOCATION_KIND` para `UNASSIGNED`, aunque existan
 *     filas con ese tipo. Filtrarlas desde aquí sería ofrecer un botón que
 *     siempre falla. El distintivo de la fila sale del `kind` de la propia fila,
 *     no del filtro.
 *
 * `/v1/warehouses` es la excepción documentada de §1.4: **no** lleva sobre de
 * paginación porque es un catálogo cerrado que la interfaz necesita completo
 * para poblar el desplegable. Devuelve `{ items: [...] }` sin `page` ni
 * `totalItems`.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Campos exactos de `LocationResponse` del backend (CatalogDtos.kt). */
interface Ubicacion {
  id: number;
  warehouseId: number;
  warehouseCode: string;
  hall: string;
  code: string;
  position: string;
  levels: number;
  kind: string;
  active: boolean;
  /** Nombre ya compuesto por el servidor. No se recompone en el cliente. */
  displayName: string;
}

/** Campos exactos de `WarehouseResponse`. */
interface Bodega {
  id: number;
  code: string;
  name: string;
  isDefault: boolean;
  active: boolean;
}

/** Tope de `size` que acepta el backend. */
const TAMANO = 200;

const MILES = new Intl.NumberFormat("es-ES");

const RECORTE: CSSProperties = {
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
};

/**
 * Etiqueta legible del tipo de ubicación.
 *
 * `UNASSIGNED` existe en los datos aunque no se pueda filtrar por él (ver la
 * cabecera del archivo): los 10.914.130 unidades heredadas del sistema anterior
 * viven ahí.
 */
function etiquetaDeTipo(kind: string): string {
  switch (kind) {
    case "SHELF":
      return "Estante";
    case "RECEIVING":
      return "Recepción";
    case "UNASSIGNED":
      return "Sin ubicar";
    default:
      return kind;
  }
}

export default function LocationsPage() {
  const [ubicaciones, setUbicaciones] = useState<Ubicacion[]>([]);
  const [bodegas, setBodegas] = useState<Bodega[]>([]);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // El fallo de las bodegas no puede tumbar la tabla: son un filtro auxiliar.
  const [errorBodegas, setErrorBodegas] = useState<string | null>(null);

  const [pagina, setPagina] = useState(0);
  const [totalItems, setTotalItems] = useState(0);
  const [totalPaginas, setTotalPaginas] = useState(0);

  // Filtros admitidos por el endpoint: `q`, `warehouseId`, `kind`, `active`.
  const [busqueda, setBusqueda] = useState("");
  const [termino, setTermino] = useState("");
  const [bodegaId, setBodegaId] = useState<number | "">("");
  const [tipo, setTipo] = useState<"" | "SHELF" | "RECEIVING">("");
  const [activo, setActivo] = useState<"" | "true" | "false">("");

  const cargarUbicaciones = useCallback(async () => {
    setCargando(true);
    setError(null);
    try {
      // Sobre paginado, no lista directa (ver punto 3 de la cabecera).
      const respuesta = await get<Page<Ubicacion>>("/locations", {
        params: {
          page: pagina,
          size: TAMANO,
          q: termino || undefined,
          warehouseId: bodegaId === "" ? undefined : bodegaId,
          kind: tipo || undefined,
          // `active=` vacío es un 400, no un «sin filtro»: se omite.
          active: activo === "" ? undefined : activo,
        },
      });
      setUbicaciones(respuesta.items ?? []);
      setTotalItems(respuesta.totalItems ?? 0);
      setTotalPaginas(respuesta.totalPages ?? 0);
    } catch (e) {
      setError(e instanceof Error ? e.message : "No se pudieron cargar las ubicaciones.");
    } finally {
      setCargando(false);
    }
  }, [pagina, termino, bodegaId, tipo, activo]);

  /**
   * Bodegas: sin paginación y una sola vez.
   *
   * No dependen de los filtros de la tabla, así que volver a pedirlas en cada
   * consulta sería tráfico regalado. Si fallan, el desplegable queda vacío pero
   * la tabla sigue siendo utilizable; por eso el error se guarda aparte.
   */
  const cargarBodegas = useCallback(async () => {
    setErrorBodegas(null);
    try {
      const respuesta = await get<{ items: Bodega[] }>("/warehouses");
      setBodegas(respuesta.items ?? []);
    } catch (e) {
      setErrorBodegas(
        e instanceof Error ? e.message : "No se pudieron cargar las bodegas."
      );
    }
  }, []);

  useEffect(() => {
    void cargarUbicaciones();
  }, [cargarUbicaciones]);

  useEffect(() => {
    void cargarBodegas();
  }, [cargarBodegas]);

  const nombreDeBodega = new Map(bodegas.map((b) => [b.id, b.name]));
  const hayFiltros =
    termino !== "" || bodegaId !== "" || tipo !== "" || activo !== "";

  return (
    <div style={{ padding: 24 }}>
      <h1>Ubicaciones</h1>
      <p style={{ color: "#4A5A72" }}>
        Catálogo físico de estantes y zonas de la bodega. El nombre de la
        ubicación es el que compone el servidor a partir del pasillo, el código y
        la posición.
      </p>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          setPagina(0);
          setTermino(busqueda.trim());
        }}
        style={{
          display: "flex",
          flexWrap: "wrap",
          gap: 12,
          alignItems: "center",
          background: "#F2F6FC",
          padding: 12,
          marginBottom: 16,
        }}
      >
        <label style={{ display: "flex", gap: 6, alignItems: "center" }}>
          Buscar
          <input
            type="search"
            value={busqueda}
            onChange={(e) => setBusqueda(e.target.value)}
            placeholder="Pasillo, código o posición"
            aria-label="Buscar ubicación"
            style={{ padding: 8, minWidth: 200 }}
          />
        </label>

        <button
          type="submit"
          style={{ background: "#16315F", color: "#fff", padding: "8px 16px", border: 0 }}
        >
          Buscar
        </button>

        <label style={{ display: "flex", gap: 6, alignItems: "center" }}>
          Bodega
          <select
            value={bodegaId}
            onChange={(e) => {
              setBodegaId(e.target.value === "" ? "" : Number(e.target.value));
              setPagina(0);
            }}
            aria-label="Filtrar por bodega"
            style={{ padding: 8 }}
          >
            <option value="">Todas</option>
            {bodegas.map((b) => (
              <option key={b.id} value={b.id}>
                {b.name}
              </option>
            ))}
          </select>
        </label>

        <label style={{ display: "flex", gap: 6, alignItems: "center" }}>
          Tipo
          <select
            value={tipo}
            onChange={(e) => {
              setTipo(e.target.value as "" | "SHELF" | "RECEIVING");
              setPagina(0);
            }}
            aria-label="Filtrar por tipo"
            style={{ padding: 8 }}
          >
            {/* «Sin ubicar» no aparece: el backend rechaza `kind=UNASSIGNED`. */}
            <option value="">Todos los tipos</option>
            <option value="SHELF">Estante</option>
            <option value="RECEIVING">Recepción</option>
          </select>
        </label>

        <label style={{ display: "flex", gap: 6, alignItems: "center" }}>
          Estado
          <select
            value={activo}
            onChange={(e) => {
              setActivo(e.target.value as "" | "true" | "false");
              setPagina(0);
            }}
            aria-label="Filtrar por estado"
            style={{ padding: 8 }}
          >
            <option value="">Todas</option>
            <option value="true">Sólo activas</option>
            <option value="false">Sólo inactivas</option>
          </select>
        </label>

        {hayFiltros && (
          <button
            type="button"
            onClick={() => {
              setBusqueda("");
              setTermino("");
              setBodegaId("");
              setTipo("");
              setActivo("");
              setPagina(0);
            }}
            style={{ padding: "8px 16px" }}
          >
            Limpiar
          </button>
        )}
      </form>

      {errorBodegas && (
        <p style={{ color: "#C0392B", background: "#FDECEA", padding: 12 }}>
          Las bodegas no se pudieron cargar ({errorBodegas}). El filtro por bodega
          queda sin opciones; la tabla sigue disponible.
        </p>
      )}

      <p style={{ color: "#16315F", fontWeight: 600 }} aria-live="polite">
        {MILES.format(totalItems)} ubicaciones
        {totalPaginas > 1 ? ` · página ${pagina + 1} de ${totalPaginas}` : ""}
        {cargando ? " · cargando…" : ""}
      </p>

      {error && (
        <p role="alert" style={{ color: "#C0392B", background: "#FDECEA", padding: 12 }}>
          {error}{" "}
          <button type="button" onClick={() => void cargarUbicaciones()}>
            Reintentar
          </button>
        </p>
      )}

      {!error && !cargando && ubicaciones.length === 0 && (
        <p style={{ color: "#4A5A72", background: "#F2F6FC", padding: 16 }}>
          No hay ubicaciones que coincidan con los filtros.
        </p>
      )}

      {ubicaciones.length > 0 && (
        <table
          style={{ width: "100%", borderCollapse: "collapse", tableLayout: "fixed" }}
          aria-busy={cargando}
        >
          <colgroup>
            <col style={{ width: "30%" }} />
            <col style={{ width: "16%" }} />
            <col style={{ width: "26%" }} />
            <col style={{ width: "12%" }} />
            <col style={{ width: "16%" }} />
          </colgroup>
          <thead>
            <tr style={{ background: "#E4EBF5", color: "#16315F", textAlign: "left" }}>
              <th style={{ padding: 10 }}>Ubicación</th>
              <th style={{ padding: 10 }}>Tipo</th>
              <th style={{ padding: 10 }}>Bodega</th>
              <th style={{ padding: 10, textAlign: "right" }}>Niveles</th>
              <th style={{ padding: 10 }}>Estado</th>
            </tr>
          </thead>
          <tbody>
            {ubicaciones.map((u) => {
              const sinUbicar = u.kind === "UNASSIGNED";
              return (
                <tr
                  key={u.id}
                  style={{
                    borderBottom: "1px solid #E4EBF5",
                    // El saldo heredado no es una ubicación física: se marca la
                    // fila entera para que no se confunda con un estante real.
                    background: sinUbicar ? "#FFF3E0" : undefined,
                    borderLeft: sinUbicar ? "4px solid #F28C28" : "4px solid transparent",
                  }}
                >
                  {/* Nombre del servidor, nunca recompuesto aquí. */}
                  <td style={{ padding: 10, ...RECORTE }} title={u.displayName}>
                    <span style={{ fontWeight: 600 }}>{u.displayName}</span>
                  </td>
                  <td style={{ padding: 10 }}>
                    <span
                      style={{
                        background: sinUbicar ? "#F28C28" : "#E4EBF5",
                        color: sinUbicar ? "#fff" : "#16315F",
                        padding: "2px 8px",
                        whiteSpace: "nowrap",
                      }}
                    >
                      {etiquetaDeTipo(u.kind)}
                    </span>
                  </td>
                  <td style={{ padding: 10, ...RECORTE }} title={u.warehouseCode}>
                    {/* El nombre de la bodega viene del catálogo de bodegas; el
                        código de la propia fila sirve de respaldo si aquel no
                        cargó. */}
                    {nombreDeBodega.get(u.warehouseId) ?? u.warehouseCode}
                  </td>
                  <td style={{ padding: 10, textAlign: "right" }}>{u.levels}</td>
                  <td style={{ padding: 10 }}>
                    {u.active ? (
                      <span
                        style={{
                          background: "#E8F5EE",
                          color: "#2E7D5B",
                          padding: "2px 8px",
                          whiteSpace: "nowrap",
                        }}
                      >
                        Activa
                      </span>
                    ) : (
                      <span
                        style={{
                          background: "#F2F6FC",
                          color: "#4A5A72",
                          padding: "2px 8px",
                          whiteSpace: "nowrap",
                        }}
                      >
                        Inactiva
                      </span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      {/* La nota sólo aparece si la fila especial está a la vista: explicar un
          distintivo que no se ve no ayuda a nadie. */}
      {ubicaciones.some((u) => u.kind === "UNASSIGNED") && (
        <div
          style={{
            marginTop: 16,
            background: "#FFF3E0",
            borderLeft: "4px solid #F28C28",
            padding: 12,
            color: "#4A5A72",
          }}
        >
          <strong style={{ color: "#16315F" }}>Qué es «Sin ubicar»</strong>
          <p style={{ margin: "6px 0 0" }}>
            No es un estante: es el cajón donde quedó el saldo que el sistema
            anterior no tenía ubicado, <strong>10.914.130 unidades</strong>. Ese
            stock cuenta en el total del producto, pero no está en ningún sitio
            físico al que se pueda ir a recogerlo. La ubicación de recepción es
            distinta: es real y por ahí entra lo que se recibe antes de ubicarse.
          </p>
        </div>
      )}

      {totalPaginas > 1 && (
        <div style={{ display: "flex", gap: 12, alignItems: "center", marginTop: 16 }}>
          <button
            type="button"
            disabled={pagina === 0 || cargando}
            onClick={() => setPagina((p) => Math.max(0, p - 1))}
            style={{ padding: "8px 16px" }}
          >
            Anterior
          </button>
          <span>
            Página {pagina + 1} de {totalPaginas}
          </span>
          <button
            type="button"
            disabled={pagina + 1 >= totalPaginas || cargando}
            onClick={() => setPagina((p) => p + 1)}
            style={{ padding: "8px 16px" }}
          >
            Siguiente
          </button>
        </div>
      )}
    </div>
  );
}
