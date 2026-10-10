# App: Kotlin Multiplatform para Android e iOS
> **Normativo.** Cómo se organiza la app de piso. Última revisión: 2026-10-09.

## 1. Qué hace la app

La app es para el **operador de piso**: recibe mercancía, la ubica, surte órdenes, mueve existencias y
escanea etiquetas. No administra usuarios, catálogo ni reportes; eso es de la web
([plan/PLAN-REHACER.md](../plan/PLAN-REHACER.md), §2).

Cada pantalla corresponde a un flujo de [flujos/](../flujos/README.md). La app no tiene reglas propias:
muestra lo que la API responde y envía lo que el operador hace.

## 2. Estructura

Un proyecto Gradle con un módulo `shared` (KMP) y los objetivos Android e iOS.

```
app/
  shared/
    commonMain/
      core/            Red, sesión, errores, ajustes. Compartido por todas las funcionalidades.
      feature/
        auth/          Login, sesión, cambio de contraseña.
        receiving/     Facturas, captura, ubicar.
        fulfillment/   Órdenes, empaque.
        transfers/     Traslados.
        labels/        Escaneo y consulta de etiquetas.
        inventory/     Consulta de existencias.
    androidMain/       Cámara y permisos de Android
    iosMain/           Cámara y permisos de iOS
  androidApp/
  iosApp/              Proyecto Xcode (se crea en la fase 10)
```

**Regla de localidad (vertical slice).** Una funcionalidad tiene sus pantallas, su estado, sus
llamadas y sus modelos dentro de su carpeta `feature/<nombre>`. Una funcionalidad **no importa** de
otra; si dos necesitan lo mismo, va a `core/`.

## 3. Dominio compartido

Si D6 se aprueba, las reglas que la app necesita (validación de cantidades, transiciones de estado,
formato de códigos de ubicación) vienen del módulo `stock-domain`, no se reescriben en la app. Si D6
se rechaza, la app mantiene solo las validaciones de formulario y deja las reglas a la API.

## 4. Red y sesión

- Cliente HTTP con Ktor Client (solo cliente; ver [stack.md](stack.md)).
- El token de acceso vive en el almacén seguro del sistema (Keychain en iOS, Keystore en Android),
  nunca en texto plano.
- Un `401` dispara un refresh; si el refresh falla, la sesión se cierra. Un refresh reutilizado
  invalida la sesión completa ([flujos/autenticacion-y-permisos.md](../flujos/autenticacion-y-permisos.md) §3).
- Todas las escrituras que pueden reintentarse llevan `Idempotency-Key` generada en la app y guardada
  hasta que la API confirma.

## 5. Escáner

- Cámara con la API nativa de cada plataforma (CameraX en Android, AVFoundation en iOS), detrás de una
  interfaz común en `core/`.
- La conversión de coordenadas del escáner se prueba en dispositivo real. Una prueba en simulador no
  cuenta como verificación del escáner.

## 6. Caché sin conexión

**Fuera de alcance de la primera entrega.** Si se necesita trabajar sin red, se decide en un ADR
propio. Hasta entonces, si no hay red, la app muestra el estado y no encola escrituras.

## 7. Pruebas

| Nivel | Qué prueba | Dónde |
|---|---|---|
| Unidad | Formularios, estados, mapeo de respuestas | `commonTest` |
| Red | Contrato contra la API en Compose | `commonTest` con servidor simulado generado desde el contrato |
| Dispositivo | Escáner, cámara, flujo de recepción completo | Manual, documentado en [guia/lo-no-verificado.md](../guia/lo-no-verificado.md) |

## 8. Lo que no hace la app

- No calcula existencias ni saldos.
- No guarda reglas de permisos: oculta opciones según lo que la API informa, pero la API decide.
- No se comunica con el ERP ni con impresoras directamente.
