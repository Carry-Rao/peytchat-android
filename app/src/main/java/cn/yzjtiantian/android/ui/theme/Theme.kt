package cn.yzjtiantian.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = BrandViolet,
    onPrimary = OnBrandDark,
    primaryContainer = Color(0xFF46318F),
    onPrimaryContainer = Color(0xFFF5F0FF),
    secondary = Color(0xFFB0A6E8),
    onSecondary = OnBrandDark,
    secondaryContainer = Color(0xFF3A3263),
    onSecondaryContainer = Color(0xFFE8E2FF),
    tertiary = BrandOrange,
    onTertiary = OnBrandDark,
    background = Color(0xFF000000),
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = PanelActive,
    onSurfaceVariant = TextMute,
    outline = BorderSoft,
    outlineVariant = BorderSoft,
    error = Color(0xFFFF6B8A),
)

private val LightColorScheme = lightColorScheme(
    primary = GoldenPrimary,
    onPrimary = Color(0xFFFFF8F0),
    primaryContainer = Color(0xFFFFDCC8),
    onPrimaryContainer = Color(0xFF3A1A08),
    secondary = Color(0xFF7A7068),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0E7DC),
    onSecondaryContainer = Color(0xFF2C241C),
    tertiary = BrandViolet,
    onTertiary = Color(0xFFFFFFFF),
    background = GoldenBg,
    onBackground = Color(0xFF3A3028),
    surface = GoldenSurface,
    onSurface = Color(0xFF3A3028),
    surfaceVariant = Color(0xFFEAE1D5),
    onSurfaceVariant = Color(0xFF7A7068),
    outline = Color(0xFFB8AFA4),
    outlineVariant = Color(0xFFEAE1D5),
    error = Color(0xFFD9464A),
)

@Composable
fun PeytchatTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content,
    )
}
