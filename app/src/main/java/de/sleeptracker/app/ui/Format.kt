package de.sleeptracker.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.sleeptracker.app.R
import de.sleeptracker.core.SleepStage
import de.sleeptracker.core.SoundEventType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

object Fmt {
    private val time get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val date get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val fullDate get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

    fun time(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().format(time)

    fun minuteOfDay(minute: Int): String = LocalTime.of((minute / 60) % 24, minute % 60).format(time)

    fun date(iso: String): String = runCatching { LocalDate.parse(iso).format(date) }.getOrDefault(iso)
    fun date(d: LocalDate): String = d.format(date)
    fun fullDate(d: LocalDate): String = d.format(fullDate)
    fun monthYear(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("LLLL yyyy"))
    fun dayShort(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("EEE d"))
}

@Composable
fun durationText(minutes: Int): String {
    val m = abs(minutes)
    val sign = if (minutes < 0) "−" else ""
    return sign + stringResource(R.string.fmt_duration, m / 60, m % 60)
}

@Composable
fun stageName(stage: SleepStage): String = stringResource(
    when (stage) {
        SleepStage.AWAKE -> R.string.stage_awake
        SleepStage.LIGHT -> R.string.stage_light
        SleepStage.DEEP -> R.string.stage_deep
        SleepStage.REM -> R.string.stage_rem
    },
)

@Composable
fun soundName(type: SoundEventType): String = stringResource(
    when (type) {
        SoundEventType.SNORE -> R.string.sound_snore
        SoundEventType.COUGH -> R.string.sound_cough
        SoundEventType.TALK -> R.string.sound_talk
        SoundEventType.NOISE_TRAFFIC -> R.string.sound_traffic
        SoundEventType.NOISE_DOOR -> R.string.sound_door
        SoundEventType.NOISE_TV -> R.string.sound_tv
        SoundEventType.NOISE_HOUSEHOLD -> R.string.sound_household
        SoundEventType.NOISE_OTHER -> R.string.sound_other
    },
)
