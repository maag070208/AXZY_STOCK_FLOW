import { ITAlert, ITButton, ITCheckbox, ITDialog, ITFlex, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState } from "react";
import { catalogApi, type ImportReport } from "@entities/catalog";
import { ApiError } from "@shared/api/types";
import { formatQuantity } from "@shared/lib/format";
import { Loader } from "@shared/ui/loader/Loader";

interface Props {
  open: boolean;
  onClose: () => void;
  /** Se llama solo cuando se escribió de verdad, para recargar la tabla. */
  onImported: () => void;
}

/**
 * La importación del archivo del catálogo.
 *
 * **Simula por defecto.** El olvido inverso —escribir 1.398 productos cuando se
 * quería probar— no tiene vuelta atrás; el contrario se resuelve volviendo a
 * pulsar. Es la misma decisión que toma la API con `dryRun` (docs/api/catalogo.md).
 *
 * Las filas rechazadas se listan **con su número de fila**: sin él, un archivo con
 * un error es irresoluble.
 */
export default function ImportProductsDialog({ open, onClose, onImported }: Props) {
  const [file, setFile] = useState<File | null>(null);
  const [dryRun, setDryRun] = useState(true);
  const [report, setReport] = useState<ImportReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [rejected, setRejected] = useState<[string, string][]>([]);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setFile(null);
    setReport(null);
    setError(null);
    setRejected([]);
    setDryRun(true);
  }, [open]);

  const run = async () => {
    if (!file) return;
    setBusy(true);
    setError(null);
    setRejected([]);

    try {
      const result = await catalogApi.importProducts(file, dryRun);
      setReport(result);
      if (!result.dryRun) onImported();
    } catch (caught) {
      const apiError = caught as ApiError;
      setError(apiError.message);
      // `IMPORT_INVALID_ROWS` trae un detalle por fila (`fila_3`): es lo que
      // permite corregir el archivo sin adivinar cuál estaba mal.
      setRejected(Object.entries(apiError.details ?? {}));
      setReport(null);
    } finally {
      setBusy(false);
    }
  };

  return (
    <ITDialog isOpen={open} onClose={onClose} title="Importar catálogo" className="w-full max-w-2xl">
      <ITFlex direction="column" gap={4}>
        <ITText muted className="text-xs">
          Archivo CSV o TSV con las columnas: identificador externo, código, nombre, unidad de medida y
          existencias. Una fila inválida rechaza el archivo entero y no se escribe nada.
        </ITText>

        <input
          type="file"
          accept=".csv,.tsv,.txt"
          aria-label="Archivo del catálogo"
          onChange={(event) => setFile(event.target.files?.[0] ?? null)}
          className="w-full rounded border border-slate-300 p-2 text-sm"
        />

        <ITCheckbox
          checked={dryRun}
          onChange={setDryRun}
          label="Simular (no escribe nada y muestra qué se crearía)"
        />

        {/* El loader de marca mientras la API lee el archivo: es la única
            operación de hoy con una espera real (1.398 filas). */}
        {busy && <Loader label="Procesando el archivo…" />}

        {error && (
          <ITAlert variant="error" title="No se pudo importar">
            {error}
          </ITAlert>
        )}

        {rejected.length > 0 && (
          <div className="max-h-48 overflow-auto rounded border border-red-200 bg-red-50 p-2">
            <ul className="flex flex-col gap-1 text-xs text-red-800">
              {rejected.map(([row, reason]) => (
                <li key={row}>
                  <span className="font-semibold">{row.replace("fila_", "Fila ")}:</span> {reason}
                </li>
              ))}
            </ul>
          </div>
        )}

        {report && (
          <div className="rounded border border-slate-200 bg-slate-50 p-3 text-sm">
            <ITFlex direction="column" gap={1}>
              <ITText className="font-semibold">
                {report.dryRun ? "Simulación: no se escribió nada" : "Importación aplicada"}
              </ITText>
              <span>Filas leídas: {report.totalRows}</span>
              <span>Aceptadas: {report.accepted} · Rechazadas: {report.rejected}</span>
              <span>Existencias totales: {formatQuantity(report.totalStock)}</span>
              <span>Productos en cero: {report.zeroStockCount}</span>
              <span>Unidades: {report.unitMeasures.join(", ") || "—"}</span>
              {!report.dryRun && (
                <span>
                  Creados: {report.created} · Actualizados: {report.updated} · Sin cambios: {report.skipped}
                </span>
              )}
              <ITText muted className="text-xs">
                El stock del archivo **no** se carga: es el total heredado y no dice en qué estantería
                está cada pieza. Se concilia con conteo antes del corte.
              </ITText>
            </ITFlex>
          </div>
        )}

        <ITFlex justify="end" gap={2}>
          <ITButton label="Cerrar" variant="outline" onClick={onClose} disabled={busy} />
          <ITButton
            label={busy ? "Procesando…" : dryRun ? "Simular" : "Importar"}
            color={dryRun ? "primary" : "danger"}
            onClick={() => void run()}
            disabled={busy || file === null}
          />
        </ITFlex>
      </ITFlex>
    </ITDialog>
  );
}
