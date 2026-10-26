package ai.tushar.nanochat.devtools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether developer surfaces are shown. Provided once at the root from [ai.tushar.nanochat.settings.Settings.devToolsEnabled];
 * every dev surface lives in this package and checks it through [DevOnly] or this local.
 */
val LocalDevTools = staticCompositionLocalOf { false }

@Composable
fun DevOnly(content: @Composable () -> Unit) {
    if (LocalDevTools.current) content()
}
