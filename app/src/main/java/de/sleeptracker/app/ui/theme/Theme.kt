package de.sleeptracker.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.sleeptracker.app.data.db.ThemeMode
import de.sleeptracker.core.SleepStage
import de.sleeptracker.core.SoundEventType

/** Brand accent. Used for primary actions, selection and highlights only. */
object Brand {
    val Orange = Color(0xFFFF8A1F)
    val OrangeDim = Color(0xFFCC6A10)
    val Amber = Color(0xFFFFB547)
    val OnOrange = Color(0xFF1A0E00)
}

private val Dark = darkColorScheme(
    primary = Brand.Orange,
    onPrimary = Brand.OnOrange,
    primaryContainer = Color(0xFF3A230E),
    onPrimaryContainer = Color(0xFFFFDCC0),
    secondary = Brand.Amber,
    onSecondary = Color(0xFF261800),
    secondaryContainer = Color(0xFF2E2416),
    onSecondaryContainer = Color(0xFFFFE2B8),
    tertiary = Color(0xFF7FC8D8),
    background = Color(0xFF0D0D0F),
    onBackground = Color(0xFFECE9E6),
    surface = Color(0xFF0D0D0F),
    onSurface = Color(0xFFECE9E6),
    surfaceVariant = Color(0xFF222226),
    onSurfaceVariant = Color(0xFFA9A6A3),
    surfaceContainerLowest = Color(0xFF09090A),
    surfaceContainerLow = Color(0xFF121214),
    surfaceContainer = Color(0xFF161618),
    surfaceContainerHigh = Color(0xFF1C1C1F),
    surfaceContainerHighest = Color(0xFF242428),
    outline = Color(0xFF4A4846),
    outlineVariant = Color(0xFF2B2A2C),
    error = Color(0xFFFF6B5E),
    errorContainer = Color(0xFF4A1612),
    onErrorContainer = Color(0xFFFFDAD5),
)

private val Light = lightColorScheme(
    primary = Color(0xFFD9690A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC0),
    onPrimaryContainer = Color(0xFF2E1500),
    secondary = Color(0xFFB7791F),
    tertiary = Color(0xFF2E7D8C),
    background = Color(0xFFFAF8F6),
    surface = Color(0xFFFAF8F6),
    surfaceVariant = Color(0xFFECE7E2),
    surfaceContainerLow = Color(0xFFF4F1EE),
    surfaceContainer = Color(0xFFF0ECE8),
    surfaceContainerHigh = Color(0xFFEAE5E0),
    outline = Color(0xFFB5AEA8),
    outlineVariant = Color(0xFFDDD6D0),
)

/** Sharp, technical shapes: small radii instead of pills. */
val SharpShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(10.dp),
)

private fun typography(): Typography {
    val base = Typography()
    // Tabular figures keep columns of numbers aligned.
    val tnum = "tnum"
    return base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Light, fontFeatureSettings = tnum, letterSpacing = (-1).sp),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Light, fontFeatureSettings = tnum, letterSpacing = (-0.5).sp),
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = tnum),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = tnum),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = tnum),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyMedium = base.bodyMedium.copy(fontFeatureSettings = tnum),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
        labelMedium = base.labelMedium.copy(fontFeatureSettings = tnum),
        labelSmall = base.labelSmall.copy(fontFeatureSettings = tnum),
    )
}

/** Small uppercase label used for section titles. */
val OverlineStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)

@Composable
fun SleepTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = SharpShapes, typography = typography(), content = content)
}

/** Fixed, theme-independent colours for data; chosen to stay distinct from the orange accent. */
object DataColors {
    fun stage(stage: SleepStage): Color = when (stage) {
        SleepStage.AWAKE -> Color(0xFFFF8A1F)
        SleepStage.REM -> Color(0xFF5ED1C4)
        SleepStage.LIGHT -> Color(0xFF6E8BEF)
        SleepStage.DEEP -> Color(0xFF3A3FA8)
    }

    fun sound(type: SoundEventType): Color = when (type) {
        SoundEventType.SNORE -> Color(0xFFB892FF)
        SoundEventType.COUGH -> Color(0xFFFF6B5E)
        SoundEventType.TALK -> Color(0xFF5ED1C4)
        else -> Color(0xFF8E8B88)
    }

    val movement = Color(0xFF6E8BEF)
    val light = Color(0xFFFFD54F)
    val asleep = Color(0xFF6E8BEF)
    val good = Color(0xFF5CC08A)
    val warn = Color(0xFFFFB547)
    val bad = Color(0xFFFF6B5E)

    fun score(score: Int): Color = when {
        score >= 75 -> good
        score >= 50 -> warn
        else -> bad
    }
}
