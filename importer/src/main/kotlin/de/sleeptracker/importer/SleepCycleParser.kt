package de.sleeptracker.importer

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import kotlin.math.abs
import kotlin.math.roundToLong

/** One night read from a Sleep Cycle export. Times are local wall-clock times. */
data class ImportedNight(
    val start: LocalDateTime,
    val end: LocalDateTime,
    /** Sleep Cycle "sleep quality" 0..100. */
    val qualityPercent: Int?,
    val timeInBedSeconds: Long,
    val timeAsleepSeconds: Long?,
    val asleepAfterSeconds: Long?,
    /** 0..100 */
    val regularityPercent: Int?,
    val didSnore: Boolean?,
    val snoreSeconds: Long?,
    val coughsPerHour: Float?,
    val breathingDisruptionsPerHour: Float?,
    /** Ambient noise as exported by Sleep Cycle (dB). */
    val noiseDb: Float?,
    val lightLux: Float?,
    val movementsPerHour: Float?,
    val heartRateBpm: Float?,
    val steps: Int?,
    val windowStart: LocalDateTime?,
    val windowStop: LocalDateTime?,
    /** Legacy "Sleep Notes" entries (e.g. "Coffee", "Stressful day"). */
    val notes: List<String>,
    /** Legacy wake-up mood 1..5 (":(" = 2, ":|" = 3, ":)" = 4). */
    val mood: Int?,
    /** 1-based row number in the file (for error messages). */
    val row: Int,
)

data class RowProblem(val row: Int, val reason: Reason) {
    enum class Reason { MISSING_DATES, UNPARSABLE_DATE, END_BEFORE_START, TOO_LONG }
}

data class ParseResult(
    val nights: List<ImportedNight>,
    val problems: List<RowProblem>,
    val format: Format,
    val rowCount: Int,
) {
    enum class Format { SLEEP_CYCLE, SLEEP_CYCLE_LEGACY, UNKNOWN }
}

/**
 * Parses Sleep Cycle CSV exports (current and legacy layout), also after the file was opened and
 * re-saved as .xlsx by a spreadsheet app.
 */
object SleepCycleParser {

    private enum class Col {
        START, END, QUALITY, IN_BED_SECONDS, IN_BED_HHMM, ASLEEP_SECONDS, ASLEEP_AFTER, REGULARITY,
        DID_SNORE, SNORE_SECONDS, COUGH, BREATHING, NOISE, LIGHT, MOVEMENTS, HEART_RATE, STEPS,
        WINDOW_START, WINDOW_STOP, NOTES, MOOD,
    }

    /** Normalised header → column (lower-case letters only, so "Time in bed (seconds)" → "timeinbedseconds"). */
    private val ALIASES: Map<String, Col> = mapOf(
        "wenttobed" to Col.START, "start" to Col.START, "bedtime" to Col.START, "zubettgegangen" to Col.START, "schlafenszeit" to Col.START,
        "wokeup" to Col.END, "end" to Col.END, "aufgewacht" to Col.END, "ende" to Col.END,
        "sleepquality" to Col.QUALITY, "schlafqualitat" to Col.QUALITY, "quality" to Col.QUALITY,
        "timeinbedseconds" to Col.IN_BED_SECONDS, "timeinbed" to Col.IN_BED_HHMM,
        "timeasleepseconds" to Col.ASLEEP_SECONDS,
        "asleepafterseconds" to Col.ASLEEP_AFTER, "timebeforesleepseconds" to Col.ASLEEP_AFTER,
        "regularity" to Col.REGULARITY,
        "didsnore" to Col.DID_SNORE,
        "snoretimeseconds" to Col.SNORE_SECONDS, "snoretime" to Col.SNORE_SECONDS,
        "coughingperhour" to Col.COUGH,
        "breathingdisruptionsperhour" to Col.BREATHING,
        "ambientnoisedb" to Col.NOISE,
        "ambientlightlux" to Col.LIGHT,
        "movementsperhour" to Col.MOVEMENTS,
        "heartrate" to Col.HEART_RATE, "heartratebpm" to Col.HEART_RATE,
        "steps" to Col.STEPS, "activitysteps" to Col.STEPS,
        "wakeupwindowstart" to Col.WINDOW_START, "windowstart" to Col.WINDOW_START,
        "wakeupwindowstop" to Col.WINDOW_STOP, "windowstop" to Col.WINDOW_STOP,
        "sleepnotes" to Col.NOTES, "notes" to Col.NOTES,
        "wakeup" to Col.MOOD, "mood" to Col.MOOD,
    )

    fun normalise(header: String): String =
        TableReader.cleanText(header).lowercase()
            .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")
            .filter { it in 'a'..'z' }

    fun parse(bytes: ByteArray): ParseResult = parse(TableReader.read(bytes))

    fun parse(table: Table): ParseResult {
        val index = HashMap<Col, Int>()
        table.header.forEachIndexed { i, h -> ALIASES[normalise(h)]?.let { index.putIfAbsent(it, i) } }
        val format = when {
            Col.START !in index || Col.END !in index -> ParseResult.Format.UNKNOWN
            Col.IN_BED_SECONDS in index || Col.ASLEEP_SECONDS in index -> ParseResult.Format.SLEEP_CYCLE
            else -> ParseResult.Format.SLEEP_CYCLE_LEGACY
        }
        if (format == ParseResult.Format.UNKNOWN) return ParseResult(emptyList(), emptyList(), format, table.rows.size)

        val nights = ArrayList<ImportedNight>()
        val problems = ArrayList<RowProblem>()
        table.rows.forEachIndexed { i, cells ->
            val rowNo = i + 2
            fun cell(c: Col): String? = index[c]?.let { cells.getOrNull(it) }?.let(::missingToNull)
            val startText = cell(Col.START)
            val endText = cell(Col.END)
            if (startText == null || endText == null) { problems += RowProblem(rowNo, RowProblem.Reason.MISSING_DATES); return@forEachIndexed }
            val start = parseDateTime(startText)
            val end = parseDateTime(endText)
            if (start == null || end == null) { problems += RowProblem(rowNo, RowProblem.Reason.UNPARSABLE_DATE); return@forEachIndexed }
            if (!end.isAfter(start)) { problems += RowProblem(rowNo, RowProblem.Reason.END_BEFORE_START); return@forEachIndexed }
            val span = Duration.between(start, end).seconds
            if (span > 24 * 3600) { problems += RowProblem(rowNo, RowProblem.Reason.TOO_LONG); return@forEachIndexed }

            val inBed = cell(Col.IN_BED_SECONDS)?.let(::number)?.roundToLong()
                ?: cell(Col.IN_BED_HHMM)?.let(::hoursMinutesToSeconds)
                ?: span
            val windowStop = cell(Col.WINDOW_STOP)?.let { parseDateTime(it, near = end) }
            nights += ImportedNight(
                start = start,
                end = end,
                qualityPercent = cell(Col.QUALITY)?.let(::percent),
                timeInBedSeconds = inBed.coerceIn(0, span.coerceAtLeast(inBed)),
                timeAsleepSeconds = cell(Col.ASLEEP_SECONDS)?.let(::number)?.roundToLong()?.coerceAtLeast(0),
                asleepAfterSeconds = cell(Col.ASLEEP_AFTER)?.let(::number)?.roundToLong()?.coerceAtLeast(0),
                regularityPercent = cell(Col.REGULARITY)?.let(::percent),
                didSnore = cell(Col.DID_SNORE)?.let(::bool),
                snoreSeconds = cell(Col.SNORE_SECONDS)?.let(::number)?.roundToLong()?.coerceAtLeast(0),
                coughsPerHour = cell(Col.COUGH)?.let(::number)?.toFloat(),
                breathingDisruptionsPerHour = cell(Col.BREATHING)?.let(::number)?.toFloat(),
                noiseDb = cell(Col.NOISE)?.let(::number)?.toFloat(),
                lightLux = cell(Col.LIGHT)?.let(::number)?.toFloat()?.coerceAtLeast(0f),
                movementsPerHour = cell(Col.MOVEMENTS)?.let(::number)?.toFloat(),
                heartRateBpm = cell(Col.HEART_RATE)?.let(::number)?.toFloat()?.takeIf { it > 0f },
                steps = cell(Col.STEPS)?.let(::number)?.toInt()?.takeIf { it > 0 },
                windowStart = cell(Col.WINDOW_START)?.let { parseDateTime(it, near = windowStop ?: end) },
                windowStop = windowStop,
                notes = cell(Col.NOTES)?.split(':', ';', ',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                mood = cell(Col.MOOD)?.let(::mood),
                row = rowNo,
            )
        }
        return ParseResult(nights, problems, format, table.rows.size)
    }

    // --- Value parsing ------------------------------------------------------------------------

    private fun missingToNull(s: String): String? {
        val t = s.trim()
        return if (t.isEmpty() || t == "—" || t == "–" || t == "-" || t.equals("n/a", true) || t.equals("null", true)) null else t
    }

    /** "26,68" or "26.68" or "1,234.5" → Double. */
    fun number(s: String): Double? {
        var t = s.trim().removeSuffix("%").trim().replace(" ", "").replace(" ", "")
        if (t.isEmpty()) return null
        if (t.contains(',') && t.contains('.')) {
            t = if (t.lastIndexOf(',') > t.lastIndexOf('.')) t.replace(".", "").replace(',', '.') else t.replace(",", "")
        } else if (t.contains(',')) t = t.replace(',', '.')
        return t.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
    }

    /** "76%", "0.76" or "76" → 76. */
    fun percent(s: String): Int? {
        val hasSign = s.trim().endsWith("%")
        val v = number(s) ?: return null
        val pct = if (!hasSign && v <= 1.0) v * 100 else v
        return pct.roundToLong().toInt().coerceIn(0, 100)
    }

    private fun bool(s: String): Boolean? = when (s.trim().lowercase()) {
        "true", "yes", "1", "ja", "wahr" -> true
        "false", "no", "0", "nein", "falsch" -> false
        else -> null
    }

    private fun mood(s: String): Int? = when (s.trim()) {
        ":)", "🙂", "good" -> 4
        ":|", "😐", "ok" -> 3
        ":(", "🙁", "bad" -> 2
        else -> null
    }

    /** "7:23" (h:mm) → seconds. */
    fun hoursMinutesToSeconds(s: String): Long? {
        val parts = s.trim().split(':')
        if (parts.size !in 2..3) return number(s)?.let { (it * 3600).roundToLong() }
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val sec = parts.getOrNull(2)?.toLongOrNull() ?: 0
        return h * 3600 + m * 60 + sec
    }

    private val EXCEL_EPOCH: LocalDateTime = LocalDateTime.of(1899, 12, 30, 0, 0)

    private fun fmt(pattern: String): DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern(pattern)
        .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
        .toFormatter()

    private val PATTERNS: List<DateTimeFormatter> = listOf(
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm",
        "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm",
        "yy-MM-dd HH:mm:ss", "yy-MM-dd HH:mm", "dd-MM-yy HH:mm:ss", "dd-MM-yy HH:mm",
        "dd.MM.yyyy HH:mm:ss", "dd.MM.yyyy HH:mm", "d.M.yyyy H:mm:ss", "d.M.yyyy H:mm", "dd.MM.yy HH:mm",
        "MM/dd/yyyy HH:mm:ss", "MM/dd/yyyy HH:mm", "M/d/yyyy H:mm:ss", "M/d/yyyy H:mm", "M/d/yyyy h:mm a",
        "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm",
    ).map(::fmt)

    /**
     * Parses a date-time given as an Excel serial number or as text. When several text patterns
     * match (e.g. yy-MM-dd vs dd-MM-yy), the result closest to [near] wins.
     */
    fun parseDateTime(s: String, near: LocalDateTime? = null): LocalDateTime? {
        val t = s.trim().replace(Regex("\\.\\d+$"), "").replace(Regex("(Z|[+-]\\d{2}:?\\d{2})$"), "").trim()
        number(s)?.takeIf { it in 20_000.0..80_000.0 && !s.contains('-') && !s.contains('/') && !s.contains(':') }?.let { serial ->
            return EXCEL_EPOCH.plusSeconds((serial * 86_400).roundToLong())
        }
        val candidates = PATTERNS.mapNotNull { f -> runCatching { LocalDateTime.parse(t, f) }.getOrNull() }
            .filter { it.year in 1990..2200 }
            .distinct()
        if (candidates.isEmpty()) {
            // Date only: midnight.
            return runCatching { LocalDate.parse(t).atTime(LocalTime.MIDNIGHT) }.getOrNull()
        }
        if (near == null || candidates.size == 1) return candidates.first()
        return candidates.minBy { abs(Duration.between(it, near).seconds) }
    }
}
