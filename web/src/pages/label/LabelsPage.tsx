import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@shared/api";
import { usePermissionScope } from "@shared/auth";
import type { LabelFieldSchema, LabelJob, LabelTemplate } from "@shared/api";
import { PageShell } from "@shared/ui/page-shell";
import { PermissionScopeTag } from "@shared/ui/permission-scope-tag";
import { useCan } from "@entities/access";
import {
  createLabelTemplate,
  fetchLabelJobs,
  fetchLabelTemplate,
  fetchLabelTemplates,
  previewLabel,
  reprintLabelJob,
} from "@entities/label";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ETIQUETAS — pantalla REAL contra la API (§8)
 *
 * **Esta pantalla era una maqueta honesta y ya no lo es.** Decía «El endpoint no
 * existe. `GET /v1/labels` responde 404, comprobado con `curl`». Era verdad y
 * dejó de serlo; la maqueta siguió afirmándolo. Es la misma deuda que tenían
 * Traslados y Surtido.
 *
 * ── LA REGLA QUE ESTA PANTALLA EXISTE PARA HACER VISIBLE ───────────────────
 *
 * > **Una etiqueta no se marca impresa hasta que la impresora confirma.**
 *
 * El legacy la marcaba sin esperar la respuesta, así que **si la Zebra estaba
 * apagada la etiqueta se perdía para siempre** y nadie se enteraba. Aquí un
 * trabajo nace `PENDING` y sigue pendiente hasta que la impresora reporta por
 * `PATCH /v1/print-jobs/{id}`.
 *
 * Que la cola sea visible no es un adorno: **es lo único que permite descubrir una
 * etiqueta que no salió**. Si los trabajos pendientes no se vieran, el fallo
 * aparecería cuando faltara la etiqueta en el estante, con la mercancía ya
 * colocada.
 *
 * ── LO QUE LA PANTALLA NO HACE, Y POR QUÉ ─────────────────────────────────
 *
 * **No hay botón de «marcar como impresa».** Ningún endpoint de usuario marca un
 * trabajo como impreso: el sistema no se autocertifica. Solo la impresora, con su
 * credencial de servicio, puede hacerlo. Un botón así sería exactamente el defecto
 * del legacy, con una persona en lugar de un temporizador.
 *
 * **No se muestran los datos de prueba de una plantilla**: la vista previa pide
 * los datos reales. Inventar un `productCode` de ejemplo imprimiría —o enseñaría—
 * una etiqueta que no corresponde a ninguna mercancía.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** El estado de un trabajo con su texto. */
const ESTADO: Record<string, string> = {
  PENDING: "Pendiente",
  SENT: "Enviado",
  PRINTED: "Impreso",
  FAILED: "Falló",
};

function colorEstado(status: string): string {
  switch (status) {
    case "PRINTED":
      return "#2E7D5B";
    case "FAILED":
      return "#C0392B";
    case "PENDING":
    case "SENT":
      return "#F28C28";
    default:
      return "#16315F";
  }
}

/** El filtro de la cola. `FAILED` es el que hace útil la pantalla. */
const FILTROS = [
  { valor: "", texto: "Todas" },
  { valor: "PENDING", texto: "Pendientes" },
  { valor: "FAILED", texto: "Fallidas" },
  { valor: "PRINTED", texto: "Impresas" },
];

export default function LabelsPage() {
  const scope = usePermissionScope("label.print");

  const [plantillas, setPlantillas] = useState<LabelTemplate[] | null>(null);
  const [cola, setCola] = useState<LabelJob[] | null>(null);
  const [totalCola, setTotalCola] = useState(0);
  const [filtro, setFiltro] = useState("");

  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const [sinPermiso, setSinPermiso] = useState(false);

  /** La vista previa de una plantilla: el ZPL resuelto y su lienzo. */
  const [vistaPrevia, setVistaPrevia] = useState<{
    codigo: string;
    zpl: string;
    widthMm: number;
    heightMm: number;
  } | null>(null);
  const [datos, setDatos] = useState<Record<string, string>>({});
  const [plantillaAbierta, setPlantillaAbierta] = useState<number | null>(null);

  const [reimprimiendo, setReimprimiendo] = useState<number | null>(null);
  const [motivo, setMotivo] = useState("");

  // ── El alta de plantillas ──────────────────────────────────────────────
  //
  // `label.template_edit` es un permiso **sensible y solo de alcance `ALL`** según
  // el catálogo, así que el botón no se pinta a quien no lo tiene. Una plantilla
  // mal guardada se imprime en cada etiqueta del almacén: no es un ajuste cosmético.
  const puedeEditar = useCan("label.template_edit");
  const [altaAbierta, setAltaAbierta] = useState(false);
  const [codigo, setCodigo] = useState("");
  const [nombre, setNombre] = useState("");
  const [zpl, setZpl] = useState("^XA\n^FD{{productCode}}^FS\n^XZ");
  const [camposTexto, setCamposTexto] = useState("productCode:Código:si");
  const [creando, setCreando] = useState(false);

  const cargar = useCallback(async () => {
    setCargando(true);
    setError(null);
    setSinPermiso(false);
    try {
      const [lista, pagina] = await Promise.all([
        fetchLabelTemplates(),
        fetchLabelJobs(filtro ? { status: filtro } : {}),
      ]);
      setPlantillas(lista);
      setCola(pagina.items);
      setTotalCola(pagina.totalItems);
    } catch (e) {
      // Se descarta lo anterior: tras un fallo, dejar la cola vieja haría creer
      // que no hay trabajos pendientes cuando puede haberlos.
      setPlantillas(null);
      setCola(null);
      setSinPermiso(e instanceof ApiError && e.status === 403);
      setError(e instanceof Error ? e.message : "No se pudieron cargar las etiquetas.");
    } finally {
      setCargando(false);
    }
  }, [filtro]);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  /** Abre una plantilla para previsualizar, y pide su detalle (que sí trae `zpl`). */
  const abrirPlantilla = useCallback(async (id: number) => {
    setError(null);
    setVistaPrevia(null);
    setPlantillaAbierta(id);
    setDatos({});
    try {
      // El listado NO trae `zpl` (es grande): hay que pedir el detalle. Sin esto,
      // la previsualización se quedaría sin plantilla y sin saber por qué.
      await fetchLabelTemplate(id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "No se pudo abrir la plantilla.");
      setPlantillaAbierta(null);
    }
  }, []);

  /** Pide el ZPL resuelto. `422 MISSING_LABEL_FIELD` si falta un dato. */
  const previsualizar = useCallback(async (plantilla: LabelTemplate) => {
    setError(null);
    setVistaPrevia(null);
    try {
      const resultado = await previewLabel({ templateId: plantilla.id, data: datos });
      setVistaPrevia({
        codigo: plantilla.code,
        zpl: resultado.zpl,
        widthMm: resultado.preview.widthMm,
        heightMm: resultado.preview.heightMm,
      });
    } catch (e) {
      // **El caso que la pantalla tiene que explicar bien.** La API rechaza una
      // etiqueta a la que le falta un dato en vez de imprimir el marcador literal
      // o un hueco. El mensaje nombra los campos que faltan, así que se muestra tal
      // cual: es lo que el usuario necesita para rellenarlos.
      if (e instanceof ApiError && e.code === "MISSING_LABEL_FIELD") {
        setError(
          `Faltan datos para esta etiqueta: ${e.message}. No se imprime una etiqueta ` +
            `incompleta: un hueco donde va el código no se distingue de una plantilla mal ` +
            `hecha, y esa etiqueta acaba pegada en un estante.`,
        );
      } else {
        setError(e instanceof Error ? e.message : "No se pudo generar la vista previa.");
      }
    }
  }, [datos]);

  /** Reimprime: exige motivo y crea un trabajo NUEVO. */
  const reimprimir = useCallback(
    async (trabajo: LabelJob) => {
      const texto = motivo.trim();
      if (texto.length === 0) {
        setError(
          "Escribe el motivo de la reimpresión. Sin él no se sabrá por qué se gastó una " +
            "segunda etiqueta.",
        );
        return;
      }

      setReimprimiendo(trabajo.id);
      setError(null);
      setAviso(null);
      try {
        const nuevo = await reprintLabelJob(trabajo.id, texto);
        setAviso(
          `Reimpresión encolada como trabajo #${nuevo.id}, enlazado al original #${trabajo.id}. ` +
            `Queda PENDING hasta que la impresora confirme.`,
        );
        setMotivo("");
        await cargar();
      } catch (e) {
        setError(e instanceof Error ? e.message : "No se pudo reimprimir.");
        await cargar();
      } finally {
        setReimprimiendo(null);
      }
    },
    [motivo, cargar],
  );

  const crearPlantilla = useCallback(async () => {
    /** `campo:Etiqueta:si|no` por línea → el `fieldSchema` que espera la API. */
    const buildSchema = (): LabelFieldSchema => {
      const esquema: LabelFieldSchema = {};
    for (const fila of camposTexto.split("\n")) {
      const partes = fila.split(":");
      const clave = (partes[0] ?? "").trim();
      if (clave.length === 0) continue;
      esquema[clave] = {
        type: "string" as const,
        label: (partes[1] ?? clave).trim() || clave,
        required: (partes[2] ?? "").trim().toLowerCase() === "si",
      };
    }
      return esquema;
    };

    if (codigo.trim().length === 0 || nombre.trim().length === 0) {
      setError("El código y el nombre de la plantilla son obligatorios.");
      return;
    }
    // **Se valida el ZPL ANTES de enviarlo**, con la misma regla que el servidor:
    // tiene que llevar `^XA` y `^XZ`. Se valida al guardar y no al imprimir porque
    // una plantilla sin cierre no da error en la impresora — deja la etiqueta en
    // blanco o el trabajo en la cola, y eso se descubre gastando papel.
    if (!zpl.includes("^XA") || !zpl.includes("^XZ")) {
      setError(
        "El ZPL tiene que llevar '^XA' al principio y '^XZ' al final. Sin el cierre la " +
          "impresora no da error: deja la etiqueta en blanco o el trabajo en la cola.",
      );
      return;
    }

    setCreando(true);
    setError(null);
    setAviso(null);
    try {
      const creada = await createLabelTemplate({
        code: codigo.trim(),
        name: nombre.trim(),
        zpl,
        fieldSchema: buildSchema(),
      });
      setAviso(`Plantilla '${creada.code}' creada. Ya se puede previsualizar.`);
      setAltaAbierta(false);
      setCodigo("");
      setNombre("");
      await cargar();
    } catch (e) {
      setError(e instanceof Error ? e.message : "No se pudo crear la plantilla.");
    } finally {
      setCreando(false);
    }
  }, [codigo, nombre, zpl, camposTexto, cargar]);

  const camposDe = (plantilla: LabelTemplate): Array<{ nombre: string; label: string; required: boolean }> =>
    Object.entries(plantilla.fieldSchema ?? {}).map(([nombre, spec]) => ({
      nombre,
      label: spec?.label ?? nombre,
      required: spec?.required ?? false,
    }));

  return (
    <PageShell
      title="Etiquetas"
      description="Plantillas ZPL, vista previa y cola de impresión."
      breadcrumbLabel="Etiquetas"
    >
      <p style={{ display: "flex", alignItems: "center", gap: 8, color: "#4A5A72" }}>
        Permiso de la pantalla:
        <PermissionScopeTag permission="label.print" scope={scope} />
      </p>

      {cargando && <p style={{ color: "#4A5A72" }}>Cargando etiquetas…</p>}

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
              Esta pantalla exige el permiso <code>label.print</code>. Pídeselo a un
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

      {!cargando && cola && (
        <>
          {/*
            ── LA COLA, ARRIBA ─────────────────────────────────────────────
            Es lo único que permite descubrir una etiqueta que no salió. Si
            estuviera debajo de las plantillas, el fallo aparecería cuando faltara
            la etiqueta en el estante — con la mercancía ya colocada.
          */}
          <section
            role="status"
            style={{
              background: "#F2F6FC",
              borderLeft: "6px solid #16315F",
              padding: 20,
              margin: "16px 0",
            }}
          >
            <h2 style={{ margin: 0, fontSize: 19, color: "#16315F" }}>Cola de impresión</h2>
            <p style={{ margin: "8px 0 0", color: "#4A5A72" }}>
              Un trabajo queda <strong>Pendiente</strong> hasta que la impresora confirma que
              salió. <strong>Esta pantalla no puede marcarlo como impreso</strong>: solo la
              impresora puede, con su credencial de servicio. Si un trabajo sigue pendiente, la
              etiqueta <strong>no ha salido</strong>.
            </p>
          </section>

          <div style={{ display: "flex", alignItems: "center", gap: 8, margin: "16px 0" }}>
            <label htmlFor="filtro-cola">Estado:</label>
            <select
              id="filtro-cola"
              value={filtro}
              onChange={(e) => setFiltro(e.target.value)}
              disabled={cargando}
            >
              {FILTROS.map((f) => (
                <option key={f.valor} value={f.valor}>
                  {f.texto}
                </option>
              ))}
            </select>
            <span style={{ color: "#4A5A72" }}>{totalCola} trabajo(s)</span>
          </div>

          {cola.length === 0 ? (
            <p style={{ color: "#4A5A72" }}>
              {filtro === "FAILED"
                ? "Ninguna etiqueta ha fallado. Es el estado que se quiere ver aquí."
                : "No hay trabajos de impresión todavía."}
            </p>
          ) : (
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
              <thead>
                <tr style={{ background: "#E4EBF5", color: "#16315F", textAlign: "left" }}>
                  <th style={{ padding: 10 }}>Trabajo</th>
                  <th style={{ padding: 10 }}>Plantilla</th>
                  <th style={{ padding: 10, textAlign: "right" }}>Copias</th>
                  <th style={{ padding: 10 }}>Estado</th>
                  <th style={{ padding: 10, textAlign: "right" }}>Intentos</th>
                  <th style={{ padding: 10 }}>Error</th>
                  <th style={{ padding: 10 }}>Reimprimir</th>
                </tr>
              </thead>
              <tbody>
                {cola.map((t) => (
                  <tr key={t.id} style={{ borderBottom: "1px solid #E4EBF5" }}>
                    <td style={{ padding: 10 }}>#{t.id}</td>
                    <td style={{ padding: 10 }}>{t.templateCode}</td>
                    <td style={{ padding: 10, textAlign: "right" }}>{t.copies}</td>
                    <td style={{ padding: 10, color: colorEstado(t.status), fontWeight: 600 }}>
                      {ESTADO[t.status] ?? t.status}
                    </td>
                    <td style={{ padding: 10, textAlign: "right" }}>{t.attempts}</td>
                    <td style={{ padding: 10, color: "#C0392B" }}>{t.lastError ?? "—"}</td>
                    <td style={{ padding: 10 }}>
                      <input
                        type="text"
                        aria-label={`Motivo de la reimpresión del trabajo ${t.id}`}
                        placeholder="Motivo (obligatorio)"
                        value={reimprimiendo === t.id ? motivo : ""}
                        onChange={(e) => {
                          setReimprimiendo(t.id);
                          setMotivo(e.target.value);
                        }}
                        style={{ width: 170 }}
                      />
                      <button
                        type="button"
                        disabled={reimprimiendo === t.id && motivo.trim().length === 0}
                        onClick={() => void reimprimir(t)}
                      >
                        Reimprimir
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}

      {!cargando && plantillas && (
        <>
          <div style={{ display: "flex", alignItems: "center", gap: 12, marginTop: 32 }}>
            <h2 style={{ fontSize: 17, color: "#16315F", margin: 0 }}>
              Plantillas ({plantillas.length})
            </h2>
            {puedeEditar && (
              <button type="button" onClick={() => setAltaAbierta((v) => !v)}>
                {altaAbierta ? "Cancelar el alta" : "Nueva plantilla"}
              </button>
            )}
          </div>

          {puedeEditar && altaAbierta && (
            <section
              style={{
                background: "#F2F6FC",
                borderLeft: "6px solid #16315F",
                padding: 20,
                margin: "16px 0",
              }}
            >
              <h3 style={{ margin: 0, fontSize: 16, color: "#16315F" }}>Nueva plantilla</h3>
              <p style={{ margin: "8px 0", color: "#4A5A72" }}>
                Los campos se declaran como <code>nombre:Etiqueta:si|no</code>, uno por línea.
                El <strong>nombre</strong> es el marcador que se sustituye en el ZPL
                (<code>{"{{productCode}}"}</code>), y <code>si</code> marca el campo como
                obligatorio.
              </p>

              <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
                <label style={{ display: "flex", flexDirection: "column" }}>
                  <span style={{ fontSize: 13, color: "#4A5A72" }}>Código</span>
                  <input
                    type="text"
                    aria-label="Código de la plantilla"
                    value={codigo}
                    onChange={(e) => setCodigo(e.target.value)}
                    placeholder="receipt"
                  />
                </label>
                <label style={{ display: "flex", flexDirection: "column", flexGrow: 1 }}>
                  <span style={{ fontSize: 13, color: "#4A5A72" }}>Nombre</span>
                  <input
                    type="text"
                    aria-label="Nombre de la plantilla"
                    value={nombre}
                    onChange={(e) => setNombre(e.target.value)}
                    placeholder="Recibo de mercancía"
                  />
                </label>
              </div>

              <label style={{ display: "flex", flexDirection: "column", marginTop: 12 }}>
                <span style={{ fontSize: 13, color: "#4A5A72" }}>ZPL</span>
                <textarea
                  aria-label="ZPL de la plantilla"
                  rows={4}
                  value={zpl}
                  onChange={(e) => setZpl(e.target.value)}
                  style={{ fontFamily: "monospace" }}
                />
              </label>

              <label style={{ display: "flex", flexDirection: "column", marginTop: 12 }}>
                <span style={{ fontSize: 13, color: "#4A5A72" }}>Campos</span>
                <textarea
                  aria-label="Campos de la plantilla"
                  rows={3}
                  value={camposTexto}
                  onChange={(e) => setCamposTexto(e.target.value)}
                  style={{ fontFamily: "monospace" }}
                />
              </label>

              <button
                type="button"
                style={{ marginTop: 12 }}
                disabled={creando}
                onClick={() => void crearPlantilla()}
              >
                {creando ? "Creando…" : "Crear plantilla"}
              </button>
            </section>
          )}

          {plantillas.length === 0 ? (
            <p style={{ color: "#4A5A72" }}>
              No hay ninguna plantilla. Se crean desde la API con su ZPL —que tiene que llevar
              <code> ^XA</code> y <code>^XZ</code>— y los campos que espera.
            </p>
          ) : (
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
              <thead>
                <tr style={{ background: "#E4EBF5", color: "#16315F", textAlign: "left" }}>
                  <th style={{ padding: 10 }}>Código</th>
                  <th style={{ padding: 10 }}>Nombre</th>
                  <th style={{ padding: 10 }}>Tamaño</th>
                  <th style={{ padding: 10 }}>Campos</th>
                  <th style={{ padding: 10 }}>Activa</th>
                  <th style={{ padding: 10 }}>Vista previa</th>
                </tr>
              </thead>
              <tbody>
                {plantillas.map((p) => (
                  <tr key={p.id} style={{ borderBottom: "1px solid #E4EBF5" }}>
                    <td style={{ padding: 10 }}>{p.code}</td>
                    <td style={{ padding: 10 }}>{p.name}</td>
                    <td style={{ padding: 10 }}>{p.size ?? "4x6"}</td>
                    <td style={{ padding: 10 }}>{camposDe(p).length}</td>
                    <td style={{ padding: 10 }}>{p.isActive ? "Sí" : "No"}</td>
                    <td style={{ padding: 10 }}>
                      <button type="button" onClick={() => void abrirPlantilla(p.id)}>
                        Ver
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}

      {/*
        ── LA VISTA PREVIA ──────────────────────────────────────────────────
        Los campos se piden uno a uno según el `fieldSchema` de la plantilla, y
        NO se rellenan con datos de ejemplo: inventar un código de producto
        enseñaría una etiqueta que no corresponde a ninguna mercancía, y alguien
        podría imprimirla.
      */}
      {!cargando && plantillaAbierta != null && plantillas && (
        <section
          style={{
            background: "#F2F6FC",
            borderLeft: "6px solid #16315F",
            padding: 20,
            margin: "24px 0",
          }}
        >
          {(() => {
            const plantilla = plantillas.find((p) => p.id === plantillaAbierta);
            if (!plantilla) return null;
            const campos = camposDe(plantilla);
            return (
              <>
                <h2 style={{ margin: 0, fontSize: 17, color: "#16315F" }}>
                  Vista previa de «{plantilla.name}»
                </h2>
                <p style={{ margin: "8px 0", color: "#4A5A72" }}>
                  Escribe los datos reales de la etiqueta. No se imprimirá nada: esto solo
                  resuelve el ZPL.
                </p>

                {campos.length === 0 ? (
                  <p style={{ color: "#4A5A72" }}>
                    Esta plantilla no declara campos: el ZPL es fijo.
                  </p>
                ) : (
                  <div style={{ display: "flex", gap: 12, flexWrap: "wrap", margin: "12px 0" }}>
                    {campos.map((campo) => (
                      <label key={campo.nombre} style={{ display: "flex", flexDirection: "column" }}>
                        <span style={{ fontSize: 13, color: "#4A5A72" }}>
                          {campo.label}
                          {campo.required ? " *" : ""}
                        </span>
                        <input
                          type="text"
                          value={datos[campo.nombre] ?? ""}
                          onChange={(e) =>
                            setDatos((previo) => ({ ...previo, [campo.nombre]: e.target.value }))
                          }
                        />
                      </label>
                    ))}
                  </div>
                )}

                <div style={{ display: "flex", gap: 12, marginTop: 8 }}>
                  <button type="button" onClick={() => void previsualizar(plantilla)}>
                    Generar ZPL
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setPlantillaAbierta(null);
                      setVistaPrevia(null);
                      setDatos({});
                    }}
                  >
                    Cerrar
                  </button>
                </div>

                {vistaPrevia && vistaPrevia.codigo === plantilla.code && (
                  <div style={{ marginTop: 16 }}>
                    <p style={{ color: "#4A5A72", margin: "0 0 8px" }}>
                      Lienzo: {vistaPrevia.widthMm} × {vistaPrevia.heightMm} mm. El tamaño lo
                      calcula el <strong>servidor</strong>: si lo hiciera cada cliente, la vista
                      previa del móvil y la de la web mostrarían tamaños distintos para la misma
                      plantilla.
                    </p>
                    <pre
                      aria-label="ZPL resuelto"
                      style={{
                        background: "#0F1B2D",
                        color: "#D7E3F4",
                        padding: 12,
                        overflowX: "auto",
                        fontSize: 12,
                      }}
                    >
                      {vistaPrevia.zpl}
                    </pre>
                  </div>
                )}
              </>
            );
          })()}
        </section>
      )}
    </PageShell>
  );
}
