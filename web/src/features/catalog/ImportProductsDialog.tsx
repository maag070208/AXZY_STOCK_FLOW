import { ITAlert, ITButton, ITCheckbox, ITDialog, ITFlex, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState } from "react";
import { catalogApi, type ImportReport } from "@entities/catalog";
import { ApiError } from "@shared/api/types";
import { formatQuantity } from "@shared/lib/format";
import { Loader } from "@shared/ui/loader/Loader";
import i18n from "@shared/i18n";
import { describeError } from "@shared/api/errorMessage";

interface Props {
  open: boolean;
  onClose: () => void;
  /** Called only when something was really written, to reload the table. */
  onImported: () => void;
}

/**
 * The catalog file import.
 *
 * **Simulates by default.** The reverse oversight —writing 1,398 products when the
 * wanted to test— it cannot be undone; the opposite is solved by going back to
 * clicking. It is the same decision the API takes with `dryRun` (docs/api/catalogo.md).
 *
 * The rejected rows are listed **with their row number**: without it, a file with
 * an error is unresolvable.
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
      setError(describeError(apiError, "catalog.importFailed"));
      // `IMPORT_INVALID_ROWS` carries a detail per row (`row_3`): it is what
      // lets the file be corrected without guessing which row was wrong.
      setRejected(Object.entries(apiError.details ?? {}));
      setReport(null);
    } finally {
      setBusy(false);
    }
  };

  return (
    <ITDialog isOpen={open} onClose={onClose} title={i18n.t("catalog.importCatalog")} className="w-full max-w-2xl">
      <ITFlex direction="column" gap={4}>
        <ITText muted className="text-xs">
          Archivo CSV o TSV con las columnas: identificador externo, código, nombre, unidad de medida y
          existencias. Una fila inválida rechaza el archivo entero y no se escribe nada.
        </ITText>

        <input
          type="file"
          accept=".csv,.tsv,.txt"
          aria-label={i18n.t("catalog.catalogFile")}
          onChange={(event) => setFile(event.target.files?.[0] ?? null)}
          className="w-full rounded border border-slate-300 p-2 text-sm"
        />

        <ITCheckbox
          checked={dryRun}
          onChange={setDryRun}
          label={i18n.t("catalog.dryRunButton")}
        />

        {/* El loader de marca mientras la API lee el archivo: es la única
            operación de hoy con una espera real (1.398 filas). */}
        {busy && <Loader label={i18n.t("catalog.processingFile")} />}

        {error && (
          <ITAlert variant="error" title={i18n.t("catalog.importFailed")}>
            {error}
          </ITAlert>
        )}

        {rejected.length > 0 && (
          <div className="max-h-48 overflow-auto rounded border border-red-200 bg-red-50 p-2">
            <ul className="flex flex-col gap-1 text-xs text-red-800">
              {rejected.map(([row, reason]) => (
                <li key={row}>
                  <span className="font-semibold">{row.replace("row_", i18n.t("catalog.row"))}:</span> {reason}
                </li>
              ))}
            </ul>
          </div>
        )}

        {report && (
          <div className="rounded border border-slate-200 bg-slate-50 p-3 text-sm">
            <ITFlex direction="column" gap={1}>
              <ITText className="font-semibold">
                {report.dryRun ? i18n.t("catalog.dryRunNotice") : i18n.t("catalog.importApplied")}
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
          <ITButton label={i18n.t("common.close")} variant="outline" onClick={onClose} disabled={busy} />
          <ITButton
            label={busy ? i18n.t("catalog.processing") : dryRun ? i18n.t("catalog.preview") : i18n.t("catalog.import")}
            color={dryRun ? "primary" : "danger"}
            onClick={() => void run()}
            disabled={busy || file === null}
          />
        </ITFlex>
      </ITFlex>
    </ITDialog>
  );
}
