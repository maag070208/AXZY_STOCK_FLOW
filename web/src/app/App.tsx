import { Route, Routes } from "react-router-dom";
import PrivateRoutes from "@app/guards/PrivateRoutes";
import RequireAuth from "@app/guards/RequireAuth";
import RequirePermission from "@app/guards/RequirePermission";
import ChangePasswordPage from "@pages/auth/ChangePasswordPage";
import LoginPage from "@pages/auth/LoginPage";
import LocationsPage from "@pages/catalog/LocationsPage";
import ProductsPage from "@pages/catalog/ProductsPage";
import WarehousesPage from "@pages/catalog/WarehousesPage";
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
      </Route>

      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
