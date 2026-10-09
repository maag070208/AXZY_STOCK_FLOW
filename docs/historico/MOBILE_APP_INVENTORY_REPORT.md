# Inventario técnico — App móvil **Cascar** (`BASE/mobile-app`)

> **Alcance y método.** Documento generado por lectura estática del código. No se instaló nada, no se modificó ningún fichero dentro de `BASE/mobile-app` (solo se creó este informe fuera del repo, en la raíz del workspace).
> Todo lo afirmado proviene de `App.js`, `src/**/*.js`, `package.json`, `app.json`, `eas.json`, `babel.config.js`, `.eslintrc.json`, `.gitignore` y `assets/`. Cuando el comportamiento no es deducible del código se marca explícitamente como **no verificable / ambiguo**. Para contrastar semántica de endpoints se leyó también el backend Express (`BASE/backend/src/routes/*.ts`), indicado como **[backend]**.
>
> Ficheros JS totales: 55 (App.js + 26 pantallas + 20 componentes + 5 servicios + 4 utils + 1 contexto + 1 tema + 1 enum de estados). ≈8.754 líneas.

---

## 1. Stack y arquitectura

### 1.1 `package.json` — dependencias

App Expo (React Native) en **JavaScript puro** (no TypeScript). Nombre de paquete: `cascar-app`, versión `1.0.0`, entry `node_modules/expo/AppEntry.js`.

| Dependencia | Versión | Uso real en el código |
|---|---|---|
| `expo` | ~47.0.9 | runtime |
| `react` | 18.1.0 | — |
| `react-native` | 0.70.8 | — |
| `@react-navigation/native` | ^6.1.1 | `NavigationContainer`, `useNavigation` |
| `@react-navigation/native-stack` | ^6.9.7 | stacks |
| `@react-navigation/bottom-tabs` | ^6.5.2 | tabs |
| `react-native-paper` | ^5.1.2 | UI kit (Card, DataTable, Snackbar, Dialog, Portal, Modal, TextInput, Searchbar, Menu, IconButton, Button, Provider) |
| `react-native-paper-dropdown` | ^1.0.7 | `DropDown` |
| `react-native-paper-dates` | ^0.20.5 | `DatePickerModal` (solo en `CreateOrderForm.js`) |
| `@react-native-async-storage/async-storage` | ^1.17.11 | persistencia (claves `user`, `axios`) |
| `axios` | ^1.2.2 | cliente HTTP + interceptor |
| `react-hook-form` | ^7.42.1 | formularios |
| `@hookform/resolvers` | ^2.9.10 | `yupResolver` |
| `yup` | ^0.32.11 | validación (Login, Config, Malla) |
| `expo-barcode-scanner` | ~12.1.0 | escaneo |
| `react-native-barcode-mask` | ^1.2.4 | máscara de escaneo |
| `react-native-safe-area-context` | 4.4.1 | `SafeAreaView`/`SafeAreaProvider` |
| `react-native-screens` | ~3.18.0 | `enableScreens(true)` |
| `react-native-reanimated` | ~2.12.0 | **no importado** en el código |
| `intl` | ^1.2.5 | polyfill de locale (importado en `CreateOrderForm.js`) |
| `expo-dev-client`, `expo-status-bar` | — | `expo-status-bar` no se importa |
| `react-native-vision-camera` | ^2.15.4 | **no importado en ningún sitio** (dependencia muerta) |

**Faltantes en `dependencies` aunque se usan en el código:** `prop-types` (importado en 17 ficheros; sólo existe como transitivo — 2 entradas en `package-lock.json`) y `@expo/vector-icons` (`Ionicons`, viene con Expo).

**Scripts:** `start`, `android`, `ios`, `web`, `lint` (eslint .), `build` (`eas build --platform android`), `submit`, `preview` (`-p android --profile preview`), `apk` (`eas build --profile production --platform android`). Dev: `eslint`, `eslint-plugin-react`, `@babel/core`. Lint config `eslint:recommended` + `plugin:react/recommended`, sin reglas custom.

### 1.2 `app.json` / `eas.json`

- `name: cascar-app`, `slug: cscr-app`, `orientation: default`, `userInterfaceStyle: light`.
- Android package `mx.cascar.app`, iOS `supportsTablet: true`; proyecto EAS `a6b6703c-d641-4f5d-a992-5ecc644c3bd5`, owner `maag070208`.
- Splash: `./assets/splash.png` con fondo `#ffffff`; iconos `./assets/icon.png`.
- `web.favicon` apunta a `./assets/favicon.png`, **que no existe** (en `assets/` solo hay `folder.png`, `icon.png`, `logo.png`, `splash.png`).
- `eas.json`: perfil `preview` (APK), `production` vacío, CLI ≥ 3.4.0.
- No hay `android.permissions` declarados ni `usesCleartextTraffic`: la app usa **HTTP en claro** hacia `http://<ip>:4000` (ver 1.6); en builds Android modernos esto requeriría configuración explícita.

### 1.3 `App.js` (190 líneas) — navegación y estado de sesión

```
App
 └─ <Provider> (react-native-paper, SIN prop theme)
     └─ <AuthContext.Provider value={authContext}>
         └─ <NavigationContainer>
             ├─ StackNavigator   (si state.userToken == null)
             │   ├─ "Login"   → LoginScreen   (headerShown:false)
             │   └─ "Config"  → ConfigScreen  (title: "Configuracion")
             └─ TabNavigator     (si state.userToken != null)
                 ├─ "Home"      → HomeScreen        title "Inicio"   [tab visible]
                 ├─ "User"      → UserScreen        title "User"     [tab visible]
                 ├─ "Invoices"  → InvoiceScreen     [tabBarButton: () => null]
                 ├─ "Orders"    → OrderScreen       [tabBarButton: () => null]
                 ├─ "Tickets"   → TicketScreen      [tabBarButton: () => null]
                 ├─ "Location"  → LocationsScreen   [tabBarButton: () => null]
                 └─ "Inventory" → InventoryScreen   [tabBarButton: () => null]
```

- **Stacks anidados** (cada uno es un `createNativeStackNavigator` propio dentro del fichero de la pantalla contenedora):

  | Stack (tab) | Pantallas registradas | Fichero |
  |---|---|---|
  | `Invoices` | `Invoice` → `InvoiceListScreen`; `InvoiceVerification` → `InvoiceDetailScreen`; `InvoiceAssignStore` → `InvoiceAssignStoreScreen` | `src/screens/InvoiceScreen.js` |
  | `Orders` | `OrderHome` → `OrderHomeScreen`; `Order` → `OrderListScreen`; `CreateOrder` → `OrderCreateScreen`; `OrderDetail` → `OrderDetailScreen`; `OrderDetailCreate` → `OrderDetailsCreateScreen` | `src/screens/OrderScreen.js` |
  | `Tickets` | `Tickets` → `TicketListScreen`; `Mesh` → `MeshScreen`; `FreeLabel` → `FreeLabelScreen` | `src/screens/TicketScreen.js` |
  | `Location` | `Invoice` → `LocationsOptionsScreen` (**nombre engañoso**); `Move` → `MoveProductLocation` | `src/screens/LocationsScreen.js` |
  | `Inventory` | `Inventory` → `InventoryOptionsScreen`; `Search` → `SearchLocationContentScreen`; `SearchInventory` → `InventorySearchScreen` | `src/screens/InventoryScreen.js` |

- **Estado global:** `useReducer` en `App.js:25-59` con acciones `RESTORE_TOKEN` (deja `userToken: action.token`, `isLoading:false`), `SIGN_IN`, `SIGN_OUT`, `LEAVE`. Estado inicial `{ isLoading:true, isSignout:false, userToken:null }`.
  - **`bootstrapAsync` (`App.js:61-72`) está vacío** (solo comentarios). **No se restaura la sesión al arrancar**: si la app se cierra, el usuario vuelve a `Login` aunque el `user` siga en AsyncStorage.
  - `RESTORE_TOKEN` y `LEAVE` **nunca se despachan**; `isLoading` e `isSignout` **no se leen en ningún sitio**.
- **Contexto**: `React.useMemo` (`App.js:74-82`) expone únicamente `signIn(token)` y `signOut()`. No hay estado de usuario en el contexto (los datos del usuario se releen de AsyncStorage en cada pantalla).
- `enableScreens(true)` (`App.js:19`).
- Iconos de tab: `screenOptions` sólo resuelve iconos para `Home`/`User`; para el resto `iconName` queda `undefined`.
- `tabBarVisible` (propiedad **deprecada** en React Navigation v6) se usa junto a `tabBarButton: () => null` para ocultar los tabs de módulos.
- Colisión de nombres: el tab `Tickets` y su pantalla inicial interna también se llaman `Tickets`.
- No se usa `linking`, ni tema de React Navigation, ni `Provider theme={theme}` (ver 1.5).

### 1.4 Contexto de autenticación — `src/context/authContext.js`

3 líneas: `export const AuthContext = createContext();`. Sin valor por defecto, sin hooks (`useAuth`), sin tipos.

### 1.5 Tema — `src/core/theme.js`

Extiende `DefaultTheme` de react-native-paper:

```js
primary: "#3182CE", white: "#FFFFFF", text: "#1A202C",
tableRow: "#bee3f8", black: "#000000", success: "#48BB78"
```

- Se importa en `Button.js`, `Input.js`, `Header.js`, `OrderDetailTable.js`, `CustomDialog.js`, `ConfigModal.js` y en casi todas las pantallas con tablas (para `backgroundColor: theme.colors.primary`), pero **nunca se pasa a `<Provider theme={theme}>`**, por lo que Paper usa su tema por defecto en los componentes internos (Diálogos, Snackbars, etc.).
- Colores "hardcodeados" fuera del tema en muchas pantallas: `#EDF2F7` (fondo), `#fff`, `#718096`, `#38A169`, `#E53E3E`.

### 1.6 Utilidades

| Fichero | Contenido | Observaciones |
|---|---|---|
| `src/utils/storage.js` (27) | `storeData(key,value)`, `getData(key)`, `removeData(key)` sobre AsyncStorage con `JSON.stringify/parse` | Errores solo `console.error`. Claves usadas: **`user`** (respuesta de login) y **`axios`** (`{serverUrl}`) |
| `src/utils/axios.js` (49) | `axios.create({timeout: 0})` + interceptor de request; helpers `post/get/patch/put/remove` | Ver abajo |
| `src/utils/jwt.js` (18) | `parseJwt` (usa `atob`) y `isTokenExpired(token)` | **Nunca se importa en ninguna parte → no hay control de expiración de token** |
| `src/utils/wait.js` (5) | `wait(ms)` promesa con `setTimeout` | **Nunca se importa** |

**Cliente axios (`src/utils/axios.js`)**
- `timeout: 0` → **sin timeout** (peticiones potencialmente colgadas indefinidamente).
- Interceptor de request (líneas 8-26), en cada llamada:
  1. `const user = await getData("user")` → si `user.token`, añade `Authorization: Bearer <token>`.
  2. `const baseUrl = await getData("axios")` → si existe, `config.baseURL = http://${baseUrl.serverUrl}:4000`.
- **El puerto 4000 y el esquema `http` están hardcodeados.** Si no hay configuración guardada, no hay `baseURL` y las rutas relativas (`/users/login`, etc.) fallan.
- Helpers: `handleResponse = response => response.data` (desempaqueta `data`), `handleError` → `console.error` + `Promise.reject`. **No hay interceptor de respuesta**: un 401/403/500 no dispara logout, refresh ni mensaje de UI; el error sólo se registra en consola o se captura con `.catch` local.
- JWT: se genera en el servidor; la app sólo lo guarda y lo envía. **No hay refresh token, ni renovación, ni logout automático por expiración.**

### 1.7 Servicios — resumen (detalle completo en §4)

`src/services/userService.js` (6), `productService.js` (8), `invoiceService.js` (10), `orderService.js` (40), `printService.js` (6). Todos son wrappers finos de `utils/axios` sin manejo de errores propio (salvo propagar el rechazo).

### 1.8 Estados — `src/data/status.js`

```js
export const statusEnum = {
  pending: "Pendiente de capturar",
  captured: "Esperando verificacion",
  verified: "Surtida",
  "pending-to-locate": "Pendiente de ubicar",
};
```

- Usado en `InvoiceList.js`, `OrderList.js`, `InvoiceDetailScreen.js`, `InvoiceAssignStoreScreen.js`, `OrderDetailScreen.js`, `OrderDetailsCreateScreen.js`.
- **Incompleto**: el backend produce además `discrepancy`, `assigned` y `done` **[backend `src/service/invoice.service.ts`, `order.service.ts`]**. Para esos valores `statusEnum[status] === undefined` → la UI muestra el estado vacío.
- No hay enum de roles (los roles se resuelven con enteros 1/2/3 en `UserScreen.js:67-71`).

### 1.9 Código muerto / no referenciado (verificado con grep)

| Elemento | Evidencia |
|---|---|
| `src/screens/MoveProductAdmin.js` (617 líneas) | No se importa en ningún fichero. Es una variante casi idéntica de `MoveProductLocation.js` (sin paginación; el diff son logs y `disabled` en inputs) |
| `src/components/TicketList.js` | No se importa. `TicketListScreen` no lo usa |
| `src/components/TicketItem.js` | Solo lo usa `TicketList.js` (muerto). Además sus `propTypes` exigen `orderNumber`/`status` pero recibe `name` → warning + `PropTypes` incorrecto |
| `src/components/AssignLocationCard.js` | Fichero de 6 líneas, función vacía, sin `export` coherente ni uso |
| `src/components/ConfigModal.js` | No se importa; contiene el texto placeholder `"Caca"` y botones sin `onPress` |
| `src/utils/jwt.js`, `src/utils/wait.js` | Sin importadores |
| `react-native-vision-camera`, `react-native-reanimated`, `expo-status-bar` | Dependencias sin uso en el código |

---

## 2. Pantallas (`src/screens/*.js`)

> Convención: **Endpoint** = método + ruta HTTP final (la base es `http://<serverUrl>:4000`).

### 2.1 `LoginScreen.js` (162 líneas) — autenticación

- **Propósito:** inicio de sesión y acceso a la configuración del servidor.
- **UI:** `Container` (fondo `#EDF2F7`, centrado) + `Logo` (assets/logo.png) + `Header` "Inicia sesión en tu cuenta" + `Notification` de error + 2 `Input` (Usuario con icono `account`; Contraseña con icono `lock`/`lock-open` que alterna `secureTextEntry`) + `Button` "Iniciar Sesión" + botón de texto "Configuracion" abajo a la derecha.
- **Formulario:** `react-hook-form` + `yupResolver`; campos `username` (requerido, "El usuario es requerido"), `password` (requerido, "La contraseña es requerida").
- **Acciones:**
  - Iniciar sesión → `loginService.username/password` → `POST /users/login` con `{username, password}`; en éxito `storeData("user", response)` y `signIn(response.token)` (el árbol cambia a tabs). En error → `invalidCredentials = true` → Snackbar "Credenciales inválidas".
  - "Configuracion" → `navigation.navigate("Config")`.
- **Props/params:** ninguno. **Endpoint:** `POST /users/login` (vía `userService.login`).
- **Observaciones:** no hay "recordar sesión", ni recuperación de contraseña, ni validación de expiración del token devuelto. `useCallback` con deps `[]` (no usa `signIn` de deps, pero está estable).

### 2.2 `ConfigScreen.js` (73 líneas) — configuración de servidor

- **Propósito:** guardar IP del servidor backend.
- **UI:** 1 `Input` "IP del servidor" (`serverUrl`) + `Button` "Guardar".
- **Formulario:** `yup` — `serverUrl` requerido ("La URL del servidor es requerida"); sin validación de formato IP/host.
- **Acción:** `storeData("axios", values)` → `{serverUrl}` en AsyncStorage → `navigation.navigate("Login")`.
- **Endpoint:** ninguno. **No hay test de conectividad** ni botón de "probar conexión"; no precarga el valor guardado (el campo siempre aparece vacío).
- Llegada: sólo desde `LoginScreen`.

### 2.3 `HomeScreen.js` (182 líneas) — panel (tab "Inicio")

- **Propósito:** menú principal con contadores.
- **UI:** `ScrollView` con `RefreshControl`; rejilla de 5 `CategoryCard` (componente local `HomeScreen.js:18-43`, `Pressable` + `Card` con título, icono Ionicons y valor):
  - "Facturas Pendientes" (`receipt-outline`) → `navigate("Invoices")`, valor = `getReadyInvoice(user.id)` → `GET /invoices/ready/:id`, `.length`.
  - "Ordenes Pendientes" (`cart-outline`) → `navigate("Orders")`, valor = `getReadyOrder(user.id)` → `GET /orders/ready/:id`, `.length`.
  - "Locaciones" (`location-outline`) → `navigate("Location")` (sin `value` aunque `propTypes` lo exige → warning).
  - "Inventario" (`cube-outline`) → `navigate("Inventory")` (sin `value`).
  - "Etiquetas" (`pricetag-outline`) → `navigate("Tickets")` (sin `value`).
- **Recarga:** `navigation.addListener("focus", …)` + pull-to-refresh (`onRefresh` no espera las promesas).
- **Endpoint:** `GET /invoices/ready/:id`, `GET /orders/ready/:id`.
- `propTypes` declara `navigation: PropTypes.func` (debería ser objeto).

### 2.4 `UserScreen.js` (91 líneas) — perfil (tab "User")

- **UI:** `Card` con icono `person-circle-outline`, `UserName: {userData.name}`, `Role:` (roleId 1 → "Admin", 2 → "Operaciones", cualquier otro → "Almacen") y `<Button title="Cerrar sesion" onPress={handleSignOut} />` (botón **nativo** de RN, no el componente `Button` de Paper).
- **Datos:** `getData("user")` (token, id, name, roleId).
- **Acción logout:** `removeData("user")` + `signOut()` → vuelve al stack `Login`. **No limpia la configuración `axios`** (correcto, la IP persiste).
- **Endpoint:** ninguno. No permite editar datos, ni cambiar contraseña, ni ver permisos.

### 2.5 `InventoryScreen.js` (32 líneas) — stack "Inventario"

Sólo define el `Stack.Navigator`: `Inventory` (`InventoryOptionsScreen`, título "Inventario"), `Search` (`SearchLocationContentScreen`, "Buscar"), `SearchInventory` (`InventorySearchScreen`, "Buscar Iventario" — typo en el título).

### 2.6 `InventoryOptionsScreen.js` (97 líneas) — menú de inventario

- **UI:** 2 `CategoryCard` (mismo patrón que Home):
  - "Buscar Producto" (`search`) → `navigate("SearchInventory")`.
  - "Buscar material en ubicación" (`search-outline`) → `navigate("Search")`.
- **Endpoint:** ninguno.

### 2.7 `InventorySearchScreen.js` (251 líneas) — inventario por producto

- **Propósito:** dado un código de producto, ver en qué ubicaciones hay stock.
- **UI:**
  - `Container` + `Header` "Inventario del producto: {productCode}" (código en rojo).
  - `Searchbar` (placeholder "Buscar producto", icono `barcode` → `handleScan`) + `Menu` desplegable con hasta 10 resultados.
  - `ProductLocationTable` si hay datos, si no `EmptyState` "No se encontraron productos".
  - `Notification` (error).
- **Acciones de usuario:**
  - Escribir ≥2 caracteres → `getSearchProduct(text)` → `GET /products/:code` (respuesta esperada: array de `{code, name}`), se muestra `code + " - " + name`, se recorta a 10.
  - Pulsar un resultado → `findProductsOnLocation(item.code)`.
  - Icono barcode → `CodeScanner`; al detectar código dentro del cuadro central → `findProductsOnLocation(e.data)`.
  - Vaciar el campo → `handleClear()` (limpia resultados y código).
- **Datos mostrados:** `response.productLocationsHasStock` mapeado agregando `productCode`; columnas de `ProductLocationTable`: Producto (`productCode`), Descripcion (`productDescription`), Locacion (`location`), Unidades (`total`).
- **Endpoints:** `GET /products/:code`, `GET /store/:productCode` (`getProductLocations`).
- **Observaciones:** el texto del buscador está ligado a `productCode` (se sobreescribe con cada `onChangeText`); `setProductCode(code.toUpperCase())` pero la consulta se hace con el código **sin** normalizar (`.toUpperCase()` no se aplica a la petición); el `propTypes` exige `navigation: PropTypes.func` pero el componente no recibe props (warning).

### 2.8 `SearchLocationContentScreen.js` (132 líneas) — contenido de una ubicación

- **Propósito:** listar los productos que hay en una ubicación.
- **UI:** `Container` + `Header` "Contenido de la Ubicación: {locationCode}" (rojo) + `TextInput` "Ubicación" (ancho 250, `onBlur` dispara la búsqueda) + `IconButton` `barcode-scan` (escáner) + `ProductLocationTable` o `EmptyState` "No se encontraron productos".
- **Acciones:** escribir la ubicación y salir del campo, o escanearla.
- **Endpoint:** `GET /locations/inventory/:code` (`getLocationContent`, con `toUpperCase().trim()`).
- **Datos:** respuesta directa (array con `productCode`, `productDescription`, `location`, `total`) pasada a la tabla.
- **Observaciones:** no muestra `Notification` de error (solo `console.log`); `propTypes` declara `navigation: PropTypes.func` sin recibirlo.

### 2.9 `InvoiceScreen.js` (37 líneas) — stack "Facturas"

Registra `Invoice` (`InvoiceListScreen`, "Facturas"), `InvoiceVerification` (`InvoiceDetailScreen`, "Detalle Factura"), `InvoiceAssignStore` (`InvoiceAssignStoreScreen`, "Asignar Locacion").

### 2.10 `InvoiceListScreen.js` (55 líneas) — lista de facturas

- **UI:** `InvoiceList` (componente).
- **Datos:** al enfocar la pantalla → `getToWork(user.id)` → `GET /invoices/to-work/:id`. Estado `data` + `refreshing` (inicial `true`).
- **Endpoint:** `GET /invoices/to-work/:id`.

### 2.11 `InvoiceDetailScreen.js` (790 líneas) — captura física de factura

- **Propósito:** captura de conteo físico (unidades por caja y cantidad física) por producto de una factura, e impresión de etiquetas.
- **Params de ruta:** `{ invoice }` con `id`, `invoiceNumber`, `invoiceType` (`NATIONAL` / otro), `receiptDate` (string `YYYY-MM-DD`), `status`, `createdAt`, `updatedAt`.
- **UI:**
  - Cabecera blanca: "Factura: {invoiceNumber}" + `statusEnum[invoice.status]`.
  - `DataTable` horizontal (columnas × anchos fijos): **Producto** (150), **Descripcion** (450), **Contenedor** (100), **Unidades x caja** (120, editable), **Cantidad** (100, solo lectura), **Cantidad Fisica** (120, editable), **Acciones** (150).
  - Celda "Unidades x caja": si `status === "captured"` o `"discrepancy"` muestra texto; si no, `TextInput` numérico controlado por `react-hook-form` (`quantities[i].unitsPerBox`).
  - Celda "Cantidad Fisica": igual con `quantities[i].physicalQuantity`.
  - Acciones por fila:
    - `status === "captured"` → icono `check` (verde, inerte) + icono `printer` → abre diálogo de impresión.
    - `status === "discrepancy"` → icono `alert` (rojo) → abre diálogo "Discrepancia" ("Favor de contactar al administrador…").
    - otro → icono `content-save` → `onSubmit(values, rowIndex)` → diálogo de confirmación "Guardar Factura" ("Este proceso guardara los datos capturados, ya no sera posible editar la factura, sin la ayuda de un administrador.").
  - Diálogos: confirmación de guardado (`CustomDialog` + `saveDetails`), impresión (`CustomDialog` sin botones + `ModalPrint`), discrepancia (`CustomDialog`).
  - `Notification` (Snackbar) para éxito/error.
  - Pull-to-refresh → recarga los detalles.
- **Formulario:** `useForm` con `defaultValues` derivados de `formData` y `reset(formData)` en `useEffect`.
- **Datos / transformación (`transformResponse`, líneas 95-129):** por fila → `{id, productCode, productName, productDescription, quantity, location: response.location, container: invoice.invoiceNumber, physicalQuantity, unitsPerBox, boxQuantity, unitMeasure, status}`; y la estructura de formulario `quantities[i] = {id, user: user.name, productName, productCode, quantity, physicalQuantity, unitsPerBox, container: invoiceNumber, boxQuantity, unitMeasure, location, status}`. (`response.location` es una propiedad del array, probablemente `undefined`.)
- **Guardado (`transformRequest` + `saveDetails`):** valida que **ningún** `unitsPerBox` sea 0 → si lo es, error "La cantidad de unidades por caja no puede ser 0" y aborta. Si todo va bien: `PATCH /invoices/detail/capture` con `{products:[{id, user, productCode, quantity, physicalQuantity, boxQuantity, unitsPerBox, container, location:""}]}` → mensaje "Factura guardada correctamente" → recarga.
- **Impresión de etiquetas (`handlePrint` + `ModalPrint`):** el diálogo pregunta "Tamaño de etiqueta" (Normal `sm` / Grande `lg`). Cálculo: `totalBoxes = floor(physicalQuantity / unitsPerBox)`, `resto = physicalQuantity % unitsPerBox`; se genera una etiqueta por caja con `quantity = unitsPerBox` y `boxQuantity = n`, más una etiqueta final con el resto (`boxQuantity = totalBoxes+1`) si `resto > 0`. Payload a `POST /prints`:
  ```json
  { "labels": [ { "labelId": 8 | 1,
      "data": { "productName", "productCode", "quantity": "<qty> <unitMeasure>",
                "boxQuantity", "numberOfBoxes", "invoiceNumber",
                "invoiceType": "CN" | "CE", "receiptDate": "dd-mm-yyyy" } } ] }
  ```
  `labelId = 8` si `sm`, `1` si `lg`; `invoiceType` `"CN"` si `invoice.invoiceType === "NATIONAL"`, si no `"CE"`; `receiptDate` convertida con `ddmmyyyy()` (`YYYY-MM-DD` → `DD-MM-YYYY`, sin validación).
- **Endpoints:** `GET /invoices/detail/:id/:userId` (`getDetails`), `PATCH /invoices/detail/capture` (`captureDetails`), `POST /prints` (`addPrint`).
- **Observaciones / defectos:** `useEffect(async () => {…})` en línea 140 (callback async, anti-patrón); `getDataFromApi` declarado sin parámetros pero invocado con `(invoice.id, user.id)`; `handleDiscrepancy` sólo cierra el diálogo (no hay endpoint de ajuste aunque el backend expone `PATCH /invoices/:id` con permiso `invoice:approve`); el usuario que captura se envía por **nombre** (`user.name`), no por id.

### 2.12 `InvoiceAssignStoreScreen.js` (537 líneas) — asignación de ubicaciones a factura

- **Propósito:** repartir las cantidades de una factura "pendiente de ubicar" entre ubicaciones escaneadas.
- **Params:** `{ invoice }` (`id`, `invoiceNumber`, `status`, `createdAt`, `updatedAt`).
- **UI:** si `scanning` → `CodeScanner` a pantalla completa; si no:
  - Cabecera "Factura: {invoiceNumber}" + `statusEnum[invoice.status]`.
  - `DataTable`: **Producto** (150), **Descripcion** (500), **Cantidad** (150, = `row.quantity - row.physicalAssignItems`), **Unidades a Asignar** (140, `TextInput` numérico; si la cantidad es 0 → icono `check` verde), **Locacion** (90: icono `check` si cantidad 0, si no `IconButton` `barcode-scan` → `handleScan(rowIndex)`).
  - `Notification`.
- **Flujo de escaneo (máquina de estados `step` + `scanning` + `scanned`):**
  1. El usuario escribe `unitsToAssign` y pulsa el icono de escaneo → validaciones: vacío/`"0"` → "No se ha asignado ninguna unidad"; `unitsToAssign > quantity` → "No se puede asignar mas unidades de las que existen". Si pasa: guarda `unitsToAssign`, `productToAssign = productCode`, `step+1`, abre cámara.
  2. Al leer un código dentro de la ventana central: si `step === 1` compara `e.data !== productToAssign` → error "El codigo de barras no corresponde al producto" y `step=0`; si coincide → aviso "Producto escaneado correctamente, por favor escanee la ubicacion".
  3. `else` (step 0) → "ESCANEO DE UBICACION": busca el producto, decrementa `quantity`, asigna `location = e.data` y llama `assign` → `PATCH /invoices/assign`.
  4. Respuesta: si `response.error` → muestra el error; si hay respuesta → "Producto asignado correctamente", recarga, limpia `unitsToAssign` y **si `response.every(item => item.status === "done")` navega a `"Invoice"`** (fin del trabajo).
- **Datos:** `transformResponse` → `transformedData {id, productCode, productDescription, quantity: Number(quantity)-Number(physicalAssignItems), location, container: invoiceNumber, physicalQuantity, unitsPerBox}`; `quantities[i] = {id, idInvoice: invoice.id, user: user.name, productCode, quantity, container, location, unitsToAssign: ""}`. **El payload enviado a `assign` es una sola fila `quantities[i]`.**
- **Endpoints:** `GET /invoices/detail/:id/:userId`, `PATCH /invoices/assign`.
- **Defectos observados:** `onRefresh` hace `const user = getData("user")` **sin `await`** (pasa una Promise) y luego `getDataFromApi(invoice.id, user.id)` — aunque `getDataFromApi` no acepta argumentos, así que no rompe funcionalmente; `useEffect(() => getDataFromApi(), [])` sin dependencias declaradas. Ventana de escaneo: el `CodeScanner` dibuja una máscara de **300×100** (`CodeScanner.js:24-30`) mientras la validación por `bounds` usa **250×50** centrados (`left`, `top`), desalineados. **La rama `else` ("ESCANEO DE UBICACION") sólo se alcanza cuando `step` vuelve a 0**, es decir, tras un error de validación: escanear el producto correcto deja `step = 1` y el escaneo siguiente (la ubicación) vuelve a caer en la rama de validación y produce el error "El codigo de barras no corresponde al producto", reiniciando el paso; hace falta un tercer escaneo para asignar. Es un fallo de secuencia del flujo de dos pasos (el mismo patrón está en `OrderDetailScreen` y `MoveProductLocation`/`MoveProductAdmin`).

### 2.13 `TicketScreen.js` (31 líneas) — stack "Tickets"

`Tickets` (`TicketListScreen`, "Tickets"), `Mesh` (`MeshScreen`, "Malla"), `FreeLabel` (`FreeLabelScreen`, "Etiqueta Libre").

### 2.14 `TicketListScreen.js` (102 líneas) — menú de etiquetas

- **UI:** 2 `CategoryCard`: "Etiqueta Malla" (`barcode-outline`) → `navigate("Mesh")`; "Etiqueta Libre" (`pricetag`) → `navigate("FreeLabel")`.
- **Endpoint:** ninguno. (No usa `TicketList`/`TicketItem`, que quedan muertos.)

### 2.15 `MeshScreen.js` (468 líneas) — etiqueta de malla

- **Propósito:** imprimir etiquetas de malla (producto + medidas).
- **UI:** `Container` + `Header` "Crear Etiqueta Malla" + campos:
  - `loop` "Numero de etiquetas*" (numérico, habilitado siempre, por defecto `"1"`),
  - `productCode` vía `Searchbar` (placeholder "Buscar producto", icono `barcode` → escáner) + `Menu` de hasta 10 resultados (`code - name`),
  - `caliber` "Calibre*", `height` "Altura*", `width` "Ancho*", `aperture` "Apertura*", `termination` "Terminación*" — **todos deshabilitados hasta que se resuelve un producto**,
  - `Button` "Imprimir" (deshabilitado sin producto), `Notification`.
- **Formulario:** `yup` — todos requeridos ("El código de producto es requerido", "El calibre es requerido", "La altura es requerida", "El ancho es requerido", "La apertura es requerida", "La terminación es requerida", `loop` numérico ≥1 "El número de vueltas debe ser mayor a 0"). No hay validación de formato/dimensiones más allá de requerido.
- **Acciones:** buscar por texto (`getSearchProduct` → `GET /products/:code`, recorte a 10) o pulsar un resultado (`setProduct(item)`, `setValue("productCode", item.code)`); escanear (`getProduct` → `GET /store/detail/:code`, mensaje "Producto no encontrado" si null/error); imprimir → `POST /prints` con `{labels: [{labelId: 4, data: {productCode, caliber, height, width, aperture, termination}}]}` repetido `loop` veces; en éxito limpia todo el formulario.
- **Endpoints:** `GET /products/:code`, `GET /store/detail/:code`, `POST /prints`.
- **Observaciones:** hay un `Controller` **comentado** para `productCode` (líneas 237-253); el `product` sólo sirve de bandera de habilitación (no se usan sus datos para el payload); `propTypes` declara `navigation` sin uso.

### 2.16 `FreeLabelScreen.js` (413 líneas) — etiqueta libre

- **Propósito:** imprimir N etiquetas de un producto con cantidad y tipo de factura.
- **UI:** `SafeAreaView` + `ScrollView` con: `Searchbar` "Buscar producto" (icono `barcode` → escáner) + `Menu` de resultados; `Input` "Unidad de medida" (deshabilitado, autocompletado); `Input` "Cantidad" (numérico, deshabilitado sin producto); `Input` "Cantidad de etiquetas" (`loop`, por defecto `"1"`); `DropDown` "Tipo de factura" (Nacional / Importado); `DropDown` "Tamaño de etiqueta" (Normal `sm` / Grande `lg`); `Button` "Imprimir" (deshabilitado sin producto); `Notification`.
- **Datos:** producto resuelto con `getProduct(code.trim().toUpperCase())` → `GET /store/detail/:code`; de ahí `unitMeasure`; el objeto `product` completo (`product.code`, `product.description`) se usa para el payload. Búsqueda incremental con `GET /products/:code` (hasta 10).
- **Impresión:** `POST /prints` con `{labels: [{labelId: size==="sm" ? 7 : 5, data: {productCode: product.code, productName: product.description, quantity: values.quantity + " " + values.unitMeasure, invoiceType: invoiceType==="Nacional" ? "CN" : "CE"}}]}` repetido `loop` veces.
- **Endpoints:** `GET /store/detail/:code`, `GET /products/:code`, `POST /prints`.
- **Observaciones/defectos:** `unitMeasure` no está en `defaultValues` del formulario (sólo vía `setValue`), y es obligatorio para el payload; en las rutas de error se llama `setCurrentNotification("Producto no encontrado", "error")` con **dos argumentos** en lugar del objeto `{message,type}` → `currentNotification.message` queda `undefined` y el Snackbar sale vacío (líneas 111, 119); no hay validación de `quantity` > 0; el `loop` puede ser `0` → `Array(0)` → payload con `labels: []`.

### 2.17 `OrderScreen.js` (52 líneas) — stack "Ordenes"

`OrderHome` (`OrderHomeScreen`, "Ordenes"), `Order` (`OrderListScreen`, "Ordenes"), `CreateOrder` (`OrderCreateScreen`, "Crear Orden"), `OrderDetail` (`OrderDetailScreen`), `OrderDetailCreate` (`OrderDetailsCreateScreen`).

### 2.18 `OrderHomeScreen.js` (138 líneas) — menú de órdenes

- **UI:** 2 `CategoryCard`: "Ordenes Pendientes" (valor = `getReady(user.id)` → `GET /orders/ready/:id`, `.length`) → `navigate("Order")`; "Crear Orden" (`add-circle-outline`) → `navigate("CreateOrder")`.
- **Recarga:** foco + pull-to-refresh.
- **Endpoint:** `GET /orders/ready/:id`.

### 2.19 `OrderListScreen.js` (55 líneas) — órdenes asignadas al usuario

- **UI:** `OrderList` (sin `origin` → navega a `OrderDetail`).
- **Datos:** `getReady(user.id)` → `GET /orders/ready/:id`; recarga al enfocar.
- **Endpoint:** `GET /orders/ready/:id`.

### 2.20 `OrderCreateScreen.js` (129 líneas) — alta de órdenes

- **UI:** cabecera "Ordenes" + `Button` "Crear Orden" (abre `CustomDialog` con `CreateOrderForm`), `OrderList` con `origin="create"` (navega a `OrderDetailCreate`), `RefreshControl`, `Notification`.
- **Datos:** `getOrders()` → `GET /orders` (todas las órdenes) al enfocar.
- **Defectos:** no pasa `getReadyInvoices` a `OrderList` (prop requerida) → `RefreshControl.onRefresh` queda `undefined`, por lo que el pull-to-refresh de la lista no hace nada; `useEffect` con dep `[navigation, getOrderData]` donde `getOrderData` se redefine en cada render → el listener se re-suscribe continuamente.

### 2.21 `OrderDetailScreen.js` (970 líneas) — surtido/verificación de orden

- **Propósito:** asignar material de una orden a ubicaciones escaneadas e imprimir etiquetas.
- **Params:** `{ order }` (`id`, `orderNumber`, `datePackaging`, `status`, `createdAt`, `updatedAt`).
- **UI (no escaneando):**
  - Cabecera blanca "Orden: {orderNumber}" + `statusEnum[order.status]`.
  - `DataTable`: **Producto** (150), **Descripcion** (400), **Cantidad** (100), **Numero de cajas** (140, `TextInput` `quantities[i].boxQuantity`; si `status === "verified"` texto), **Unidades a surtir** (100, `TextInput` `quantities[i].unitsToAssign`; si verified, texto), **Locaciones** (150: icono `information` → `findProductLocations(productCode)`; si verified icono `printer` → `handlePrint(item)`; si no icono `barcode-scan` → `onSubmit(values, rowIndex)`).
  - Los inputs se deshabilitan para las filas distintas de `productToAssign` mientras hay un escaneo en curso.
  - Diálogo "Ubicaciones del producto" (`CustomDialog` con `ProductLocationTable`) alimentado por `GET /store/:productCode` → `response.productLocationsHasStock` con `productCode` agregado.
  - Diálogo "Imprimir etiqueta" con `ModalPrint` (tabla de 1 fila: Producto, Descripcion, Cantidad = `physicalAssignItems`, **Unidades por caja** editable, acción `send`).
  - `Notification`.
- **Validaciones en `onSubmit` (líneas 303-352):** `boxQuantity` distinto de 0/""/null → "Debe ingresar una cantidad de cajas"; `unitsToAssign` distinto de 0 → "Debe ingresar una cantidad de unidades"; `unitsToAssign > quantity` → "La cantidad ingresada es mayor a la cantidad a surtir". Después pasa a escaneo (`step+1`).
- **Flujo de escaneo:** igual que en facturas: `step === 1` valida `e.data === productToAssign`; la rama `else` construye y envía `assignMaterial` → `PATCH /orders/detail` con `{orderDetailId: product.id, productCode, locationCode: e.data, boxQuantity: parseInt(boxQuantity), units: parseInt(unitsToAssign), user: product.user}`; en error muestra `response.message`; en éxito "Material asignado correctamente" + recarga. Mismo defecto de secuencia que en `InvoiceAssignStoreScreen` (la rama de ubicación sólo se alcanza tras un fallo de validación).
- **Impresión (`handleToPrint`):** valida `unitsPerBox` no vacío/0 → "Debe ingresar una cantidad" (no valida que sea numérico). Reparte `printData.physicalAssignItems` en cajas (`floor` + resto) y envía `POST /prints` con `{labels:[{labelId: 3, data:{productCode, productName: productDescription, quantity: "<qty> <unitMeasure>", order: orderNumber, datePackaging: dd-mm-yyyy}}]}` (todas las etiquetas con `labelId 3`, sin numeración de caja).
- **Datos:** `getDetailsById(order.id, user.id)` → `GET /orders/detail/:id/:user`; `transformedData {id, productCode, productDescription, quantity: row.quantity - row.physicalAssignItems, physicalAssignItems, unitMeasure, status}`; `quantities[i] = {id, user: user.name, productCode, unitsToAssign: 0, boxQuantity: 0, unitMeasure, quantity, status}`.
- **Endpoints:** `GET /orders/detail/:id/:user`, `PATCH /orders/detail`, `GET /store/:productCode`, `POST /prints`.
- **Riesgo detectado:** `unitsPerBox` no numérico (p. ej. "abc") → `unitsPerBox = NaN` → `Array(NaN)` lanza `RangeError` (no hay validación `isNaN`).

### 2.22 `OrderDetailsCreateScreen.js` (585 líneas) — composición/edición de orden

- **Props:** `{ order }`.
- **UI:**
  - Cabecera "Orden: {orderNumber}" + `statusEnum[order.status]`; botón "Agregar material" **sólo si `order.status === "pending"`** → abre diálogo "Crear detalle de orden" con `CreateOrderDetailForm`.
  - `DataTable`: **Producto** (150), **Descripcion** (400), **Cantidad** (120), **Cantidad Surtida** (`physicalAssignItems`, 100), **Usuario Asignado** (`user`, 110), **Acciones** (150): `pencil` → diálogo "Actualizar detalle de orden" (`CreateOrderDetailForm` con `details`), `delete` → diálogo "Eliminar detalle de orden" ("¿Está seguro que desea eliminar el detalle?"), `account` → diálogo "Asignar usuario" (`AssignToUser`). Los tres botones están **deshabilitados si `item.physicalAssignItems > 0`**.
  - Diálogos + `Notification` + `RefreshControl`.
- **Datos:** `getDetails(order.id)` → `GET /orders/detail/:id` (sin usuario).
- **Acciones y endpoints:**
  - Eliminar detalle → `deleteDetail(detail.id)` → `DELETE /orders/detail/:id`.
  - Asignar usuario → `assignDetail({orderId, userId, orderDetailId, productCode})` → `PATCH /orders` (**body**: ids de orden, usuario y detalle).
  - Alta/edición de detalle la hace `CreateOrderDetailForm` (ver 3.5).
- **Observaciones:** `onRefresh` hace `setShowUpdateDialog(false)` / `setShowDialog(false)` y `setRefreshing(true)` con deps `[]` (closure sobre setters, correcto); los diálogos de alta y actualización comparten el mismo estado `productCode` de escaneo.

### 2.23 `OrderCreateScreen` → `CreateOrderForm` (componente, ver 3.6) — alta de orden

- Campos: **Numero de orden** (obligatorio), **Fecha de entrega** (`DatePickerModal` de react-native-paper-dates, por defecto hoy, se muestra con `toLocaleDateString()` y se envía como `datePackaging: date.toLocaleDateString()`), **Codigo Cliente** (`codigoCliente`), **Tipo Concepto** (`tipoConcepto`, valores `0..4`: REMISIÓN / FACTURA CONTADO AL 8% / FACTURA CONTADO AL 16% / FACTURA CREDITO AL 8% / FACTURA CREDITO AL 16%). Botón "Crear orden" → `POST /orders` con `{orderNumber, datePackaging, codigoCliente, tipoConcepto}` (nota: la clave enviada es `codigoCliente`, no `client`).
- Sólo se valida `orderNumber`.

### 2.24 `LocationsScreen.js` (27 líneas) — stack "Locaciones"

`Invoice` (`LocationsOptionsScreen`, "Locaciones") y `Move` (`MoveProductLocation`, "Mover"). No registra `MoveProductAdmin`.

### 2.25 `LocationsOptionsScreen.js` (91 líneas) — menú de locaciones

1 `CategoryCard`: "Mover de ubicación" (`swap-horizontal-outline`) → `navigate("Move")`.

### 2.26 `MoveProductLocation.js` (631 líneas) — mover producto entre ubicaciones

- **Propósito:** consultar el contenido de una ubicación y mover unidades de un producto a otra ubicación escaneada.
- **UI:**
  - `Header` "Contenido de la Ubicación: {locationCode}" (rojo) + `TextInput` "Ubicación" (`onBlur` → búsqueda) + `IconButton` `barcode-scan` (`handleScan(0, true)` = buscar ubicación).
  - Si `loading` → "Cargando…"; si no, `DataTable`: **Producto** (150), **Producto** (descripción, 400), **Locacion** (100), **Unidades** (`total`, 100), **Unidades A Mover** (150, `TextInput` `quantities[i].unitsToMove` con `disabled` si hay otro producto en proceso), **Acciones** (100: `barcode-scan` → `handleScan(rowIndex, false)`).
  - `DataTable.Pagination` local: `pageSize` 10/25/50/100, `pageSizes = [10,25,50,100]`, etiqueta `"inicio-fin de total"`; el troceado se hace **en cliente** sobre `allProducts` (todos los ítems vienen de la API).
  - `Notification`.
- **Reglas de negocio al mover (`handleScan` → `handleSubmit`):** `unitsToMove < 0` → "La cantidad a mover no puede ser menor a 0"; `=== 0` o vacío → "La cantidad a mover no puede ser 0"; `> quantity` → "La cantidad a mover no puede ser mayor a la cantidad total". (Detalle: las dos primeras comprobaciones incluyen la condición `step == 1`, que en el primer escaneo es `false`, por lo que **sólo se aplica la comprobación de vacío** en el arranque del flujo; las de `<0` y `===0` se evalúan en reescaneos.)
- **Flujo:** introducir unidades → escanear producto (`productToAssign`) → escanear ubicación destino → `moveProduct(newLocationCode)`:
  `PATCH /locations/inventory` con `{productCode, units: parseInt(unitsToMove), from: location.location, to: newLocationCode, user: user.name}` → "El producto se movió correctamente" + recarga del contenido de la ubicación destino.
- **Endpoints:** `GET /locations/inventory/:code` (con `toUpperCase().trim()`), `PATCH /locations/inventory`.
- **Defectos:** mismo enredo de `step` (la rama `else` que ejecuta el movimiento sólo se alcanza tras un error de validación); `getData("user")` en `handleChangePage`; `useEffect(() => handleChangePage(), [page,pageSize])` ejecuta una consulta/paginación extra al montar y en cada cambio de página (con `page` inicial 1, la primera llamada rebanará `allProducts` desde el índice 10 cuando haya datos — comportamiento observable a revisar); `propTypes` exige `navigation` sin uso.

### 2.27 `MoveProductAdmin.js` (617 líneas) — **código muerto**

Variante de `MoveProductLocation` sin paginación por tamaño (paginación fija de 10, `handleChangePage(page)` con `start = (page-1)*10`), con logs adicionales, sin `disabled` en los inputs y `setProductToAssign("")` en vez de `null`. Etiqueta de paginación `"1-N of N"` (en inglés). No está registrada en ningún stack.

---

## 3. Componentes (`src/components/*.js`)

| Componente | Líneas | Propósito | Props |
|---|---|---|---|
| `AssignUser.js` (`AssignToUser`) | 97 | `DropDown` con la lista de usuarios para asignar a un detalle de orden. Carga `GET users/list` en `useEffect` y mapea `{label: user.name, value: user.id}` | `onUserSelect(user)` (requerida, se llama con el id seleccionado). Estado interno: loading, users, user, showDropDown. Textos en inglés ("Select a user", "Loading...") |
| `Button.js` | 69 | Wrapper de `PaperButton` a ancho completo | `children` (string, req.), `onPress` (req.), `mode` (`contained`/`outlined`/`text`), `color` (`primary`/`accent`/`error` → aplica `styles[color]`), `borderType` (`round`/`square`), `spacing` (bool), `icon` (string), `disabled` |
| `CodeScanner.js` | 42 | Escáner de códigos de barras a pantalla completa | `scanned` (bool, req.), `handleBarCodeScanned` (func, req.), `Headers` (obj, declarada pero no usada). Ver §5 |
| `ConfigModal.js` | 36 | **Muerto.** Modal Paper con texto placeholder `"Caca"` y botones sin acción | `visible`, `onClose` |
| `Container.js` | 25 | Layout centrado (`flex:1`, padding 40, fondo `#EDF2F7`) | `children` (node, req.) |
| `CreateOrderDetailForm.js` | 333 | Formulario de alta/edición de detalle de orden, con búsqueda de producto y scanner | `handleScan` (req.), `productCode` (string, req.), `onShowNotification(message,type)` (req.), `onSubmit()` (req., refresca), `order` (obj, req.), `details` (obj, opcional → modo edición). Ver §3.5 abajo |
| `CreateOrderForm.js` | 179 | Formulario de alta de orden (número, fecha, cliente, tipo de concepto) | `onSubmit(response)` (req.), `onClose()` (req.), `onShowNotification(message,type)` (req.) |
| `CustomDialog.js` | 61 | `Portal`+`Dialog` reutilizable con título, descripción, contenido y botones Cancelar/Guardar ("Guardar"/"Cancelar") | `showDialog` (req.), `onCancel` (req.), `onSave` (req.), `title` (req.), `description` (req. — **aunque muchos usos no la pasan**), `children` (req. — varios usos no lo pasan), `showActionButtons` (default `true`) |
| `EmptyState.js` | 39 | Estado vacío con `assets/folder.png` y mensaje | `message` (req.) |
| `Header.js` | 24 | `Text` de título (24px, bold) | `children` (string, req.) |
| `Input.js` | 89 | `TextInput` de Paper con label, error y estilos del tema | `name` (req.), `label` (req.), `value` (req.), `onChange` (req.), `onBlur`, `autoCapitalize`, `autoComplete`, `returnKeyType`, `keyboardType`, `secureTextEntry`, `mode`, `disabled`, `errorText`, `touched`, `left` (elemento). **No acepta `style` en propTypes pero se le pasa `style` en varios sitios** |
| `InvoiceItem.js` | 47 | Fila de factura: "Numero de Factura: X" + estado | `invoiceNumber` (req.), `status` (req.) |
| `InvoiceList.js` | 79 | `FlatList` de facturas con `RefreshControl`, `EmptyState` "No tienes facturas asignadas" y navegación por estado | `itemList`, `getReadyInvoices` (onRefresh, req.), `refreshing` (req.). Navegación: `pending` → `InvoiceVerification`; `pending-to-locate` → `InvoiceAssignStore`; cualquier otro → **no hace nada**. `keyExtractor = item.id` |
| `Logo.js` | 23 | Imagen `assets/logo.png` centrada | — |
| `Notification.js` | 39 | `Snackbar` de 3000 ms, verde (`#38A169`) para éxito / rojo (`#E53E3E`) para error | `message` (string, req.), `clear()` (req.), `show` (bool, req.), `type` (`error`/`success`) |
| `OrderDetailTable.js` (`ProductLocationTable`) | 210 | Tabla horizontal de ubicaciones/stock. Columnas: Producto (`item.productCode`), Descripcion (`item.productDescription.slice(0,20)`), Locacion (`item.location`), Unidades (`item.total`) | `data` (array de objetos, req.). `propTypes` declarado dos veces (líneas 202 y 206) |
| `OrderItem.js` | 45 | Fila de orden: "Numero de Orden: X" + estado | `orderNumber` (req.), `status` (req.) |
| `OrderList.js` | 73 | `FlatList` de órdenes con `RefreshControl` y `EmptyState` " ordenes asignadas" (espacio inicial) | `itemList`, `getReadyInvoices` (req.), `refreshing` (req.), `origin` (req.: `"create"` → `OrderDetailCreate`; si no → `OrderDetail`) |
| `TicketItem.js` | 44 | Fila "Etiqueta de: {name}" | Declara `orderNumber`/`status` requeridos pero recibe `name` → **propTypes incorrecto** |
| `TicketList.js` | 64 | **Muerto.** `FlatList` de tickets; navega a `Mesh` si `item.type === "malla"` | `itemList`, `getReadyInvoices` (req.), `refreshing` (req.) |

### 3.5 `CreateOrderDetailForm.js` en detalle

- **Campos / `useForm`:** `id`, `code`, `quantity`, `unitMeasure`.
- **UI:** `Searchbar` "Buscar producto" (icono `barcode` → `handleScan`) + `Menu` de resultados (`row.code + " - " + row.name`, hasta 10, con `maxWidth: windowWidth-140`), `Input` "Unidad de medida" (deshabilitado, autocompletado), `Input` "Cantidad" (numérico), `Button` "Agregar"/"Actualizar" (según `details`).
- **Búsqueda:** al escribir ≥2 caracteres → `getSearchProduct(code)` → `GET /products/:code`; al elegir un ítem → `setValue("code", item.code)` + `searchProduct(item.code)` (→ `GET /store/detail/:code`) que rellena `id` (`response.productId`) y `unitMeasure`. Al montar con `productCode` (proveniente de un escaneo) ejecuta `setValue("code")` + `searchProduct`.
- **Guardado (`onSubmitForm`):**
  - Valida `quantity` distinto de `"0"` y `""` → "La cantidad debe ser mayor a 0" (comparación **de strings**, no numérica).
  - Edición (`details` presente): `updateDetail({id: details.id, quantity: parseInt(quantity)})` → `PUT /orders/detail`.
  - Alta: `addDetail({productId: parseInt(values.id), orderId: parseInt(order.id), quantity: parseInt(values.quantity), unitMeasure})` → `POST /orders/detail`.
  - En ambos casos llama `onSubmit()` (refresco en la pantalla) y limpia los campos; notificaciones "Detalle actualizado/creado correctamente" / "Error al …". **No hay `.catch` para `getProduct` con respuesta nula** (sí: muestra "Producto no encontrado").
- **PropTypes:** `productCode: PropTypes.string.isRequired` y `order: PropTypes.object.isRequired`.

---

## 4. Servicios — funciones y endpoints

> Base común: `http://<serverUrl>:4000` (interceptor). Prefijo de API: ninguno (rutas directas).
> Los helpers (`post/get/patch/put/remove`) devuelven `response.data` y en error hacen `console.error` + `reject`.

### `src/services/userService.js`
| Función | Llamada | Endpoint | Notas [backend] |
|---|---|---|---|
| `login(username, password)` | `post("/users/login", {username, password})` | **POST `/users/login`** | Ruta pública (sin token) |
| `getUserList()` | `get("users/list")` | **GET `/users/list`** | Requiere token; devuelve lista `{id, name}` |

### `src/services/productService.js`
| Función | Endpoint | Uso |
|---|---|---|
| `getProduct(code)` | **GET `/store/detail/{code}`** | Obtener producto por código (`productId`, `unitMeasure`, `code`, `description`) |
| `getSearchProduct(code)` | **GET `/products/{code}`** | Búsqueda incremental (array con `code`, `name`, `unitMeasure`) |

> **Importante / contraintuitivo:** `getProduct` (nombre de "producto") apunta a `/store/detail/:code` (kardex) y `getSearchProduct` a `/products/:code`. La constante se llama `BASE_PRODUCT_URL = "/products"` y `BASE_URL = "/store"`.

### `src/services/invoiceService.js`
| Función | Endpoint | Uso |
|---|---|---|
| `getReady(id)` | **GET `/invoices/ready/{id}`** | Contador de Home (facturas listas del usuario) |
| `getToWork(id)` | **GET `/invoices/to-work/{id}`** | Lista de trabajo en `InvoiceListScreen` |
| `assign(invoice)` | **PATCH `/invoices/assign`** | Asignar ubicación a una línea (`InvoiceAssignStoreScreen`) |
| `getDetails(id, userId)` | **GET `/invoices/detail/{id}/{userId}`** | Detalle de factura con datos por usuario |
| `captureDetails(products)` | **PATCH `/invoices/detail/capture`** | Guardar conteo físico `{products:[…]}` |

### `src/services/orderService.js`
| Función | Endpoint | Uso / forma del body |
|---|---|---|
| `getOrders()` | **GET `/orders`** | `OrderCreateScreen` (todas las órdenes) |
| `getReady(id)` | **GET `/orders/ready/{id}`** | Home + `OrderListScreen` |
| `getDetailsById(id, user)` | **GET `/orders/detail/{id}/{user}`** | `OrderDetailScreen` |
| `getDetails(id)` | **GET `/orders/detail/{id}`** | `OrderDetailsCreateScreen` |
| `updateOrder(data)` | **PUT `/orders/detail`** | **Sin uso** (código muerto) |
| `verifyOrder(id, user)` | **PATCH `/orders/detail/{id}/{user}`** | **Sin uso** |
| `assignMaterial(data)` | **PATCH `/orders/detail`** | `{orderDetailId, productCode, locationCode, boxQuantity, units, user}` |
| `createOrder(data)` | **POST `/orders`** | `{orderNumber, datePackaging, codigoCliente, tipoConcepto}` |
| `addDetail(detail)` | **POST `/orders/detail`** | `{productId, orderId, quantity, unitMeasure}` |
| `updateDetail(detail)` | **PUT `/orders/detail`** | `{id, quantity}` |
| `deleteDetail(id)` | **DELETE `/orders/detail/{id}`** | — |
| `assignDetail(detail)` | **PATCH `/orders`** | `{orderId, userId, orderDetailId, productCode}` |
| `getProductLocations(productCode)` | **GET `/store/{productCode}`** | Ubicaciones con stock (`response.productLocationsHasStock`) |
| `getLocationContent(locationCode)` | **GET `/locations/inventory/{locationCode}`** | Contenido de una ubicación |
| `moveProductLocation(values)` | **PATCH `/locations/inventory`** | `{productCode, units, from, to, user}` |

### `src/services/printService.js`
| Función | Endpoint | Uso |
|---|---|---|
| `addPrint(data)` | **POST `/prints`** | `{labels:[{labelId, data:{…}}]}`; usado desde `InvoiceDetailScreen`, `OrderDetailScreen`, `MeshScreen`, `FreeLabelScreen` |

### Etiquetas de impresión (`labelId`) observadas en el código
| labelId | Origen | Contenido del `data` |
|---|---|---|
| `1` | `InvoiceDetailScreen` (tamaño "Grande") | productName, productCode, quantity(+unidad), boxQuantity, numberOfBoxes, invoiceNumber, invoiceType (CN/CE), receiptDate |
| `8` | `InvoiceDetailScreen` (tamaño "Normal") | ídem |
| `3` | `OrderDetailScreen` | productCode, productName, quantity(+unidad), order, datePackaging |
| `4` | `MeshScreen` (malla) | productCode, caliber, height, width, aperture, termination |
| `5` | `FreeLabelScreen` (tamaño "Grande") | productCode, productName, quantity(+unidad), invoiceType (CN/CE) |
| `7` | `FreeLabelScreen` (tamaño "Normal") | ídem |

---

## 5. Escaneo de códigos de barras / QR

- **Librerías realmente usadas:** `expo-barcode-scanner` (`BarCodeScanner`) y `react-native-barcode-mask` (`BarcodeMask`) en `src/components/CodeScanner.js`.
- **`react-native-vision-camera` está en `package.json` pero no se importa en ningún fichero** (grep sin resultados) → no se usa.
- **`CodeScanner` (42 líneas):**
  - `useEffect` **sin array de dependencias** → pide permiso de cámara (`BarCodeScanner.requestPermissionsAsync()`) en cada render.
  - Renderiza `BarCodeScanner` sólo si `hasPermission === true`; `onBarCodeScanned` se anula (`undefined`) cuando `scanned === true`.
  - `style = StyleSheet.absoluteFillObject`, `shiftDistanceX/Y = 5`.
  - `BarcodeMask` blanco, **300×100**, borde 1, línea animada.
  - No se especifica `barCodeTypes` → se aceptan **todos los tipos soportados por expo-barcode-scanner** (QR, EAN-13/8, Code39/93/128, UPC, PDF417, ITF14, DataMatrix, Aztec…). En la práctica la app sólo interpreta `e.data` como **código de producto** o **código de ubicación** (texto).
- **Dónde se usa (9 ficheros):** `InvoiceAssignStoreScreen`, `OrderDetailScreen`, `OrderDetailsCreateScreen`, `SearchLocationContentScreen`, `InventorySearchScreen`, `MoveProductLocation`, `MoveProductAdmin` (muerto), `MeshScreen`, `FreeLabelScreen`.
- **Qué lee cada pantalla:**
  - `InvoiceAssignStoreScreen`: primero el **código de producto** (se compara con `productToAssign`), después el **código de ubicación** (se envía como `location`).
  - `OrderDetailScreen`: código de producto (validación) y **código de ubicación** (`locationCode` en `assignMaterial`).
  - `MoveProductLocation` / `MoveProductAdmin`: código de **ubicación** (búsqueda de contenido) y de **producto** (validación) + ubicación destino.
  - `OrderDetailsCreateScreen`, `MeshScreen`, `FreeLabelScreen`: código de **producto**.
  - `SearchLocationContentScreen`: código de **ubicación**.
  - `InventorySearchScreen`: código de **producto**.
- **Validación por posición (el "target" de la máscara):** todas las pantallas calculan
  `left = (window.width - 250)/2`, `top = window.height/2 - 125` y **sólo aceptan el escaneo si `bounds.origin.x` está en `(left, left+250)` y `bounds.origin.y` en `(top, top+50)`**. La ventana lógica (250×50) **no coincide** con la máscara visual (300×100) ni está centrada verticalmente respecto a ella (`top+50` vs. máscara de 100 de alto) → se puede escanear dentro de la máscara y que el evento se descarte, o aceptarse fuera del recuadro visible en los bordes.
- El estado `scanned` se usa para evitar lecturas repetidas (`scanned ? undefined : handler`), pero se resetea a `false` antes de cada nuevo escaneo.

---

## 6. Offline / almacenamiento, flujo de auth, config de servidor, locale y fechas

### 6.1 Almacenamiento y comportamiento offline
- **Único almacenamiento:** AsyncStorage con dos claves:
  - `user`: objeto completo de la respuesta de login (contiene como mínimo `token`, `id`, `name`, `roleId`).
  - `axios`: `{ serverUrl }` guardado por `ConfigScreen`.
- **No hay caché de datos de negocio**: facturas, órdenes, productos, ubicaciones y stock se consultan **siempre en vivo**. Sin conexión, las listas quedan vacías o se quedan con el último estado en memoria; los errores en varios sitios sólo van a `console.error`/`console.log` (`InvoiceListScreen`, `OrderListScreen`, `SearchLocationContentScreen`, `AssignUser`) sin feedback al usuario.
- **No hay cola de operaciones pendientes ni reintentos**: cada captura/escaneo es una llamada inmediata; si falla, se pierde el trabajo no confirmado (salvo lo que siga en el estado del formulario).
- **No hay `NetInfo` ni detección de conectividad**, ni indicador de "sin conexión".
- La sesión **no se restaura** al abrir la app (ver 1.3), pero el token sí permanece en disco hasta el logout.

### 6.2 Flujo de autenticación (end-to-end)
1. Arranque con `userToken = null` → stack `Login`/`Config`.
2. Usuario (opcional) "Configuracion" → `ConfigScreen` → guarda `axios.serverUrl`.
3. Login → `POST /users/login` → `storeData("user", response)` → `signIn(response.token)` → `userToken != null` → se monta `TabNavigator`.
4. Cada request añade `Authorization: Bearer <user.token>` (leído de AsyncStorage en cada petición) y el `baseURL` configurado.
5. Logout (tab User) → `removeData("user")` → `signOut()` → vuelve a `Login`.
- **Sin protección de rutas por rol**: `roleId` sólo se usa para mostrar la etiqueta en `UserScreen`; **la UI no oculta ninguna funcionalidad por rol** (crear orden, mover productos, asignar usuarios, imprimir), aunque el backend sí valida permisos por rol (`order:create`, `location:read`, etc.) → un usuario sin permiso verá errores 403 sin mensaje amigable.
- **Sin expiración/refresh**: `isTokenExpired` existe pero no se usa; un token expirado produce 401 y la pantalla muestra error genérico o se queda vacía.
- **Sin biometría/PIN**, sin "recordarme", sin recuperación de contraseña.

### 6.3 Configuración del servidor
- Pantalla `ConfigScreen` (stack `Login` → `Config`), accesible sólo desde el login.
- Campo único `serverUrl` (etiqueta "IP del servidor"), validado sólo como requerido.
- Se persiste en AsyncStorage (`axios`) y el cliente compone `http://<serverUrl>:4000`.
- No se precarga el valor existente, no se prueba la conexión, no se admite `https` ni puerto configurable, y no hay "restablecer a valores por defecto".

### 6.4 Locale, fechas y textos
- `intl` + `intl/locale-data/jsonp/en` se importan únicamente en `CreateOrderForm.js` (**locale inglés**), pero la UI es mayoritariamente en español; no hay i18n ni ficheros de traducción.
- Fechas:
  - Entrada: `DatePickerModal` (mode `single`, `saveLabel="Guardar"`, `label="Seleccione una fecha"`) con `new Date()` por defecto.
  - Transporte: `date.toLocaleDateString()` (formato dependiente del locale del dispositivo → **no determinista**, p. ej. `9/10/2024`).
  - Impresión: `ddmmyyyy(date)` divide por `-` asumiendo `YYYY-MM-DD` del backend; si el formato difiere, produce `undefined-undefined-…` o basura. Fallback literal `"1900-01-01"` cuando el campo es nulo.
  - No hay `dayjs`/`date-fns` en el cliente ni manejo de zona horaria.
- Mezcla de idiomas: textos de UI en español ("Inicia sesión…", "Cerrar sesion", "Buscar producto", "Unidades a Asignar", "Items por pagina") y en inglés ("User", "Role:", "Select a user", "Loading…", "1-N of N", títulos de props), además de typos: "Ticekt enviado correctamente" (`MeshScreen`, `FreeLabelScreen`), "Buscar Iventario" (`InventoryScreen`), " ordenes asignadas" (`OrderList`), "Guardar Factura"/"Discrepancia".

---

## 7. Enums de estado y reglas de negocio embebidas

### 7.1 Estados
**Definidos en la app** (`src/data/status.js`):
| Clave | Etiqueta mostrada |
|---|---|
| `pending` | "Pendiente de capturar" |
| `captured` | "Esperando verificacion" |
| `verified` | "Surtida" |
| `pending-to-locate` | "Pendiente de ubicar" |

**Estados que el código compara o espera y que NO están en el enum** (aparecerían en blanco en la cabecera):
| Estado | Dónde aparece | Efecto en la UI |
|---|---|---|
| `discrepancy` | `InvoiceDetailScreen` (celdas editables vs. texto; icono `alert`) | `statusEnum["discrepancy"]` = `undefined` en cabecera |
| `done` | `InvoiceAssignStoreScreen:200-202` → `response.every(item => item.status === "done")` | Controla la navegación de vuelta a `Invoice` |
| `assigned` / `captured` | **[backend]** en filtros de facturas | — |
| `verified` (órdenes) | `OrderDetailScreen:702,749,818` | Filas de solo lectura + botón imprimir |

Estados que **dirigen la navegación**:
- `InvoiceList.actionOnRow`: `pending` → detalle de captura; `pending-to-locate` → asignación de ubicación; cualquier otro → **fila sin acción** (no navega).
- `OrderDetailsCreateScreen`: botón "Agregar material" sólo si `order.status === "pending"`.

### 7.2 Reglas de negocio embebidas en el cliente
1. **Conteo físico de factura (captura):** por línea, capturar `unitsPerBox` y `physicalQuantity`; **`unitsPerBox` no puede ser 0** (validación antes de `PATCH /invoices/detail/capture`); una vez `status === "captured"` (o `discrepancy`) la fila pasa a solo lectura y muestra los valores; la confirmación advierte que la factura ya no será editable sin un administrador.
2. **Impresión derivada del conteo:** `cajas = floor(cantidadFísica / unidadesPorCaja)` + etiqueta extra con el resto; `numberOfBoxes` = total de etiquetas. No se valida `unidadesPorCaja` numérico (riesgo de `NaN`).
3. **Asignación de ubicaciones a factura:** no se puede asignar 0 unidades; no se pueden asignar más unidades que las existentes (`quantity = cantidad - physicalAssignItems`); el código escaneado debe coincidir con el del producto seleccionado; al asignar, la cantidad local se decrementa (`quantity - unitsToAssign`) antes de enviar la petición; cuando **todas** las líneas responden `status === "done"` se navega de vuelta a la lista de facturas.
4. **Surtido de orden (material):** obligatorio indicar **número de cajas** y **unidades a surtir**; `unitsToAssign <= quantity`; el producto escaneado debe coincidir; se envía `{orderDetailId, productCode, locationCode, boxQuantity, units, user}`; tras el éxito se recarga el detalle.
5. **Composición de orden (admin):** sólo se pueden agregar/editar/eliminar detalles o asignar usuario si `physicalAssignItems == 0` (si ya hay material surtido, los botones están deshabilitados); el alta de la orden sólo requiere `orderNumber` (fecha, cliente y tipo de concepto se envían aunque sean por defecto); `quantity` del detalle debe ser > 0.
6. **Mover entre ubicaciones:** `0 < unitsToMove <= total` de esa línea; se requiere escanear el producto y luego la ubicación destino; el payload envía `from` (la ubicación de origen de la fila) y `to` (código escaneado).
7. **Identificación de usuario en operaciones:** casi todas las mutaciones envían `user: user.name` (nombre, no id) —excepto `assignDetail`, que envía `userId`— lo que implica que el backend resuelve por nombre en varios flujos.
8. **Roles:** 1 = Admin, 2 = Operaciones, 3 (u otro) = Almacén. Sólo informativo en el cliente.

### 7.3 Reglas de formato observadas
- Códigos de producto/ubicación se normalizan con `trim().toLocaleUpperCase()` en varios puntos (pero no en todos: `InventorySearchScreen.getProductLocations` usa el texto sin normalizar).
- `parseInt` sin radix explícito en varias conversiones (`CreateOrderDetailForm`, `OrderDetailScreen`, `MoveProductLocation`).
- Comparaciones de cantidades mezclan `string` y `number` (`values.quantity === "0"`, `parseInt(...) > parseInt(...)`).

---

## 8. Huecos, defectos y TODOs relevantes

**No hay comentarios `TODO`/`FIXME` explícitos** en el código (grep sin resultados); los huecos se deducen del propio código.

### 8.1 Funcionales / de flujo
1. **Sesión no persistente entre arranques** (`bootstrapAsync` vacío en `App.js:61-72`): el `user` está en disco pero siempre se pide login.
2. **Sin manejo de token expirado / 401** (`utils/jwt.js` sin usar; sin interceptor de respuesta).
3. **Máquina de estados de escaneo rota (2 pasos):** en `InvoiceAssignStoreScreen`, `OrderDetailScreen`, `MoveProductLocation` y `MoveProductAdmin` el `step` no llega nunca a 2; la rama de "escaneo de ubicación / asignación" sólo se ejecuta cuando `step` volvió a 0 por un error previo → el segundo escaneo produce el mensaje "El codigo de barras no corresponde al producto" y hace falta escanear tres veces. *(Observación de código; no verificada en dispositivo.)*
4. **Ventana de validación por `bounds` (250×50) desalineada con la máscara visual (300×100)** en las 9 pantallas con escáner.
5. **División por cero / `NaN`** en la impresión: `handleToPrint` (`OrderDetailScreen`) y `handlePrint` (`InvoiceDetailScreen`) no validan que "unidades por caja" sea numérico → `Array(NaN)` puede lanzar `RangeError`.
6. **`assign` de factura envía `quantity` ya decrementada** localmente y sólo una línea por petición; no hay control de concurrencia ni bloqueo de fila.
7. **`InvoiceDetailScreen`**: `useEffect` con callback `async`; `getDataFromApi` invocado con argumentos que ignora; discrepancia sin endpoint (sólo cierra el diálogo, aunque el backend ofrece `PATCH /invoices/:id` con permiso `invoice:approve`).
8. **`FreeLabelScreen`**: notificaciones construidas con firma incorrecta (`setCurrentNotification("msg","error")`) → Snackbar vacío; `quantity` sin validar; `loop` puede ser 0 → `labels: []`.
9. **`OrderCreateScreen`**: no pasa `getReadyInvoices` a `OrderList` → pull-to-refresh sin efecto; `useEffect` re-suscrito en cada render.
10. **`OrderDetailCreateScreen`**: `onRefresh` incluye llamadas redundantes a `setShowDialog(false)`; el escaneo de producto sólo rellena `productCode` y depende de que el usuario confirme en el formulario.
11. **Estados no cubiertos en `statusEnum`** (`discrepancy`, `done`, `assigned`) → cabeceras en blanco y filas sin acción en la lista de facturas.
12. **Sin feedback de error** en varias rutas (`InvoiceListScreen`, `OrderListScreen`, `SearchLocationContentScreen`, `AssignUser` sólo hacen `console.error`).
13. **Sin control de permisos en la UI** (el backend sí los aplica) → errores de autorización silenciosos o confusos.

### 8.2 Técnicos / de calidad
14. **Código muerto**: `MoveProductAdmin.js` (617 líneas), `TicketList.js`, `TicketItem.js`, `AssignLocationCard.js`, `ConfigModal.js` (con texto de prueba "Caca"), `utils/jwt.js`, `utils/wait.js`, deps `react-native-vision-camera`/`reanimated`/`expo-status-bar`.
15. **Dependencias no declaradas**: `prop-types`, `@expo/vector-icons`.
16. **PropTypes incorrectos**: `TicketItem` (recibe `name`, exige `orderNumber`/`status`), `CustomDialog` (`description`/`children` requeridos y a menudo ausentes), `Input` (recibe `style` no declarado), `OrderDetailTable` (bloque `propTypes` duplicado), pantallas que declaran `navigation: PropTypes.func` sin recibirlo o recibirlo como objeto.
17. **`console.log` abundantes** (≈120 en total; `CreateOrderDetailForm` 16, `MoveProductAdmin` 15, `FreeLabelScreen` 11).
18. **Sin tests, sin TypeScript, sin tipos de API centralizados**; los DTOs sólo existen implícitamente.
19. **Sin i18n**, textos mezclados ES/EN y typos visibles al usuario.
20. **`<Provider>` sin `theme`** → el tema propio (`core/theme.js`) no se aplica a los componentes internos de Paper.
21. **`baseURL` http + puerto 4000 hardcodeado**; sin configuración TLS y con posible bloqueo de cleartext en Android moderno.
22. **`axios` sin timeout** (`timeout: 0`).
23. **`tabBarVisible` deprecado**; iconos de tab sólo definidos para Home/User.
24. **Nombres de ruta confusos** (`LocationsScreen` registra la pantalla inicial como `"Invoice"`; tab y pantalla `"Tickets"` colisionan).
25. **`app.json` referencia `./assets/favicon.png` inexistente** (bloque `web`).
26. **Sin `.env`, sin configuración de API por entorno**; la URL del backend se introduce a mano.

---

## 9. Reescritura a Kotlin Multiplatform / Compose Multiplatform con core Kotlin compartido

> Sección propositiva basada en los hechos anteriores (no hay nada de KMP en el repo).

### 9.1 Qué es "core compartido" aquí (candidatos claros)
Del inventario se deduce un núcleo de dominio pequeño, ya aislado de la UI, fácil de portar tal cual:
1. **Modelos/DTOs** hoy implícitos: `User{id,name,roleId,token}`, `Invoice{id,invoiceNumber,invoiceType,receiptDate,status}`, `InvoiceDetail{id,productCode,productName,productDescription,quantity,physicalQuantity,physicalAssignItems,unitsPerBox,boxQuantity,unitMeasure,status}`, `Order{id,orderNumber,datePackaging,status}`, `OrderDetail{…}`, `Product{productId,code,name,description,unitMeasure}`, `LocationContent{productCode,productDescription,location,total}`, `Label{labelId,data}`. → `kotlinx.serialization` `@Serializable` en `shared/commonMain`.
2. **Enum de estados** (`data/status.js`) ampliado y con etiquetas: `Pending`, `Captured`, `Verified`, `PendingToLocate`, `Discrepancy`, `Assigned`, `Done` + mapeo a texto es-MX (hoy `statusEnum` es incompleto).
3. **Reglas de negocio** hoy dispersas en pantallas, que deberían ser funciones puras testeables:
   - validación de captura (`unitsPerBox != 0`, cantidades ≥ 0);
   - cálculo de etiquetas/cajas (`floor`, resto, `numberOfBoxes`) — hoy duplicado en `InvoiceDetailScreen` y `OrderDetailScreen`;
   - validación de asignación (`unitsToAssign in 1..quantity`) y de movimiento (`0 < units <= total`);
   - máquina de estados del escaneo (producto → validación → ubicación) **corregida** en un `sealed class ScanState` compartido (hoy replicada con bugs en 4 pantallas);
   - mapeo `labelId` y construcción del payload `{labels:[…]}` (hoy copiado en 4 sitios con constantes mágicas 1/3/4/5/7/8).
4. **Contratos de API** (los 20 endpoints de §4) como interfaces `ApiClient` con Ktor; mismo esquema de URL `http://<host>:<port>` configurable (idealmente host+puerto+esquema, no sólo host).
5. **Fecha/normalización**: conversión `yyyy-MM-dd → dd-MM-yyyy`, normalización `trim().uppercase()` de códigos, formateo de fecha de entrega determinista (`kotlinx-datetime` + patrón fijo en lugar de `toLocaleDateString()`).

### 9.2 Mapeo de capas y librerías

| Hoy (React Native) | Destino KMP/CMP |
|---|---|
| `utils/axios.js` (axios + interceptor) | **Ktor Client** (OkHttp/Darwin) con `defaultRequest` (baseUrl + `Authorization: Bearer`) y plugin de auth/refresh; configurar **timeouts** (hoy `0`) y manejo de 401 |
| `utils/storage.js` (AsyncStorage, claves `user`/`axios`) | `multiplatform-settings` (Keychain/EncryptedSharedPreferences para el token) o DataStore; claves y `AppConfig(host,port,scheme)` en shared |
| `utils/jwt.js` (sin uso) | Validación de `exp` en shared + renovación/logout automático (verificar si el backend emite refresh token; **[backend]** sólo se observó `jsonwebtoken` en login) |
| `context/authContext.js` + reducer de `App.js` | `AuthRepository`/`SessionManager` en shared (StateFlow<SessionState>: `Loading/Restored/LoggedOut/LoggedIn`) con **restauración de sesión al arranque** (hoy no existe) |
| `@react-navigation/native-stack` / `bottom-tabs` | **Compose Navigation**: `NavHost` + rutas tipadas; replicar la jerarquía de §1.3 (tabs Home/User visibles; Invoices/Orders/Tickets/Location/Inventory como destinos ocultos) |
| `react-native-paper` (Card, DataTable, Dialog, Snackbar, Searchbar, Menu, IconButton, TextInput, Dropdown) | **Material 3 `androidx.compose.material3`** (`Scaffold`, `Card`, `AlertDialog`, `Snackbar`, `SearchBar`, `DropdownMenu`, `TextField`, `LazyColumn`) + componentes propios |
| `react-hook-form` + `yup` | `StateFlow` de estado de formulario + validadores en shared (los `yup` de Login/Config/Malla se traducen 1:1) o librerías CMP de formularios |
| `expo-barcode-scanner` + `react-native-barcode-mask` | `expect/actual`: CameraX + ML Kit (Android) / AVFoundation + Vision (iOS); overlay propio. La **lógica de validación por `bounds` en la ventana central debe compartirse** como función pura (`isScanInsideTarget(bounds, target)`) y alinearse con el overlay real |
| `react-native-paper-dates` (`DatePickerModal`) | `androidx.compose.material3.DatePicker` / `DatePickerDialog` (o lib CMP) |
| `react-native-vision-camera` (no usado) | no portar |
| `intl`/`toLocaleDateString` | `kotlinx-datetime` + formatos explícitos; i18n con recursos por locale (`es-MX`) |
| `POST /prints` (impresión delegada al backend) | **Se reutiliza tal cual**: el cliente sólo construye `labelId + data`; no hay integración de impresora en el cliente |

### 9.3 Qué habría que cambiar/corregir al portar (checklist)
1. **Corregir la máquina de estados del escaneo** antes de portarla (o rediseñarla: un único escaneo que resuelva "producto u ubicación" por prefijo/formato, o un `ScanTarget` explícito por paso).
2. **Unificar la ventana de escaneo** (overlay y validación con las mismas dimensiones y centro).
3. **Completar el enum de estados** y definir el comportamiento de filas para `discrepancy`/`done`/`assigned` (hoy filas sin acción y cabeceras vacías).
4. **Restaurar sesión al arrancar** y **manejar expiración/401**.
5. **Centralizar el manejo de errores** con mensajes al usuario por código HTTP y por `error/message` de la API (varios endpoints de factura ya devuelven `{error}` / `{message}`).
6. **Validación numérica fuerte** (`toIntOrNull`) en todos los campos de cantidad, y prohibir `unitsPerBox == 0` también en impresión.
7. **Formato de fecha determinista**; eliminar `toLocaleDateString()` como formato de transporte y el parseo `split("-")` sin validación.
8. **Config de servidor completa** (esquema + host + puerto), prellenado, con botón de prueba de conectividad y persistencia segura; Android requerirá permitir cleartext o mover a HTTPS.
9. **Roles/permisos en la UI** (ocultar/deshabilitar acciones según `roleId`/permisos para evitar 403 silenciosos) — el backend ya expone permisos (`role:read` → `getRoleWithPermissions` **[backend]**).
10. **Enviar identificadores en lugar de nombres** (`user.id` en lugar de `user.name`) o confirmar el contrato con backend antes de portar.
11. **Eliminar código muerto** al portar (`MoveProductAdmin`, `TicketList`, `TicketItem`, `AssignLocationCard`, `ConfigModal`, `wait`, `jwt` sin uso).
12. **Definir estrategia offline**: hoy no hay caché; si se quiere funcionar sin red (almacén/almacenes con mala cobertura) hay que introducir `SQLDelight`/Room-KMP y una cola de operaciones (captura, asignación, movimiento) con resolución de conflictos, además de `last-write-wins`/idempotencia en el backend.
13. **Tests**: no existen; el core compartido debería nacer con tests unitarios de las reglas de §9.1.3 (cajas/etiquetas, validaciones, mapeos de estado) — es la mayor ganancia de extraer el core.
14. **Compatibilidad de contratos**: revisar en el backend los DTOs reales (`BASE/backend/src/dto/*.ts`) antes de fijar los modelos compartidos; el cliente hoy no valida nada de lo que recibe.

### 9.4 Riesgos / incógnitas para la reescritura (no verificables sólo con este repo)
- **Contrato exacto** de varias respuestas: `GET /invoices/ready|to-work/:id` (se asume `id`+`invoiceNumber`+`status`), `GET /orders` (lista), respuesta de `PATCH /invoices/assign` (se asume array con `status: "done"`), `POST /orders` (¿devuelve la orden creada? `CreateOrderForm` hace `onSubmit(response)` sin usar campos).
- **Semántica de `location: ""`** enviada en `captureDetails` (¿el backend la ignora?).
- **Uso real de `physicalQuantity` vs `physicalAssignItems`**: coexisten nombres distintos para conceptos similares según pantalla.
- **Puerto 4000 y http** en producción (¿hay reverse proxy/HTTPS?).
- **Discrepancias**: no hay flujo de resolución en la app (sólo un diálogo informativo).

---

### Anexo A — Árbol de ficheros (sin `node_modules`)

```
BASE/mobile-app/
├── App.js
├── app.json, eas.json, babel.config.js, .eslintrc.json, .gitignore
├── package.json, package-lock.json, yarn.lock
├── assets/{folder.png, icon.png, logo.png, splash.png}
└── src/
    ├── components/  (AssignLocationCard, AssignUser, Button, CodeScanner, ConfigModal, Container,
    │                 CreateOrderDetailForm, CreateOrderForm, CustomDialog, EmptyState, Header, Input,
    │                 InvoiceItem, InvoiceList, Logo, Notification, OrderDetailTable, OrderItem,
    │                 OrderList, TicketItem, TicketList)
    ├── context/authContext.js
    ├── core/theme.js
    ├── data/status.js
    ├── screens/     (ConfigScreen, FreeLabelScreen, HomeScreen, InventoryOptionsScreen,
    │                 InventoryScreen, InventorySearchScreen, InvoiceAssignStoreScreen,
    │                 InvoiceDetailScreen, InvoiceListScreen, InvoiceScreen, LocationsOptionsScreen,
    │                 LocationsScreen, LoginScreen, MeshScreen, MoveProductAdmin, MoveProductLocation,
    │                 OrderCreateScreen, OrderDetailScreen, OrderDetailsCreateScreen, OrderHomeScreen,
    │                 OrderListScreen, OrderScreen, SearchLocationContentScreen, TicketListScreen,
    │                 TicketScreen, UserScreen)
    ├── services/    (invoiceService, orderService, printService, productService, userService)
    └── utils/       (axios, jwt, storage, wait)
```

### Anexo B — Grafos de navegación (rutas por `navigate`)

```
Login ──▶ Config
Login ──▶ (login OK) tabs
Home ──▶ Invoices | Orders | Tickets | Location | Inventory
InvoiceList ──▶ InvoiceVerification (status=pending) | InvoiceAssignStore (status=pending-to-locate)
InvoiceAssignStore ──▶ Invoice (cuando todas las líneas responden done)
OrderHome ──▶ Order | CreateOrder
OrderCreate ──▶ OrderDetailCreate (OrderList origin="create")
OrderList ──▶ OrderDetail
OrderDetailCreate ──▶ (diálogos) Crear/Actualizar/Eliminar detalle, Asignar usuario
TicketList ──▶ Mesh | FreeLabel
InventoryOptions ──▶ SearchInventory | Search
LocationsOptions ──▶ Move
Tab User ──▶ (logout) Login
```
