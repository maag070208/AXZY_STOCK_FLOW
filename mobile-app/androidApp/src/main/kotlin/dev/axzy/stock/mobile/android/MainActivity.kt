package dev.axzy.stock.mobile.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.axzy.stock.mobile.App

/**
 * Punto de entrada Android.
 *
 * NO TIENE UI PROPIA: monta `App()`, que es la interfaz compartida con iOS. Una
 * pantalla escrita aquí sería una pantalla que iOS no tiene, y la Fase 6 exige el
 * mismo flujo en las dos plataformas.
 *
 * `enableEdgeToEdge()` antes de `super.onCreate`: con el orden inverso, el sistema
 * dibuja las barras con el tema antiguo y la primera composición sale desplazada.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // ADJUST_RESIZE: el escáner abre un campo de unidades y, sin esto, el teclado
        // tapa la vista de cámara y el botón de confirmar. En un almacén se captura de
        // pie y con una mano: tener que cerrar el teclado para ver el botón es un
        // defecto de uso, no de estética.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        setContent { App() }
    }
}

@Preview
@Composable
fun AppPreview() {
    App()
}
