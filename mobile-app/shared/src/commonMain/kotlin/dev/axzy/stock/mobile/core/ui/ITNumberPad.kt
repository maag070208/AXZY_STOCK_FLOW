package dev.axzy.stock.mobile.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.axzy.stock.mobile.core.theme.ITShape
import dev.axzy.stock.mobile.core.theme.ITSpacing
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.common_delete
import org.jetbrains.compose.resources.stringResource

/**
 * The number pad for counts. Its keys are large because the floor operator types with a glove, and it is always
 * on screen, so the count never waits for the system keyboard. It reports each digit and the delete key; the
 * screen decides which field they go to.
 */
@Composable
fun ITNumberPad(
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ITSpacing.S)) {
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { digits ->
            Row(horizontalArrangement = Arrangement.spacedBy(ITSpacing.S)) {
                digits.forEach { digit ->
                    PadKey(onClick = { onDigit(digit) }, modifier = Modifier.weight(1f)) {
                        Text(
                            text = digit.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ITSpacing.S)) {
            Spacer(modifier = Modifier.weight(1f))
            PadKey(onClick = { onDigit(0) }, modifier = Modifier.weight(1f)) {
                Text(
                    text = "0",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            PadKey(onClick = onDelete, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = stringResource(Res.string.common_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PadKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(ITShape.Control)
            .background(colors.surface, ITShape.Control)
            .border(1.dp, colors.outlineVariant, ITShape.Control)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
