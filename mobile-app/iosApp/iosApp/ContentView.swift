import MobileApp
import SwiftUI
import UIKit

/// Puente a Compose Multiplatform.
///
/// `MainViewControllerKt` es el nombre que genera Kotlin/Native para las funciones de
/// nivel superior del archivo `MainViewController.kt`. El framework se llama
/// `MobileApp` (ver `baseName` en mobile-app/build.gradle.kts).
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            // Compose dibuja también bajo la isla/notch: sin esto queda una banda
            // blanca que además desplaza la vista de cámara y, con ella, la máscara.
            .ignoresSafeArea()
    }
}
