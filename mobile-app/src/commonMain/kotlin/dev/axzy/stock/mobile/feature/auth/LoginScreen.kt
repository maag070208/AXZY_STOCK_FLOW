package dev.axzy.stock.mobile.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.axzy.stock.mobile.core.theme.AppColors
import dev.axzy.stock.mobile.core.ui.AppButton
import dev.axzy.stock.mobile.core.ui.AppCard
import dev.axzy.stock.mobile.core.ui.AppPasswordField
import dev.axzy.stock.mobile.core.ui.AppTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Inicio de sesión.
 *
 * PATRÓN: MVVM simple, NO MVI. Lo pide §3.4 y el criterio es explícito: MVI donde
 * hay una máquina de estados real; aquí no hay ninguna secuencia que romper, solo
 * dos campos y un botón. Montar intents sellados para esto sería ceremonia sin
 * beneficio, y la sobreingeniería también es un defecto.
 *
 * SIN `stock-network` NO HAY AUTENTICACIÓN REAL: se valida que los campos no estén
 * vacíos y se entra. Es deliberado que **no** se finja un token: el día que llegue
 * el cliente HTTP, lo único que cambia es el cuerpo de `onClick`, y mientras tanto
 * nadie puede creer que esto autentica de verdad — la pantalla lo dice.
 */
@Composable
fun LoginScreen(onLoggedIn: (String) -> Unit) {
    var usuario by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var cargando by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Almacén Integral", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Control de inventario de piso",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp, bottom = 28.dp),
        )

        AppCard(modifier = Modifier.fillMaxWidth()) {
            AppTextField(
                value = usuario,
                onValueChange = {
                    usuario = it
                    error = null
                },
                label = { Text("Usuario") },
                placeholder = { Text("jperez") },
                singleLine = true,
                isError = error != null,
            )
            Spacer(Modifier.height(12.dp))
            AppPasswordField(
                value = password,
                onValueChange = {
                    password = it
                    error = null
                },
                label = { Text("Contraseña") },
                isError = error != null,
            )

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error!!,
                    color = AppColors.Danger,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(20.dp))
            AppButton(
                text = "Entrar",
                onClick = {
                    scope.launch {
                        cargando = true
                        // La espera es para que el estado de carga del botón sea
                        // visible y para recordar que aquí habrá una llamada de red.
                        // Un `delay` de mentira es mejor que un botón que nunca
                        // muestra que está trabajando.
                        delay(200)
                        val fallo = if (usuario.isBlank() || password.isBlank()) {
                            "Escribe usuario y contraseña"
                        } else {
                            null
                        }
                        error = fallo
                        cargando = false
                        if (fallo == null) onLoggedIn(usuario.trim())
                    }
                },
                loading = cargando,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(
            text = "Aún sin cliente HTTP: la sesión es local y los datos son de ejemplo.",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextFaint,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
