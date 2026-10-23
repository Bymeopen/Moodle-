package id.pribadi.minilms

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode(val label: String) { SYSTEM("Sistem"), LIGHT("Terang"), DARK("Gelap") }

val LocalIsDark = compositionLocalOf { false }

// Warna hangat netral dengan oranye Moodle sebagai aksen; indigo khusus untuk hal-hal AI; teal untuk status "siap/berhasil".
private val LightScheme = lightColorScheme(
    primary = Color(0xFFE8590C), onPrimary = Color.White, primaryContainer = Color(0xFFFFE6D5), onPrimaryContainer = Color(0xFF5C2100),
    secondary = Color(0xFF0F766E), onSecondary = Color.White, secondaryContainer = Color(0xFFD5F2EE), onSecondaryContainer = Color(0xFF04332F),
    tertiary = Color(0xFF6D5BD0), onTertiary = Color.White, tertiaryContainer = Color(0xFFE7E2FF), onTertiaryContainer = Color(0xFF1F1458),
    background = Color(0xFFFAF7F2), onBackground = Color(0xFF1C1917), surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1C1917),
    surfaceVariant = Color(0xFFEFE9E1), onSurfaceVariant = Color(0xFF57504A), outline = Color(0xFF8C8279), outlineVariant = Color(0xFFE2DAD0),
    error = Color(0xFFC62828), onError = Color.White, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F2EC), surfaceContainer = Color(0xFFF1ECE4),
    surfaceContainerHigh = Color(0xFFEBE5DC), surfaceContainerHighest = Color(0xFFE5DED4),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF9A5C), onPrimary = Color(0xFF3A1500), primaryContainer = Color(0xFF5E2808), onPrimaryContainer = Color(0xFFFFE0CC),
    secondary = Color(0xFF5FD3C5), onSecondary = Color(0xFF00201D), secondaryContainer = Color(0xFF0B4B45), onSecondaryContainer = Color(0xFFC9F5EF),
    tertiary = Color(0xFFB8ACFF), onTertiary = Color(0xFF1F1458), tertiaryContainer = Color(0xFF3F3490), onTertiaryContainer = Color(0xFFE7E2FF),
    background = Color(0xFF100F0E), onBackground = Color(0xFFF2EDE8), surface = Color(0xFF181615), onSurface = Color(0xFFF2EDE8),
    surfaceVariant = Color(0xFF2E2A27), onSurfaceVariant = Color(0xFFC5BCB3), outline = Color(0xFF9A9087), outlineVariant = Color(0xFF3A3532),
    error = Color(0xFFFF8A80), onError = Color(0xFF3B0000), errorContainer = Color(0xFF5C1A17), onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainerLowest = Color(0xFF131110), surfaceContainerLow = Color(0xFF1C1A18), surfaceContainer = Color(0xFF232120),
    surfaceContainerHigh = Color(0xFF2B2826), surfaceContainerHighest = Color(0xFF353230),
)

private val Typo = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)

private val Shape = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun LmsTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    CompositionLocalProvider(LocalIsDark provides dark) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = Typo, shapes = Shape, content = content)
    }
}

/** Pasangan warna gradien banner kursus; dipilih stabil dari nama kursus. */
object Palette {
    private val light = listOf(
        Color(0xFFFF9966) to Color(0xFFE8590C), Color(0xFF5BB6FF) to Color(0xFF2563EB), Color(0xFF3DDC97) to Color(0xFF0F766E),
        Color(0xFFB39DFF) to Color(0xFF6D28D9), Color(0xFFFF8FB8) to Color(0xFFBE185D), Color(0xFFFFCB57) to Color(0xFFD97706),
    )
    fun course(key: Long): Pair<Color, Color> = light[Logic.paletteIndex(key, light.size)]
}
