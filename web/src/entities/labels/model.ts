/** Labels: the print queue (docs/api/etiquetas.md). */
export type PrintJob = {
  id: number;
  templateCode: string;
  copies: number;
  status: string;
  attempts: number;
  lastError: string | null;
  printedAt: string | null;
  createdAt: string;
  reprintedFromId: number | null;
};
