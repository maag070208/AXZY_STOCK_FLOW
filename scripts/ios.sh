#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# COMPILA EL FRAMEWORK DE iOS
#
#   ./scripts/ios.sh
#
# El proyecto Xcode (`mobile-app/iosApp`) se abre a mano: su `pbxproj` se escribió
# sin abrirlo en Xcode nunca, así que **puede no abrir bien**. El framework de
# Kotlin sí compila, que es lo que valida el código compartido y el `actual` de
# AVFoundation.
#
# PENDIENTE Y SIN VERIFICAR: la cámara en un iPhone real. Es lo primero que hay
# que probar en dispositivo.
#
# **Este script apuntaba al proyecto `:mobile-app` del monorepo, que ya no existe.**
# Tras la reestructuración el móvil es su propio build, así que el módulo pasó a
# llamarse `:shared` y se compila DESDE `mobile-app/`. Es el mismo desfase que tuvo
# `movil-android.sh`.
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ/mobile-app"

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$RAIZ/.gradle-home}"
export KONAN_DATA_DIR="${KONAN_DATA_DIR:-$RAIZ/.konan-home}"

echo "Compilando el framework para el simulador de iOS…"
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 \
  -PenableIosTargets=true --console=plain

echo
echo "✓ Framework en mobile-app/shared/build/bin/iosSimulatorArm64/debugFramework/MobileApp.framework"
echo
echo "Para abrirlo en un simulador: abre mobile-app/iosApp/iosApp.xcodeproj en Xcode."
