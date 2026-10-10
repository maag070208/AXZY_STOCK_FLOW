/** Surtido (docs/api/surtido.md). `exportStatus` cuenta el intento honesto: sin ERP sale `FAILED`. */
export type OrderHeader = {
  id: number;
  orderNumber: string;
  packagingDate: string | null;
  status: string;
  customerCode: string | null;
  concept: string | null;
  converted: boolean;
  convertedAt: string | null;
  exportStatus: string | null;
  exportAttempts: number;
  exportLastError: string | null;
  totalLines: number;
  packagedLines: number;
};
