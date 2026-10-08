import { useState, type FormEvent } from "react";
import { post } from "@shared/api";
// `newId` se reutiliza en lugar de `crypto.randomUUID` a secas porque la web
// interna entra por `http://IP` y ahí `randomUUID` no existe (solo en contexto
// seguro); el helper ya contempla ese caso.
import { newId } from "@shared/api/lib/http";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * AJUSTES DE INVENTARIO — ALTA (`POST /inventory/adjustments`)
 *
 * **Esta pantalla NO es la bandeja de pendientes, y no lo es a propósito.**
 * El endpoint que la haría posible no existe: no hay
 * `GET /inventory/adjustments` ni `/adjustments/pending`. Inventar la ruta
 * daría un `404` y, peor, una bandeja que siempre se ve vacía: un vacío que
 * miente es peor que una ausencia, porque se lee como «no hay nada pendiente».
 * Lo que el backend sí expone —y es lo que se hace aquí— es el alta.
 *
 * Dos cosas que la pantalla dice en voz alta porque el contrato las hace fáciles
 * de malinterpretar:
 *
 *  1. **Un ajuste que AUMENTA el inventario exige aprobación de otra persona**
 *     (decisión R-74). Si no se manda aprobador, el servidor responde
 *     `PENDING_APPROVAL` y **no escribe nada**: el inventario no se mueve. Hay
 *     que decirlo, porque «creado» y «aplicado» no son lo mismo.
 *  2. **Robo y «Otro» exigen explicación escrita** (mínimo 10 caracteres,
 *     `AdjustmentRequest.MIN_NOTE_LENGTH`). El motivo se pide solo en esos dos
 *     casos, y se explica por qué: son los dos que tienen consecuencias legales
 *     o que revelan que falta un motivo en la taxonomía.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Motivos, copiados del enum `AdjustmentReason`
 * (`stock-domain/.../inventory/Adjustment.kt`). **No se inventan**: los ocho y
 * sus nombres son los del dominio. Añadir uno aquí, o traducir un nombre a
 * otro, produce un `400 INVALID_PARAMETER` o —peor— motivos que ningún reporte
 * sabe sumar.
 *
 * `exigeNota` refleja `AdjustmentReason.requiresNote` del dominio.
 */
const MOTIVOS = [
  { valor: "SHORTAGE", etiqueta: "Faltante", exigeNota: false },
  { valor: "DAMAGE", etiqueta: "Daño", exigeNota: false },
  { valor: "THEFT", etiqueta: "Robo", exigeNota: true },
  { valor: "SUPPLIER_ERROR", etiqueta: "Error del proveedor", exigeNota: false },
  { valor: "COUNT_ERROR", etiqueta: "Error de conteo", exigeNota: false },
  { valor: "RETURN_TO_SUPPLIER", etiqueta: "Devolución a proveedor", exigeNota: false },
  { valor: "EXPIRED", etiqueta: "Caducado", exigeNota: false },
  { valor: "OTHER", etiqueta: "Otro", exigeNota: true },
];

/**
 * Orígenes, copiados del enum `AdjustmentOrigin`. Son los cuatro flujos que el
 * legacy tenía como caminos separados y que aquí son un campo del mismo
 * movimiento.
 */
const ORIGENES = [
  { valor: "INVOICE_DISCREPANCY", etiqueta: "Discrepancia de factura" },
  { valor: "FULFILLMENT_SHORTAGE", etiqueta: "Faltante en surtido" },
  { valor: "MANUAL_COUNT", etiqueta: "Conteo físico" },
  { valor: "DAMAGE_REPORT", etiqueta: "Reporte de daño" },
];

/**
 * Direcciones, con los nombres del dominio (`AdjustmentDirection`).
 *
 * Ojo: **no** son los del tipo `AdjustmentDirection` de `@shared/api`, que usa
 * `"IN"`/`"OUT"`. Esta ruta espera `INCREASE`/`DECREASE` y con `"IN"` responde
 * `400`. Se declara aquí la que el endpoint acepta de verdad.
 */
const DIRECCIONES = [
  { valor: "DECREASE", etiqueta: "Disminuir existencia (merma)" },
  { valor: "INCREASE", etiqueta: "Aumentar existencia" },
];

/** Mínimo de caracteres de la nota, igual que `AdjustmentRequest.MIN_NOTE_LENGTH`. */
const LONGITUD_MINIMA_NOTA = 10;

/**
 * Respuesta de `POST /inventory/adjustments` (`MovimientoResponse`).
 *
 * Con `status: "PENDING_APPROVAL"` el `kardexIds` viene vacío: **no se escribió
 * nada**. `total` es una cadena decimal de 3 posiciones, como el resto de
 * cantidades del contrato.
 */
interface ResultadoAjuste {
  kardexIds: number[];
  status: string;
  total: string;
  message: string | null;
}

export default function AdjustmentApprovalPage() {
  const [producto, setProducto] = useState("");
  const [ubicacion, setUbicacion] = useState("");
  const [cantidadTexto, setCantidadTexto] = useState("");
  const [direccion, setDireccion] = useState("DECREASE");
  const [motivo, setMotivo] = useState(MOTIVOS[0].valor);
  const [origen, setOrigen] = useState(ORIGENES[0].valor);
  const [nota, setNota] = useState("");

  const [enviando, setEnviando] = useState(false);
  const [aviso, setAviso] = useState<string | null>(null);
  const [resultado, setResultado] = useState<ResultadoAjuste | null>(null);

  const motivoElegido = MOTIVOS.find((m) => m.valor === motivo) ?? MOTIVOS[0];
  const exigeNota = motivoElegido.exigeNota;
  const esAumento = direccion === "INCREASE";

  async function enviar(evento: FormEvent) {
    evento.preventDefault();
    setAviso(null);
    setResultado(null);

    // Se comprueba lo que el dominio va a comprobar, solo para no gastar un
    // viaje de ida y vuelta en algo que ya se sabe que falla. La regla sigue
    // siendo del servidor: la nota mínima y el motivo son suyos.
    if (exigeNota && nota.trim().length < LONGITUD_MINIMA_NOTA) {
      setAviso(
        `El motivo «${motivoElegido.etiqueta}» exige una explicación de al menos ` +
          `${LONGITUD_MINIMA_NOTA} caracteres. Sin ella el ajuste no se puede auditar ` +
          `dentro de un año.`
      );
      return;
    }

    // La cantidad tiene que ser positiva y la dirección dice si suma o resta.
    // Una cantidad negativa con dirección DECREASE sería una suma disfrazada.
    const cantidad = Number(cantidadTexto);
    if (!Number.isFinite(cantidad) || cantidad <= 0) {
      setAviso("La cantidad debe ser mayor que cero. La dirección ya indica si suma o resta.");
      return;
    }

    setEnviando(true);
    try {
      const respuesta = await post<ResultadoAjuste>(
        "/inventory/adjustments",
        {
          productCode: producto.trim(),
          locationCode: ubicacion.trim(),
          quantity: cantidad,
          direction: direccion,
          reason: motivo,
          origin: origen,
          // La nota va solo cuando el motivo la exige: en los demás casos no se
          // manda, para que no quede una explicación huérfana en el kardex.
          note: exigeNota ? nota.trim() : null,
          // Sin aprobador, un aumento queda pendiente y NO se aplica. Es el
          // camino que se documenta en pantalla en lugar de elegir uno en
          // silencio por el usuario.
          approverId: null,
        },
        // §1.5: una operación que mueve inventario lleva clave de idempotencia,
        // para que un reintento no duplique el movimiento. Se genera una nueva
        // por envío deliberado: cada clic es una intención distinta.
        { idempotencyKey: newId() }
      );
      setResultado(respuesta);
    } catch (e) {
      // El mensaje del backend se muestra tal cual: nombra la ubicación o el
      // producto que falló, y un texto genérico perdería ese dato.
      setAviso(e instanceof Error ? e.message : "No se pudo registrar el ajuste.");
    } finally {
      setEnviando(false);
    }
  }

  return (
    <div style={{ padding: 24 }}>
      <h1>Ajustes de inventario</h1>
      <p style={{ color: "#4A5A72" }}>
        Alta de un ajuste por la regla única: siempre con motivo, y con aprobación de otra
        persona cuando <strong>aumenta</strong> el inventario.
      </p>

      {/*
        Se explica la ausencia en vez de disimularla: la bandeja de pendientes no
        se puede consultar porque el backend no expone el listado.
      */}
      <p style={{ background: "#F2F6FC", color: "#4A5A72", padding: 12, fontSize: 13 }}>
        Esta pantalla registra ajustes. La bandeja de aprobación no se puede mostrar
        todavía: el backend solo expone el alta, no un listado de ajustes pendientes. Se
        prefiere decir esto a inventar una ruta que devolvería un 404 y una bandeja
        siempre vacía.
      </p>

      {aviso && (
        <p role="alert" style={{ color: "#C0392B", background: "#FDECEA", padding: 12 }}>
          {aviso}
        </p>
      )}

      {resultado &&
        (resultado.status === "PENDING_APPROVAL" ? (
          // El caso que más se malinterpreta: llegó un 202 y el ajuste NO se
          // aplicó. Si no se dice, alguien asume que el inventario ya cambió.
          <div
            role="status"
            style={{
              background: "#FFF4E5",
              borderLeft: "4px solid #F28C28",
              padding: 16,
              marginBottom: 16,
            }}
          >
            <strong>El ajuste no se aplicó: necesita la aprobación de otra persona.</strong>
            <p style={{ marginBottom: 4 }}>
              {resultado.message ??
                "Un ajuste que aumenta el inventario exige una segunda firma."}
            </p>
            <p style={{ margin: 0, color: "#4A5A72" }}>
              No se escribió ningún movimiento en el kardex
              {resultado.kardexIds.length === 0 ? " (la lista de movimientos vino vacía)" : ""},
              así que <strong>el inventario no cambió</strong>.
            </p>
          </div>
        ) : (
          <div
            role="status"
            style={{
              background: "#E8F5EE",
              borderLeft: "4px solid #2E7D5B",
              padding: 16,
              marginBottom: 16,
            }}
          >
            <strong style={{ color: "#2E7D5B" }}>Ajuste aplicado.</strong>
            <p style={{ marginBottom: 4 }}>
              Movimiento(s) escrito(s) en el kardex: {resultado.kardexIds.join(", ") || "—"}.
            </p>
            <p style={{ margin: 0, color: "#4A5A72" }}>
              Nuevo total del producto: <strong>{resultado.total}</strong>
            </p>
          </div>
        ))}

      <form
        onSubmit={(e) => void enviar(e)}
        style={{
          display: "grid",
          gap: 12,
          maxWidth: 560,
          background: "#F2F6FC",
          padding: 16,
        }}
      >
        <label style={{ display: "grid", gap: 4 }}>
          <span style={{ fontSize: 13, color: "#4A5A72" }}>Código de producto</span>
          <input
            aria-label="Código de producto"
            required
            value={producto}
            onChange={(e) => setProducto(e.target.value)}
            placeholder="ABRAA1B"
            style={{ padding: 6 }}
          />
        </label>

        <label style={{ display: "grid", gap: 4 }}>
          <span style={{ fontSize: 13, color: "#4A5A72" }}>Código de ubicación</span>
          <input
            aria-label="Código de ubicación"
            required
            value={ubicacion}
            onChange={(e) => setUbicacion(e.target.value)}
            style={{ padding: 6 }}
          />
          {/*
            La validación del código es del dominio: mayúsculas, alfanumérico
            español (incluye Ñ) y 24 caracteres como máximo. Se enuncia porque el
            error del servidor no la explica; **no** se corrige el valor en
            silencio, porque un código transformado ocultaría que no coincidía.
          */}
          <span style={{ fontSize: 12, color: "#4A5A72" }}>
            Código interno de la ubicación: mayúsculas y sin separadores (máximo 24
            caracteres).
          </span>
        </label>

        <label style={{ display: "grid", gap: 4 }}>
          <span style={{ fontSize: 13, color: "#4A5A72" }}>Cantidad</span>
          <input
            aria-label="Cantidad"
            required
            type="number"
            min="0.001"
            step="0.001"
            value={cantidadTexto}
            onChange={(e) => setCantidadTexto(e.target.value)}
            style={{ padding: 6 }}
          />
          <span style={{ fontSize: 12, color: "#4A5A72" }}>
            Positiva: la dirección ya indica si suma o resta.
          </span>
        </label>

        <label style={{ display: "grid", gap: 4 }}>
          <span style={{ fontSize: 13, color: "#4A5A72" }}>Dirección</span>
          <select
            aria-label="Dirección"
            value={direccion}
            onChange={(e) => setDireccion(e.target.value)}
            style={{ padding: 6 }}
          >
            {DIRECCIONES.map((d) => (
              <option key={d.valor} value={d.valor}>
                {d.etiqueta}
              </option>
            ))}
          </select>
        </label>

        {esAumento && (
          // Aviso previo, no posterior: si se dijera solo al enviar, el usuario
          // ya habría dado por hecho que el aumento se aplicó.
          <p
            style={{
              margin: 0,
              background: "#FFF4E5",
              borderLeft: "4px solid #F28C28",
              padding: 10,
              fontSize: 13,
            }}
          >
            Un ajuste que <strong>aumenta</strong> el inventario necesita la aprobación de
            otra persona. Sin aprobador, quedará <strong>pendiente y no se aplicará</strong>:
            el inventario no cambia en el momento.
          </p>
        )}

        <label style={{ display: "grid", gap: 4 }}>
          <span style={{ fontSize: 13, color: "#4A5A72" }}>Motivo</span>
          <select
            aria-label="Motivo"
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
        </label>

        {exigeNota && (
          // Solo se pide en Robo y Otro, que son los dos motivos del dominio con
          // `requiresNote`: uno tiene implicaciones legales y el otro revela que
          // falta un motivo en la lista.
          <label style={{ display: "grid", gap: 4 }}>
            <span style={{ fontSize: 13, color: "#4A5A72" }}>
              Explicación (obligatoria para «{motivoElegido.etiqueta}»)
            </span>
            <textarea
              aria-label="Explicación"
              required
              rows={3}
              value={nota}
              onChange={(e) => setNota(e.target.value)}
              placeholder={`Al menos ${LONGITUD_MINIMA_NOTA} caracteres`}
              style={{ padding: 6 }}
            />
            <span style={{ fontSize: 12, color: "#4A5A72" }}>
              Este motivo exige explicación escrita: dentro de un año es lo único que
              permite reconstruir qué pasó.
            </span>
          </label>
        )}

        <label style={{ display: "grid", gap: 4 }}>
          <span style={{ fontSize: 13, color: "#4A5A72" }}>Origen</span>
          <select
            aria-label="Origen"
            value={origen}
            onChange={(e) => setOrigen(e.target.value)}
            style={{ padding: 6 }}
          >
            {ORIGENES.map((o) => (
              <option key={o.valor} value={o.valor}>
                {o.etiqueta}
              </option>
            ))}
          </select>
        </label>

        <button
          type="submit"
          disabled={enviando}
          style={{
            background: "#16315F",
            color: "#fff",
            padding: "10px 18px",
            border: "none",
            cursor: enviando ? "default" : "pointer",
            fontWeight: 600,
            justifySelf: "start",
          }}
        >
          {enviando ? "Registrando…" : "Registrar ajuste"}
        </button>
      </form>
    </div>
  );
}
