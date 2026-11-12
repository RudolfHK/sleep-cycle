package de.sleeptracker.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import de.sleeptracker.app.data.db.ThemeMode
import de.sleeptracker.core.SleepStage
import de.sleeptracker.core.SoundEventType

private val Dark = darkColorScheme(
    primary = Color(0xFF9FA8FF),
    onPrimary = Color(0xFF14185A),
    primaryContainer = Color(0xFF2C3280),
    onPrimaryContainer = Color(0xFFE0E0FF),
    secondary = Color(0xFF7FD6C8),
    onSecondary = Color(0xFF003731),
    tertiary = Color(0xFFF2B8D9),
    background = Color(0xFF0B0E1F),
    onBackground = Color(0xFFE3E2F0),
    surface = Color(0xFF0B0E1F),
    onSurface = Color(0xFFE3E2F0),
    surfaceVariant = Color(0xFF1C2038),
    onSurfaceVariant = Color(0xFFC6C5D8),
    surfaceContainer = Color(0xFF151933),
    surfaceContainerHigh = Color(0xFF1C2140),
    surfaceContainerLow = Color(0xFF11152B),
    outline = Color(0xFF8F8FA6),
)

private val Light = lightColorScheme(
    primary = Color(0xFF3F48B8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E0FF),
    onPrimaryContainer = Color(0xFF00006E),
    secondary = Color(0xFF006B5F),
    tertiary = Color(0xFF8E4A71),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceVariant = Color(0xFFE3E1EF),
    surfaceContainer = Color(0xFFEFEDF8),
    surfaceContainerHigh = Color(0xFFE9E7F3),
    surfaceContainerLow = Color(0xFFF5F2FD),
)

@Composable
fun SleepTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

/** Fixed, theme-independent colours for data. */
object DataColors {
    fun stage(stage: SleepStage): Color = when (stage) {
        SleepStage.AWAKE -> Color(0xFFF2A65A)
        SleepStage.REM -> Color(0xFF4FC3C7)
        SleepStage.LIGHT -> Color(0xFF7C8CF8)
        SleepStage.DEEP -> Color(0xFF3B3F9E)
    }

    fun sound(type: SoundEventType): Color = when (type) {
        SoundEventType.SNORE -> Color(0xFFB388FF)
        SoundEventType.COUGH -> Color(0xFFFF8A80)
        SoundEventType.TALK -> Color(0xFF80CBC4)
        else -> Color(0xFF9E9E9E)
    }

    val movement = Color(0xFF8C9EFF)
    val good = Color(0xFF66BB6A)
    val warn = Color(0xFFFFB74D)
    val bad = Color(0xFFE57373)

    fun score(score: Int): Color = when {
        score >= 75 -> good
        score >= 50 -> warn
        else -> bad
    }
}
