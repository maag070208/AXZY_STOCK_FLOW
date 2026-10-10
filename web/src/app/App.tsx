import { Route, Routes } from "react-router-dom";
import PrivateRoutes from "@app/guards/PrivateRoutes";
import RequireAuth from "@app/guards/RequireAuth";
import RequirePermission from "@app/guards/RequirePermission";
import ChangePasswordPage from "@pages/auth/ChangePasswordPage";
import LoginPage from "@pages/auth/LoginPage";
import LocationsPage from "@pages/catalog/LocationsPage";
import ProductsPage from "@pages/catalog/ProductsPage";
import WarehousesPage from "@pages/catalog/WarehousesPage";
import AuditPage from "@pages/audit/AuditPage";
import StockPage from "@pages/inventory/StockPage";
import KardexPage from "@pages/inventory/KardexPage";
import InvoiceDetailPage from "@pages/receiving/InvoiceDetailPage";
import OrderDetailPage from "@pages/fulfillment/OrderDetailPage";
import TransferDetailPage from "@pages/transfers/TransferDetailPage";
import InvoicesPage from "@pages/receiving/InvoicesPage";
import OrdersPage from "@pages/fulfillment/OrdersPage";
import TransfersPage from "@pages/transfers/TransfersPage";
import PrintJobsPage from "@pages/labels/PrintJobsPage";
import NotFoundPage from "@pages/errors/NotFoundPage";
import HomePage from "@pages/home/HomePage";

/**
 * Las rutas.
 *
 * Dos puertas distintas, y la diferencia importa:
 *  - `RequireAuth`: solo sesión, **sin armazón**. Es para las pantallas de cuenta
 *    que se ven a pantalla completa, como el cambio de contraseña obligatorio.
 *  - `PrivateRoutes`: sesión **y** armazón (menú, barra superior) para el resto.
 *
 * `RequirePermission` es para no ofrecer lo que va a fallar: la API decide igual
 * en cada petición y responde `403` aunque la URL se escriba a mano.
 */
export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route element={<RequireAuth />}>
        <Route path="/change-password" element={<ChangePasswordPage />} />
      </Route>

      <Route element={<PrivateRoutes />}>
        <Route path="/" element={<HomePage />} />
        <Route
          path="/products"
          element={
            <RequirePermission permissions={["product.view"]}>
              <ProductsPage />
            </RequirePermission>
          }
        />
        <Route
          path="/locations"
          element={
            <RequirePermission permissions={["location.view"]}>
              <LocationsPage />
            </RequirePermission>
          }
        />
        <Route
          path="/warehouses"
          element={
            <RequirePermission permissions={["warehouse.view"]}>
              <WarehousesPage />
            </RequirePermission>
          }
        />
        <Route
          path="/stock"
          element={
            <RequirePermission permissions={["inventory.view"]}>
              <StockPage />
            </RequirePermission>
          }
        />
        <Route
          path="/kardex"
          element={
            <RequirePermission permissions={["kardex.view"]}>
              <KardexPage />
            </RequirePermission>
          }
        />
        <Route
          path="/invoices"
          element={
            <RequirePermission permissions={["invoice.view"]}>
              <InvoicesPage />
            </RequirePermission>
          }
        />
        <Route
          path="/invoices/:id"
          element={
            <RequirePermission permissions={["invoice.view"]}>
              <InvoiceDetailPage />
            </RequirePermission>
          }
        />
        <Route
          path="/orders/:id"
          element={
            <RequirePermission permissions={["order.view"]}>
              <OrderDetailPage />
            </RequirePermission>
          }
        />
        <Route
          path="/transfers/:id"
          element={
            <RequirePermission permissions={["transfer.view"]}>
              <TransferDetailPage />
            </RequirePermission>
          }
        />
        <Route
          path="/orders"
          element={
            <RequirePermission permissions={["order.view"]}>
              <OrdersPage />
            </RequirePermission>
          }
        />
        <Route
          path="/transfers"
          element={
            <RequirePermission permissions={["transfer.view"]}>
              <TransfersPage />
            </RequirePermission>
          }
        />
        <Route
          path="/labels"
          element={
            <RequirePermission permissions={["label.queue_view"]}>
              <PrintJobsPage />
            </RequirePermission>
          }
        />
        <Route
          path="/audit"
          element={
            <RequirePermission permissions={["audit.view"]}>
              <AuditPage />
            </RequirePermission>
          }
        />
      </Route>

      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
