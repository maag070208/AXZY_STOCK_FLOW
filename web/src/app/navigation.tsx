import type { ReactNode } from "react";
import { FaBoxes, FaBoxOpen, FaClipboardList, FaExchangeAlt, FaHistory, FaHome, FaLayerGroup, FaTags, FaTruck, FaWarehouse } from "react-icons/fa";
import i18n from "@shared/i18n";

export interface MenuEntry {
  id: string;
  label: string;
  path: string;
  /** Permission the entry requires; `null` leaves it visible to anyone who has signed in. */
  permission: string | null;
  icon: ReactNode;
}

/**
 * The menu, with the permission each entry requires. The web does not reimplement the matrix: it only decides what
 * offer, and the API decides for real. The frame (the side menu) and the home page (the shortcuts) use it.
 */
export const MENU: MenuEntry[] = [
  { id: "home", label: i18n.t("nav.home"), path: "/", permission: null, icon: <FaHome size={14} /> },
  { id: "products", label: i18n.t("nav.products"), path: "/products", permission: "product.view", icon: <FaBoxes size={14} /> },
  { id: "locations", label: i18n.t("nav.locations"), path: "/locations", permission: "location.view", icon: <FaLayerGroup size={14} /> },
  { id: "warehouses", label: i18n.t("nav.warehouses"), path: "/warehouses", permission: "warehouse.view", icon: <FaWarehouse size={14} /> },
  { id: "stock", label: i18n.t("nav.stock"), path: "/stock", permission: "inventory.view", icon: <FaBoxOpen size={14} /> },
  { id: "kardex", label: i18n.t("nav.ledger"), path: "/kardex", permission: "kardex.view", icon: <FaHistory size={14} /> },
  { id: "invoices", label: i18n.t("nav.receiving"), path: "/invoices", permission: "invoice.view", icon: <FaClipboardList size={14} /> },
  { id: "orders", label: i18n.t("nav.fulfillment"), path: "/orders", permission: "order.view", icon: <FaTruck size={14} /> },
  { id: "transfers", label: i18n.t("nav.transfers"), path: "/transfers", permission: "transfer.view", icon: <FaExchangeAlt size={14} /> },
  { id: "labels", label: i18n.t("nav.labels"), path: "/labels", permission: "label.queue_view", icon: <FaTags size={14} /> },
  { id: "audit", label: i18n.t("nav.audit"), path: "/audit", permission: "audit.view", icon: <FaHistory size={14} /> },
];
