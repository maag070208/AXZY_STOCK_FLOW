import type { Locator, Page } from "@playwright/test";

/**
 * The sidebar of the kit collapses to icons and shows the labels on hover. Its accessible name is
 * the kit's own; it is located by that name, not by position on the page.
 */
const SIDEBAR_NAME = "Componentes de la librería";

/** Routes behind each sidebar label, as the tests name them. */
const ROUTES: Record<string, string> = {
  Products: "/products",
  Locations: "/locations",
  Warehouses: "/warehouses",
  Stock: "/stock",
  Ledger: "/kardex",
  Receiving: "/invoices",
  Fulfillment: "/orders",
  Transfers: "/transfers",
  Labels: "/labels",
  Audit: "/audit",
};

/** Expands the sidebar by moving the pointer over it, and returns it. */
export async function expandMenu(page: Page): Promise<Locator> {
  const sidebar = page.getByRole("navigation", { name: SIDEBAR_NAME });
  const box = await sidebar.boundingBox();
  if (box) await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  return sidebar;
}

/**
 * Opens a module from the sidebar. The hash change does what a click does (HashRouter renders the
 * route) without reloading the page, so the session in memory survives. The sidebar click itself is
 * covered by the visibility test in catalog.spec.ts.
 */
export async function openMenu(page: Page, label: string): Promise<void> {
  const route = ROUTES[label];
  if (!route) throw new Error(`No route for sidebar label "${label}"`);
  await page.evaluate((target) => {
    window.location.hash = `#${target}`;
  }, route);
}
