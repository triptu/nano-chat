package ai.tushar.nanochat.ui.theme

import ai.tushar.nanochat.R
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colors that Material's scheme has no slot for: chat bubbles and the muted dev line. */
@Immutable
data class ChatColors(
    val userBubble: Color,
    val onUserBubble: Color,
    val agentBubble: Color,
    val onAgentBubble: Color,
    val faint: Color,
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFFB5432A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9CC),
    onPrimaryContainer = Color(0xFF3B1408),
    secondary = Color(0xFF8A5A44),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE3D3),
    onSecondaryContainer = Color(0xFF2E1408),
    tertiary = Color(0xFF5E7F52),
    background = Color(0xFFFFF7EE),
    onBackground = Color(0xFF2B211C),
    surface = Color(0xFFFFF7EE),
    onSurface = Color(0xFF2B211C),
    surfaceVariant = Color(0xFFF6E7D8),
    onSurfaceVariant = Color(0xFF6B5A50),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF2E5),
    surfaceContainer = Color(0xFFFBECDD),
    surfaceContainerHigh = Color(0xFFF7E6D5),
    surfaceContainerHighest = Color(0xFFF2DFCC),
    outline = Color(0xFF958170),
    outlineVariant = Color(0xFFEBDACA),
    error = Color(0xFFB3261E),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFFF9C80),
    onPrimary = Color(0xFF4A1A0B),
    primaryContainer = Color(0xFF6B2D1A),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFE6BDA8),
    onSecondary = Color(0xFF432A1D),
    secondaryContainer = Color(0xFF4A3428),
    onSecondaryContainer = Color(0xFFFFE3D3),
    tertiary = Color(0xFFA9CC9A),
    background = Color(0xFF1C1613),
    onBackground = Color(0xFFF4EAE1),
    surface = Color(0xFF1C1613),
    onSurface = Color(0xFFF4EAE1),
    surfaceVariant = Color(0xFF3A302A),
    onSurfaceVariant = Color(0xFFC9B8AC),
    surfaceContainerLowest = Color(0xFF16110E),
    surfaceContainerLow = Color(0xFF241D19),
    surfaceContainer = Color(0xFF29211D),
    surfaceContainerHigh = Color(0xFF332A25),
    surfaceContainerHighest = Color(0xFF3D332D),
    outline = Color(0xFF8E7D72),
    outlineVariant = Color(0xFF43382F),
    error = Color(0xFFFFB4AB),
)

private val LightChat = ChatColors(
    userBubble = Color(0xFFFF8A6B),
    onUserBubble = Color(0xFF2E140A),
    agentBubble = Color(0xFFFFFFFF),
    onAgentBubble = Color(0xFF2B211C),
    faint = Color(0xFF76645A),
)

private val DarkChat = ChatColors(
    userBubble = Color(0xFFF08468),
    onUserBubble = Color(0xFF240E06),
    agentBubble = Color(0xFF2E2621),
    onAgentBubble = Color(0xFFF4EAE1),
    faint = Color(0xFFA8968A),
)

val LocalChatColors = staticCompositionLocalOf { LightChat }

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun nunito(weight: FontWeight) =
    Font(R.font.nunito, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

val Nunito = FontFamily(
    nunito(FontWeight.Normal),
    nunito(FontWeight.Medium),
    nunito(FontWeight.SemiBold),
    nunito(FontWeight.Bold),
    nunito(FontWeight.ExtraBold),
)

private fun Typography.withFamily(family: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f().copy(fontWeight = FontWeight.ExtraBold),
        headlineMedium = headlineMedium.f().copy(fontWeight = FontWeight.ExtraBold),
        headlineSmall = headlineSmall.f().copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.f().copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.f().copy(fontWeight = FontWeight.Bold),
        titleSmall = titleSmall.f().copy(fontWeight = FontWeight.Bold),
        bodyLarge = bodyLarge.f().copy(fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = bodyMedium.f().copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f().copy(fontWeight = FontWeight.Bold),
        labelMedium = labelMedium.f().copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.f().copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun NanoChatTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalChatColors provides if (dark) DarkChat else LightChat) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = Typography().withFamily(Nunito),
            shapes = AppShapes,
            content = content,
        )
    }
}
