package dev.axzy.stock.mobile.core.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.app_name
import dev.axzy.stock.mobile.generated.resources.common_loading
import dev.axzy.stock.mobile.generated.resources.stockflow_glyph
import io.github.alexzhirkevich.compottie.Compottie
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The StockFlow glyph: the same drawing as the web and the app icon. */
@Composable
fun ITBrandLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.stockflow_glyph),
        contentDescription = stringResource(Res.string.app_name),
        modifier = modifier,
    )
}

/** The brand loader: the Lottie animation of the web, played until the content arrives. */
@Composable
fun ITLoader(modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition {
        LottieCompositionSpec.JsonString(
            Res.readBytes("files/lottie/stockflow-loader-light.json").decodeToString()
        )
    }
    Image(
        painter = rememberLottiePainter(
            composition = composition,
            iterations = Compottie.IterateForever,
        ),
        contentDescription = stringResource(Res.string.common_loading),
        modifier = modifier,
    )
}
