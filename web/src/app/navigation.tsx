import type { ReactNode } from "react";
import { FaBoxes, FaBoxOpen, FaClipboardList, FaExchangeAlt, FaHistory, FaHome, FaLayerGroup, FaTags, FaTruck, FaWarehouse } from "react-icons/fa";

export interface MenuEntry {
  id: string;
  label: string;
  path: string;
  /** Permiso que exige la entrada; `null` la deja visible a cualquiera que haya entrado. */
  permission: string | null;
  icon: ReactNode;
}

/**
 * El menú, con el permiso que exige cada entrada. La web no reimplementa la matriz: solo decide qué
 * ofrecer, y la API decide de verdad. Lo usan el armazón (el menú lateral) y el inicio (los accesos).
 */
export const MENU: MenuEntry[] = [
  { id: "home", label: "Inicio", path: "/", permission: null, icon: <FaHome size={14} /> },
  { id: "products", label: "Productos", path: "/products", permission: "product.view", icon: <FaBoxes size={14} /> },
  { id: "locations", label: "Ubicaciones", path: "/locations", permission: "location.view", icon: <FaLayerGroup size={14} /> },
  { id: "warehouses", label: "Bodegas", path: "/warehouses", permission: "warehouse.view", icon: <FaWarehouse size={14} /> },
  { id: "stock", label: "Existencias", path: "/stock", permission: "inventory.view", icon: <FaBoxOpen size={14} /> },
  { id: "kardex", label: "Kardex", path: "/kardex", permission: "kardex.view", icon: <FaHistory size={14} /> },
  { id: "invoices", label: "Recepción", path: "/invoices", permission: "invoice.view", icon: <FaClipboardList size={14} /> },
  { id: "orders", label: "Surtido", path: "/orders", permission: "order.view", icon: <FaTruck size={14} /> },
  { id: "transfers", label: "Traslados", path: "/transfers", permission: "transfer.view", icon: <FaExchangeAlt size={14} /> },
  { id: "labels", label: "Etiquetas", path: "/labels", permission: "label.queue_view", icon: <FaTags size={14} /> },
  { id: "audit", label: "Auditoría", path: "/audit", permission: "audit.view", icon: <FaHistory size={14} /> },
];
