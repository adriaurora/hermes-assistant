package dk.foss.jarvis.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val JarvisDark = darkColorScheme(
    primary = Color(0xFF4934E1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF161616),
    onPrimaryContainer = Color(0xFF818CF8),
    secondary = Color(0xFF818CF8),
    tertiary = Color(0xFF818CF8),
    background = Color(0xFF0A0A0A),
    onBackground = Color.White,
    surface = Color(0xFF111111),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF161616),
    onSurfaceVariant = Color(0x8CFFFFFF),
    error = Color(0xFFFF5F56),
    onError = Color.White,
    errorContainer = Color(0xFF161616),
    onErrorContainer = Color(0xFFFF5F56),
)

val JarvisType = Typography(
    displayLarge = TextStyle(
        fontFamily = RobotoSans,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        color = Color.White,
    ),
    displayMedium = TextStyle(
        fontFamily = RobotoSans,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        color = Color.White,
    ),
    displaySmall = TextStyle(
        fontFamily = RobotoSans,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        color = Color.White,
    ),
    headlineLarge = TextStyle(
        fontFamily = RobotoSans,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        color = Color.White,
    ),
    headlineMedium = TextStyle(
        fontFamily = RobotoSans,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        color = Color.White,
    ),
    headlineSmall = TextStyle(
        fontFamily = RobotoSans,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        color = Color.White,
    ),
    titleLarge = TextStyle(
        fontFamily = RobotoSans,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        color = Color.White,
    ),
    titleMedium = TextStyle(
        fontFamily = RobotoSans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        letterSpacing = 0.15.sp,
        color = Color.White,
    ),
    titleSmall = TextStyle(
        fontFamily = RobotoSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
        color = Color.White,
    ),
    bodyLarge = TextStyle(
        fontFamily = RobotoSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        letterSpacing = 0.5.sp,
        color = Color.White,
    ),
    bodyMedium = TextStyle(
        fontFamily = RobotoSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        letterSpacing = 0.25.sp,
        color = Color.White,
    ),
    bodySmall = TextStyle(
        fontFamily = RobotoSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp,
        color = Color.White,
    ),
    labelLarge = TextStyle(
        fontFamily = RobotoSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
        color = Color.White,
    ),
    labelMedium = TextStyle(
        fontFamily = RobotoSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.5.sp,
        color = Color.White,
    ),
    labelSmall = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
        color = Color(0xFFEAF2F4),
    ),
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisDark,
        typography = JarvisType,
        content = content,
    )
}
