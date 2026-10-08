package dev.axzy.stock.mobile.feature.labels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.axzy.stock.mobile.AppScaffold
import dev.axzy.stock.mobile.core.data.AppRepositories
import dev.axzy.stock.mobile.core.data.LabelFieldView
import dev.axzy.stock.mobile.core.data.LabelTemplateView
import dev.axzy.stock.mobile.core.data.PrintJobView
import dev.axzy.stock.mobile.core.theme.AppColors
import dev.axzy.stock.mobile.core.ui.AppButton
import dev.axzy.stock.mobile.core.ui.AppButtonVariant
import dev.axzy.stock.mobile.core.ui.AppCard
import dev.axzy.stock.mobile.core.ui.AppRowCard
import dev.axzy.stock.mobile.core.ui.AppTextField
import dev.axzy.stock.mobile.core.ui.DomainStatusChip
import dev.axzy.stock.mobile.core.ui.EmptyState
import dev.axzy.stock.mobile.core.ui.LoadingState
import dev.axzy.stock.mobile.core.ui.SectionLabel
import kotlinx.coroutines.launch

/**
 * Etiquetas: elegir plantilla, rellenar sus campos y mandar a imprimir.
 *
 * EL FORMULARIO SE GENERA DESDE `fieldSchema`, no se dibuja a mano.
 * El contrato devuelve `fieldSchema` en la plantilla precisamente para esto: si la
 * plantilla cambia en el servidor, el móvil pide los campos que hay sin necesidad de
 * publicar una versión nueva de la app. Cablear los campos a mano garantizaría que
 * una plantilla nueva no se puede usar desde el móvil hasta la siguiente release.
 */
@Composable
fun LabelsScreen(
    repositories: AppRepositories,
    onBack: () -> Unit,
    onOpenJobs: () -> Unit,
) {
    var plantillas by remember { mutableStateOf<List<LabelTemplateView>?>(null) }
    var seleccionada by remember { mutableStateOf<LabelTemplateView?>(null) }
    var valores by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var copias by remember { mutableStateOf("1") }
    var enviando by remember { mutableStateOf(false) }
    var resultado by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        plantillas = repositories.labeling.templates()
    }

    AppScaffold(
        title = "Etiquetas",
        onBack = onBack,
        actions = {
            androidx.compose.material3.IconButton(onClick = onOpenJobs) {
                androidx.compose.material3.Icon(
                    Icons.Filled.History,
                    contentDescription = "Trabajos de impresión",
                )
            }
        },
    ) { modifier ->
        val lista = plantillas
        if (lista == null) {
            LoadingState(modifier)
            return@AppScaffold
        }

        val actual = seleccionada
        if (actual == null) {
            LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(16.dp),
            ) {
                items(lista, key = { it.id }) { plantilla ->
                    AppRowCard(onClick = { seleccionada = plantilla; valores = emptyMap() }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = plantilla.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = AppColors.TextPrimary,
                                )
                                Text(
                                    text = "Tamaño ${plantilla.size} · ${plantilla.fields.size} campos",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.TextMuted,
                                )
                            }
                            DomainStatusChip(status = if (plantilla.isActive) "COMPLETED" else "CANCELLED")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            return@AppScaffold
        }

        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = actual.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.TextPrimary,
                    )
                    Text(
                        text = "Tamaño ${actual.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextMuted,
                    )
                }
                Spacer(Modifier.height(18.dp))
                SectionLabel("Datos de la etiqueta")
            }

            items(actual.fields, key = { it.key }) { campo ->
                CampoDePlantilla(
                    campo = campo,
                    valor = valores[campo.key].orEmpty(),
                    onValueChange = { valores = valores + (campo.key to it) },
                )
                Spacer(Modifier.height(12.dp))
            }

            item {
                Spacer(Modifier.height(4.dp))
                AppTextField(
                    value = copias,
                    onValueChange = { copias = it.filter { c -> c.isDigit() } },
                    label = { Text("Copias") },
                    singleLine = true,
                )
                Spacer(Modifier.height(20.dp))
                AppButton(
                    text = "Enviar a imprimir",
                    onClick = {
                        scope.launch {
                            enviando = true
                            // El contrato exige los campos que declara el esquema; se
                            // envían TODOS los declarados, no solo los que el usuario
                            // tocó, porque una plantilla puede tener un valor por
                            // defecto en el servidor para el campo vacío.
                            val datos = actual.fields.associate { it.key to valores[it.key].orEmpty() }
                            val n = copias.toIntOrNull()?.coerceAtLeast(1) ?: 1
                            val res = repositories.labeling.print(actual.id, n, listOf(datos))
                            enviando = false
                            resultado = res.fold(
                                onSuccess = { ids ->
                                    // Se dice PENDING y no «impreso» a propósito: el
                                    // trabajo no está impreso hasta que la impresora
                                    // confirma. El legacy lo marcaba al enviarlo, así
                                    // que si la Zebra estaba apagada la etiqueta se
                                    // perdía sin que nadie lo supiera.
                                    "Trabajo ${ids.firstOrNull() ?: ""} en cola (PENDING). " +
                                        "Estará impreso cuando la impresora lo confirme; " +
                                        "puedes seguir el estado en «Trabajos»."
                                },
                                onFailure = { "No se pudo enviar: ${it.message}" },
                            )
                        }
                    },
                    loading = enviando,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                AppButton(
                    text = "Elegir otra plantilla",
                    onClick = { seleccionada = null },
                    variant = AppButtonVariant.Text,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (resultado != null) {
                    Spacer(Modifier.height(16.dp))
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = AppColors.Info.copy(alpha = 0.10f),
                        borderColor = AppColors.Info,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Icon(
                                Icons.Filled.Print,
                                contentDescription = null,
                                tint = AppColors.Info,
                            )
                            Spacer(Modifier.padding(horizontal = 6.dp).height(1.dp))
                            Text(
                                text = resultado!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.TextPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CampoDePlantilla(
    campo: LabelFieldView,
    valor: String,
    onValueChange: (String) -> Unit,
) {
    AppTextField(
        value = valor,
        onValueChange = onValueChange,
        label = { Text(campo.label + if (campo.required) " *" else "") },
        placeholder = { Text(if (campo.required) "Obligatorio" else "Opcional") },
        singleLine = true,
        isError = campo.required && valor.isBlank(),
        supportingText = if (campo.required && valor.isBlank()) {
            { Text("Este campo lo exige la plantilla") }
        } else {
            null
        },
    )
}

/**
 * Bandeja de trabajos de impresión.
 *
 * EXISTE PORQUE EL ESTADO NO ES «ENVIADO»: es `PENDING` → `SENT` → `PRINTED`, con
 * `FAILED`. El legacy marcaba la etiqueta como impresa al enviarla, así que una
 * impresora apagada perdía el trabajo para siempre. Esta pantalla es lo que hace
 * visible un `FAILED`, y `reprint` exige motivo.
 */
@Composable
fun PrintJobsScreen(
    repositories: AppRepositories,
    onBack: () -> Unit,
) {
    var trabajos by remember { mutableStateOf<List<PrintJobView>?>(null) }
    var soloFallidos by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        trabajos = repositories.labeling.printJobs()
    }

    AppScaffold(title = "Trabajos de impresión", onBack = onBack) { modifier ->
        val lista = trabajos
        when {
            lista == null -> LoadingState(modifier)

            else -> {
                val visibles = if (soloFallidos) lista.filter { it.status == "FAILED" } else lista
                Column(modifier = modifier) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        dev.axzy.stock.mobile.core.ui.AppFilterChip(
                            label = "Todos",
                            selected = !soloFallidos,
                            onClick = { soloFallidos = false },
                        )
                        dev.axzy.stock.mobile.core.ui.AppFilterChip(
                            label = "Fallidos",
                            selected = soloFallidos,
                            onClick = { soloFallidos = true },
                        )
                    }

                    if (visibles.isEmpty()) {
                        EmptyState("No hay trabajos que mostrar")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        ) {
                            items(visibles, key = { it.id }) { trabajo ->
                                PrintJobRow(trabajo)
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrintJobRow(trabajo: PrintJobView) {
    AppRowCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Trabajo ${trabajo.id} · ${trabajo.templateCode}",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.TextPrimary,
                )
                Text(
                    text = "${trabajo.copies} copias · ${trabajo.attempts} intentos",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextMuted,
                )
            }
            DomainStatusChip(status = trabajo.status)
        }
        if (trabajo.lastError != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = trabajo.lastError!!,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Danger,
            )
        }
    }
}
