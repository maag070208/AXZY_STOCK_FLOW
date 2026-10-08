#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# COMPILA E INSTALA LA APP ANDROID
#
#   ./scripts/movil-android.sh              compila el APK
#   ./scripts/movil-android.sh instalar     además lo instala en el dispositivo
#
# **`mobile-app` ES SU PROPIO BUILD DE GRADLE**, separado del monorepo, con
# `shared/` (la biblioteca KMP) y `androidApp/` (el APK) dentro. No es un submódulo:
# un directorio no puede ser raíz de dos builds de Gradle, y `include(":mobile-app")`
# en la raíz haría que el IDE no pudiera sincronizarlo.
#
# Con AGP 9, `com.android.application` y el plugin de KMP no pueden coexistir en el
# MISMO build, y el fallo tumba la configuración de todo el proyecto. Por eso el
# móvil vive aparte. Ver PLAN_ALMACEN_INTEGRAL.md §10-bis y el README del móvil.
#
# **ESTE SCRIPT ESTUVO ROTO Y ERA UN FALLO MÍO.** La reestructuración eliminó el
# `gradlew` de `androidApp` (colgaba de `../../gradlew` y daba
# `ClassNotFoundException: GradleWrapperMain`), pero no actualicé este script, así
# que durante un tiempo falló con «./gradlew: No such file or directory». Los scripts
# de arranque son parte de lo que se entrega: si se mueve una carpeta, hay que
# buscarlos.
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ/mobile-app"

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$RAIZ/.gradle-home}"
export KONAN_DATA_DIR="${KONAN_DATA_DIR:-$RAIZ/.konan-home}"

echo "Compilando el APK…"
./gradlew :androidApp:assembleDebug --console=plain

APK="$(find "$RAIZ/mobile-app/androidApp/build/outputs/apk" -name '*.apk' | head -1)"

if [ -z "$APK" ]; then
  echo "✗ No se generó ningún APK" >&2
  exit 1
fi

echo
echo "✓ APK: $APK ($(stat -f%z "$APK" 2>/dev/null || stat -c%s "$APK") bytes)"

if [ "${1:-}" = "instalar" ]; then
  if ! command -v adb >/dev/null 2>&1; then
    echo "✗ No hay 'adb' en el PATH. Instala las platform-tools del SDK." >&2
    exit 1
  fi
  if [ -z "$(adb devices | sed -n '2p')" ]; then
    echo "✗ No hay ningún dispositivo o emulador conectado." >&2
    echo "  Conecta un teléfono con depuración USB, o arranca un emulador." >&2
    exit 1
  fi
  echo "Instalando en el dispositivo…"
  adb install -r "$APK"
  echo "✓ Instalada"
fi
