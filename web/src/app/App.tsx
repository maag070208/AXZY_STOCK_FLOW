import { Navigate, Route, Routes } from "react-router-dom";
import PrivateRoutes from "./guards/PrivateRoutes";
import RequiresPermission from "./guards/RequiresPermission";
import LoginPage from "@pages/auth/LoginPage";
import HomePage from "@pages/home/HomePage";
import InvoicesPage from "@pages/reception/InvoicesPage";
import InvoiceDetailPage from "@pages/reception/InvoiceDetailPage";
import PendingApprovalPage from "@pages/reception/PendingApprovalPage";
import OrdersPage from "@pages/fulfillment/OrdersPage";
import OrderDetailPage from "@pages/fulfillment/OrderDetailPage";
import TransfersPage from "@pages/transfer/TransfersPage";
import ProductsPage from "@pages/catalog/ProductsPage";
import LocationsPage from "@pages/catalog/LocationsPage";
import InventoryPage from "@pages/inventory/InventoryPage";
import KardexPage from "@pages/inventory/KardexPage";
import AdjustmentApprovalPage from "@pages/inventory/AdjustmentApprovalPage";
import LabelsPage from "@pages/label/LabelsPage";
import ReportsPage from "@pages/report/ReportsPage";
import UsersPage from "@pages/access/UsersPage";
import AuditPage from "@pages/access/AuditPage";
import HealthPage from "@pages/access/HealthPage";
import NotFoundPage from "@pages/errors/NotFoundPage";

/**
 * Rutas de la aplicación.
 *
 * Las 12 rutas protegidas del legacy (`App.tsx`) más las pantallas nuevas del
 * plan. Dos detalles deliberados:
 *
 * 1. **Cada ruta declara el permiso** de su pantalla con `RequiresPermission`.
 *    El guard del menú sólo oculta el enlace; este guard bloquea la URL directa.
 *    El backend revalida siempre: la web no es la autoridad.
 * 2. `/invoices/pending-approval` se declara **antes** que `/invoices/:id`. React
 *    Router v7 ordena por especificidad y lo resolvería igual, pero el orden
 *    explícito evita que un futuro `:id` numérico se coma la ruta estática.
 */
export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route
        path="/"
        element={
          <PrivateRoutes>
            <HomePage />
          </PrivateRoutes>
        }
      />

      {/* -------------------------------------------------------- recepción */}
      <Route
        path="/invoices"
        element={
          <PrivateRoutes>
            <RequiresPermission permission="invoice.view">
              <InvoicesPage />
            </RequiresPermission>
          </PrivateRoutes>
        }
      />
      <Route
        path="/invoices/pending-approval"
        element={
          <PrivateRoutes>
            <RequiresPermission permission="invoice.view">
              <PendingApprovalPage />
            </RequiresPermission>
          </PrivateRoutes>
        }
      />
      <Route
        path="/invoices/:id"
        element={
          <PrivateRoutes>
            <RequiresPermission permission="invoice.view">
              <InvoiceDetailPage />
            </RequiresPermission>
          </PrivateRoutes>
        }
      />

      {/* ---------------------------------------------------------- surtido */}
      <Route
        path="/orders"
        element={
          <PrivateRoutes>
            <RequiresPermission permission="order.view">
              <OrdersPage />
            </RequiresPermission>
          </PrivateRoutes>
        }
      />
      <Route
        path="/orders/:id"
        element={
          <PrivateRoutes>
            <RequiresPermission permission="order.view">
              <OrderDetailPage />
            </RequiresPermission>
          </PrivateRoutes>
        }
      />
      <Route
        path="/transfers"
        element={
          <PrivateRoutes>
            <RequiresPermission permission="transfer.view">
              <TransfersPage />
            </RequiresPermission>
          </PrivateRoutes>
        }
      />

      {/* ---------------------------------------------------------- almacén */}
      <Route
        path="/products"
        element={
          <PrivateRoutes>
            <RequiresPermission permission="product.view">
              <ProductsPage />
            </RequiresPermission>
          </PrivateRoutes>
        }
      />
      <Route
        path="/locations"
        element={
          <PrivateRoutes>
            <RequiresPermission permission="location.view">
              <LocationsPage />
            </RequiresPermission>
          </PrivateRoutes>
        }
      />
      <Route
        path="/inventory"
        element={
          <PrivateRoutes>
            <RequiresPermission permission="inventory.view">
              <InventoryPage />
            </RequiresPermission>
          </PrivateRoutes>
        }
      />
      <Route
        path="/inventory/pending-approval"
        element={
          <PrivateRoutes>
            <RequiresPermission permission="inventory.approve_adjustment">
              <AdjustmentApprovalPage />
            </RequiresPermission>
          </PrivateRoutes>
        }
      />
      <Route
        path="/kardex"
        element={
          <PrivateRoutes>
            <RequiresPermission permission="kardex.view">
              <KardexPage />
            </RequiresPermission>
          </PrivateRoutes>
        }
      />

      {/* -------------------------------------------------------- etiquetas */}
      <Route
        path="/labels"
        element={
          <PrivateRoutes>
            <RequiresPermission permission="label.print">
              <LabelsPage />
            </RequiresPermission>
          </PrivateRoutes>
        }
      />

      {/* --------------------------------------------------------- reportes */}
      <Route
        path="/reports"
        element={
          <PrivateRoutes>
            <RequiresPermission permission="report.view">
              <ReportsPage />
            </RequiresPermission>
          </PrivateRoutes>
        }
      />

      {/* ----------------------------------------------------- configuración */}
      <Route
        path="/users"
        element={
          <PrivateRoutes>
            <RequiresPermission permission="user.view">
              <UsersPage />
            </RequiresPermission>
          </PrivateRoutes>
        }
      />
      <Route
        path="/audit"
        element={
          <PrivateRoutes>
            <RequiresPermission permission="audit.view">
              <AuditPage />
            </RequiresPermission>
          </PrivateRoutes>
        }
      />
      <Route
        path="/health"
        element={
          <PrivateRoutes>
            <RequiresPermission permission="system.health">
              <HealthPage />
            </RequiresPermission>
          </PrivateRoutes>
        }
      />

      {/* 404 dentro del layout: el legacy no tenía y dejaba la pantalla en blanco. */}
      <Route
        path="*"
        element={
          <PrivateRoutes>
            <NotFoundPage />
          </PrivateRoutes>
        }
      />

      {/* La web se sirve como SPA; si alguien entra por `/index.html` se le
          manda al inicio en lugar de dejarlo fuera de la aplicación. */}
      <Route path="/index.html" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
