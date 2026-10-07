package dev.axzy.stock.mobile

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Puente entre Compose Multiplatform y SwiftUI.
 *
 * `MainViewController()` es lo que llama `ContentView.swift` de `iosApp/`. El nombre
 * lo impone Kotlin/Native: una función de nivel superior en el archivo
 * `MainViewController.kt` del paquete `dev.axzy.stock.mobile` se publica en
 * Objective-C como `MainViewControllerKt.MainViewController()`, y así se importa
 * desde el framework `MobileApp`.
 *
 * LO QUE NO HACE: no hay UI propia de iOS. Monta `App()`, la MISMA interfaz que
 * Android. La única diferencia entre las dos plataformas está en
 * `core/camera/BarCodeScannerView` (CameraX + ML Kit frente a AVFoundation), que es
 * lo que de verdad no se puede compartir.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
