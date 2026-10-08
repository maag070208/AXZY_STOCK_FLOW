import { useCallback, useEffect, useState, type CSSProperties, type FormEvent } from "react";
import { get, type Page } from "@shared/api";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CATÁLOGO DE PRODUCTOS
 *
 * Pantalla **nueva** en la web: el catálogo se editaba desde el móvil o a mano.
 * El listado trae `stock` porque es el dato más consultado y, con 1.398
 * productos, evitar una petición por fila es la diferencia entre una tabla
 * usable y una que no lo es.
 *
 * Tres cosas que esta pantalla hace de una forma concreta por un motivo:
 *
 *  1. **La ruta va sin `/v1`.** La URL base del cliente ya es `/v1`
 *     (`API_CONSTANTS.BASE_URL`), así que pedir `/v1/products` compondría
 *     `/v1/v1/products` y daría 404. Está comprobado contra el backend en marcha
 *     y contra el propio axios.
 *
 *  2. **El parámetro de búsqueda es `q`, no `search`.** El backend lee
 *     `queryParameters["q"]`; un `search=` se ignora en silencio y devolvería los
 *     1.398 productos como si no se hubiera filtrado, que es la peor clase de
 *     fallo: el usuario cree que buscó y está viendo todo.
 *
 *  3. **`active` sólo se envía cuando tiene valor.** Con `active=` (cadena
 *     vacía) el backend responde `400 INVALID_PARAMETER`: no lo interpreta como
 *     «sin filtro». Omitir el parámetro es la única forma de pedir todos.
 *
 * Y una cosa que NO hace: **no recorta `size`**. El contrato rechaza un tamaño
 * mayor de 200 en lugar de recortarlo, precisamente para que el cliente no crea
 * que tiene todos los datos cuando sólo tiene la primera página. Aquí se ofrece
 * un máximo de 200 y, si el servidor rechazara el tamaño, su mensaje se muestra
 * tal cual.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Campos exactos de `ProductResponse` del backend (CatalogDtos.kt). */
interface Producto {
  id: number;
  productCode: string;
  name: string;
  unitMeasure: string;
  active: boolean;
  /** Cantidad decimal: el contrato la emite como número, nunca como cadena. */
  stock: number;
}

/** Tamaños de página ofrecidos. 200 es el tope que acepta el backend. */
const TAMANOS = [25, 50, 100, 200];

/** Separador de miles español: «1.398», que es como se lee el total aquí. */
const MILES = new Intl.NumberFormat("es-ES");

/** Las cantidades son decimales; se muestran sin ceros de relleno. */
const CANTIDAD = new Intl.NumberFormat("es-ES", { maximumFractionDigits: 3 });

/**
 * Recorte de una línea con puntos suspensivos.
 *
 * En el catálogo real hay nombres de hasta 50 caracteres. Sin recortarlos, esa
 * columna empuja al resto de la tabla y la existencia —el dato por el que se
 * entra a esta pantalla— acaba fuera de la vista. El nombre completo no se
 * pierde: viaja en el atributo `title` de la celda.
 */
const RECORTE: CSSProperties = {
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
};

export default function ProductsPage() {
  const [productos, setProductos] = useState<Producto[]>([]);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Paginación y total del servidor. `totalItems` es lo que permite saber que
  // los 1.398 productos están ahí aunque sólo se vean 50.
  const [pagina, setPagina] = useState(0);
  const [tamano, setTamano] = useState(50);
  const [totalItems, setTotalItems] = useState(0);
  const [totalPaginas, setTotalPaginas] = useState(0);

  // `busqueda` es lo que se teclea y `termino` lo que se consulta: separarlos
  // evita una petición por pulsación y deja que la consulta se dispare al
  // enviar el formulario.
  const [busqueda, setBusqueda] = useState("");
  const [termino, setTermino] = useState("");
  const [activo, setActivo] = useState<"" | "true" | "false">("");

  const cargar = useCallback(async () => {
    setCargando(true);
    setError(null);
    try {
      const respuesta = await get<Page<Producto>>("/products", {
        params: {
          page: pagina,
          size: tamano,
          q: termino || undefined,
          active: activo === "" ? undefined : activo,
        },
      });
      setProductos(respuesta.items ?? []);
      setTotalItems(respuesta.totalItems ?? 0);
      setTotalPaginas(respuesta.totalPages ?? 0);
    } catch (e) {
      // El mensaje del backend nombra el parámetro que rechazó («'size' debe
      // estar entre 1 y 200»), que es justo lo que hay que corregir. Uno
      // genérico perdería esa información.
      setError(e instanceof Error ? e.message : "No se pudo cargar el catálogo.");
    } finally {
      setCargando(false);
    }
  }, [pagina, tamano, termino, activo]);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  /**
   * Aplica la búsqueda.
   *
   * Volver a la página 0 es obligatorio: si se busca estando en la página 20, el
   * resultado filtrado cabe en una página y el servidor devolvería una lista
   * vacía que parece «no hay resultados» cuando sí los hay.
   */
  function buscar(evento: FormEvent) {
    evento.preventDefault();
    setPagina(0);
    setTermino(busqueda.trim());
  }

  return (
    <div style={{ padding: 24 }}>
      <h1>Productos</h1>
      <p style={{ color: "#4A5A72" }}>
        Catálogo de productos con su existencia total. La existencia suma lo
        ubicado en estantes más lo que viaja en tránsito.
      </p>

      {/* Los controles se quedan montados durante la carga: si desaparecieran,
          el buscador perdería el foco en cada consulta. */}
      <form
        onSubmit={buscar}
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
            placeholder="Código o nombre"
            aria-label="Buscar producto"
            style={{ padding: 8, minWidth: 220 }}
          />
        </label>

        <button
          type="submit"
          style={{ background: "#16315F", color: "#fff", padding: "8px 16px", border: 0 }}
        >
          Buscar
        </button>

        {(termino !== "" || activo !== "") && (
          <button
            type="button"
            onClick={() => {
              setBusqueda("");
              setTermino("");
              setActivo("");
              setPagina(0);
            }}
            style={{ padding: "8px 16px" }}
          >
            Limpiar
          </button>
        )}

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
            <option value="">Todos</option>
            <option value="true">Sólo activos</option>
            <option value="false">Sólo inactivos</option>
          </select>
        </label>

        <label style={{ display: "flex", gap: 6, alignItems: "center" }}>
          Por página
          <select
            value={tamano}
            onChange={(e) => {
              setTamano(Number(e.target.value));
              setPagina(0);
            }}
            aria-label="Resultados por página"
            style={{ padding: 8 }}
          >
            {TAMANOS.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </label>
      </form>

      {/* El total es la prueba de que la carga funcionó: sin él, una página
          vacía es indistinguible de un catálogo vacío. `totalItems` cuenta las
          coincidencias del filtro, no el tamaño de la página. */}
      <p style={{ color: "#16315F", fontWeight: 600 }} aria-live="polite">
        {MILES.format(totalItems)} productos
        {totalPaginas > 1 ? ` · página ${pagina + 1} de ${totalPaginas}` : ""}
        {cargando ? " · cargando…" : ""}
      </p>

      {error && (
        <p role="alert" style={{ color: "#C0392B", background: "#FDECEA", padding: 12 }}>
          {error}{" "}
          <button type="button" onClick={() => void cargar()}>
            Reintentar
          </button>
        </p>
      )}

      {!error && !cargando && productos.length === 0 && (
        <p style={{ color: "#4A5A72", background: "#F2F6FC", padding: 16 }}>
          No hay productos que coincidan con la búsqueda.
        </p>
      )}

      {productos.length > 0 && (
        <table
          style={{ width: "100%", borderCollapse: "collapse", tableLayout: "fixed" }}
          aria-busy={cargando}
        >
          {/* El ancho fijo por columna es lo que hace que `text-overflow`
              recorte en lugar de ensanchar la tabla. */}
          <colgroup>
            <col style={{ width: "14%" }} />
            <col style={{ width: "46%" }} />
            <col style={{ width: "12%" }} />
            <col style={{ width: "14%" }} />
            <col style={{ width: "14%" }} />
          </colgroup>
          <thead>
            <tr style={{ background: "#E4EBF5", color: "#16315F", textAlign: "left" }}>
              <th style={{ padding: 10 }}>Código</th>
              <th style={{ padding: 10 }}>Nombre</th>
              <th style={{ padding: 10 }}>Unidad</th>
              <th style={{ padding: 10, textAlign: "right" }}>Existencia</th>
              <th style={{ padding: 10 }}>Estado</th>
            </tr>
          </thead>
          <tbody>
            {productos.map((p) => (
              <tr key={p.id} style={{ borderBottom: "1px solid #E4EBF5" }}>
                <td style={{ padding: 10, ...RECORTE }} title={p.productCode}>
                  {p.productCode}
                </td>
                {/* El `title` conserva el nombre que el CSS recorta. */}
                <td style={{ padding: 10, ...RECORTE }} title={p.name}>
                  {p.name}
                </td>
                <td style={{ padding: 10 }}>{p.unitMeasure}</td>
                <td style={{ padding: 10, textAlign: "right" }}>{CANTIDAD.format(p.stock)}</td>
                <td style={{ padding: 10 }}>
                  <span
                    style={{
                      background: p.active ? "#E8F5EE" : "#F2F6FC",
                      color: p.active ? "#2E7D5B" : "#4A5A72",
                      padding: "2px 8px",
                      whiteSpace: "nowrap",
                    }}
                  >
                    {p.active ? "Activo" : "Inactivo"}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
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
