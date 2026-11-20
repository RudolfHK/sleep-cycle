@file:OptIn(ExperimentalMaterial3Api::class)

package de.sleeptracker.app.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.sleeptracker.analysis.DetailedStatistics
import de.sleeptracker.analysis.Insight
import de.sleeptracker.analysis.InsightsCalculator
import de.sleeptracker.analysis.NightProfile
import de.sleeptracker.analysis.NightProfileCalculator
import de.sleeptracker.analysis.ProfileNight
import de.sleeptracker.app.data.repo.SessionDetails
import de.sleeptracker.analysis.DetailedStatisticsCalculator
import de.sleeptracker.analysis.PeriodStatistics
import de.sleeptracker.analysis.SleepScoreCalculator
import de.sleeptracker.analysis.StatisticsCalculator
import de.sleeptracker.app.AppContainer
import de.sleeptracker.app.R
import de.sleeptracker.app.container
import de.sleeptracker.app.data.db.SleepSession
import de.sleeptracker.app.data.db.SleepStatistics
import de.sleeptracker.app.data.repo.toNightRecord
import de.sleeptracker.app.ui.Fmt
import de.sleeptracker.app.ui.components.BarChart
import de.sleeptracker.app.ui.components.ChartLegend
import de.sleeptracker.app.ui.components.LegendItem
import de.sleeptracker.app.ui.components.LegendKind
import de.sleeptracker.app.ui.components.TrendChart
import de.sleeptracker.app.ui.components.TrendLine
import de.sleeptracker.app.ui.components.BarEntry
import de.sleeptracker.app.ui.components.ChartPanel
import de.sleeptracker.app.ui.components.EstimateNote
import de.sleeptracker.app.ui.components.KpiTile
import de.sleeptracker.app.ui.components.LineChart
import de.sleeptracker.app.ui.components.MetricRow
import de.sleeptracker.app.ui.components.PairedBarChart
import de.sleeptracker.app.ui.components.PairedEntry
import de.sleeptracker.app.ui.components.PhaseBar
import de.sleeptracker.app.ui.components.ScheduleChart
import de.sleeptracker.app.ui.components.ScheduleNight
import de.sleeptracker.app.ui.components.ScreenHeader
import de.sleeptracker.app.ui.components.SectionCard
import de.sleeptracker.app.ui.components.SectionLabel
import de.sleeptracker.app.ui.components.rememberChartZoom
import de.sleeptracker.app.ui.durationText
import de.sleeptracker.app.ui.journal.dbText
import de.sleeptracker.app.ui.journal.luxText
import de.sleeptracker.app.ui.stageName
import de.sleeptracker.app.ui.theme.DataColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class StatPeriod { DAY, WEEK, MONTH, YEAR, ALL }

/** Monthly aggregate (year / all views). */
data class MonthBucket(val month: LocalDate, val sleepMinutes: Float, val score: Float, val nights: Int)

data class StatsUiState(
    val period: StatPeriod = StatPeriod.ALL,
    val offset: Int = 0,
    val from: LocalDate = LocalDate.now(),
    val to: LocalDate = LocalDate.now(),
    val stats: PeriodStatistics = PeriodStatistics.EMPTY,
    /** Same-length previous period, for change indicators (null for "All"). */
    val previous: PeriodStatistics? = null,
    val detailed: DetailedStatistics? = null,
    /** All nights of the period, oldest first. */
    val nights: List<SleepSession> = emptyList(),
    val months: List<MonthBucket> = emptyList(),
    val goalMinutes: Int = 480,
    val noiseCalibration: Int = 90,
    /** Day view: full data of the selected night (null if none or imported). */
    val dayDetails: SessionDetails? = null,
    /** Typical-night profile over recorded nights of the period (not for Day). */
    val profile: NightProfile? = null,
    /** Recorded nights left out of the profile (shorter than 3 h or started during the day). */
    val profileSkipped: Int = 0,
    val insights: List<Insight> = emptyList(),
    val trendAlerts: List<de.sleeptracker.analysis.TrendAlert> = emptyList(),
    val trendsEnoughData: Boolean = false,
    val bundle: de.sleeptracker.app.data.repo.InsightBundle = de.sleeptracker.app.data.repo.InsightBundle(),
)

class StatisticsViewModel(private val c: AppContainer) : ViewModel() {
    // Zoomed out by default: everything that was ever recorded.
    private val selection = MutableStateFlow(StatPeriod.ALL to 0)

    val state: StateFlow<StatsUiState> = combine(selection, c.sleep.observeCompleted(), c.settings.userSettings) { (period, offset), sessions, user ->
        compute(period, offset, sessions, user.sleepGoalMinutes).copy(noiseCalibration = user.noiseCalibrationDb)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun select(p: StatPeriod) { selection.value = p to 0 }
    fun shift(delta: Int) { selection.value = selection.value.let { it.first to (it.second + delta).coerceAtMost(0) } }

    /** Drill down from a monthly bar into that month. */
    fun openMonth(month: LocalDate) {
        val offset = ChronoUnit.MONTHS.between(LocalDate.now().withDayOfMonth(1), month.withDayOfMonth(1)).toInt()
        selection.value = StatPeriod.MONTH to offset.coerceAtMost(0)
    }

    companion object {
        const val PROFILE_MAX_NIGHTS = 120
    }

    private fun dateOf(s: SleepSession) = runCatching { LocalDate.parse(s.sessionDate) }.getOrNull()

    private fun range(period: StatPeriod, offset: Int, all: List<SleepSession>): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now()
        return when (period) {
            StatPeriod.DAY -> today.plusDays(offset.toLong()).let { it to it }
            StatPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset.toLong()).let { it to it.plusDays(6) }
            StatPeriod.MONTH -> today.withDayOfMonth(1).plusMonths(offset.toLong()).let { it to it.plusMonths(1).minusDays(1) }
            StatPeriod.YEAR -> today.withDayOfYear(1).plusYears(offset.toLong()).let { it to it.plusYears(1).minusDays(1) }
            StatPeriod.ALL -> (all.mapNotNull(::dateOf).minOrNull() ?: today) to today
        }
    }

    private fun inRange(all: List<SleepSession>, from: LocalDate, to: LocalDate) =
        all.filter { s -> dateOf(s)?.let { !it.isBefore(from) && !it.isAfter(to) } ?: false }.sortedBy { it.startTimestamp }

    private suspend fun compute(period: StatPeriod, offset: Int, all: List<SleepSession>, goal: Int): StatsUiState {
        val (from, to) = range(period, offset, all)
        val nights = inRange(all, from, to)
        val records = nights.map { it.toNightRecord() }
        val stats = StatisticsCalculator.compute(records, goal)
        val previous = if (period == StatPeriod.ALL) null else {
            val (pf, pt) = range(period, offset - 1, all)
            inRange(all, pf, pt).takeIf { it.isNotEmpty() }?.let { StatisticsCalculator.compute(it.map { s -> s.toNightRecord() }, goal) }
        }
        val months = if (period == StatPeriod.YEAR || period == StatPeriod.ALL) {
            generateSequence(from.withDayOfMonth(1)) { it.plusMonths(1) }.takeWhile { !it.isAfter(to) }.map { m ->
                val list = nights.filter { s -> dateOf(s)?.let { it.year == m.year && it.month == m.month } ?: false }
                MonthBucket(
                    m,
                    if (list.isEmpty()) 0f else list.sumOf { it.estimatedSleepMinutes }.toFloat() / list.size,
                    if (list.isEmpty()) 0f else list.sumOf { it.sleepQualityScore }.toFloat() / list.size,
                    list.size,
                )
            }.toList()
        } else emptyList()
        if (stats.nights > 0) {
            val zone = ZoneId.systemDefault()
            runCatching {
                c.database.statisticsDao().upsertKeyed(
                    SleepStatistics(
                        periodStart = from.atStartOfDay(zone).toInstant().toEpochMilli(),
                        periodEnd = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                        averageSleepDuration = stats.avgSleepMinutes,
                        averageSleepQuality = stats.avgScore,
                        averageBedtime = stats.avgBedtimeMinuteOfDay,
                        averageWakeTime = stats.avgWakeMinuteOfDay,
                        averageEfficiency = stats.avgEfficiency,
                        averageSnoringEvents = stats.avgSnoreEvents,
                        averageCoughingEvents = stats.avgCoughEvents,
                    ),
                )
            }
        }
        val dayDetails = if (period == StatPeriod.DAY) {
            nights.lastOrNull { it.hasPhaseData }?.let { runCatching { c.sleep.details(it.id) }.getOrNull() }
        } else null
        var profileSkipped = 0
        val profile = if (period != StatPeriod.DAY) {
            // Only main sleep: short or daytime recordings (e.g. early test runs) would distort it.
            val withPhases = nights.filter { it.hasPhaseData }
            val main = withPhases.filter { NightProfileCalculator.isMainSleep(it.startTimestamp, it.timeInBedMinutes, it.zoneOffsetMinutes) }
            profileSkipped = withPhases.size - main.size
            val recorded = main.takeLast(PROFILE_MAX_NIGHTS)
            val signals = c.database.signalDao()
            NightProfileCalculator.compute(
                recorded.map { n ->
                    ProfileNight(
                        zoneOffsetMinutes = n.zoneOffsetMinutes,
                        runs = signals.stages(n.id).map { Triple(it.timestamp, it.durationSeconds * 1000L, it.stageType) },
                        samples = signals.epochs(n.id).map {
                            Triple(
                                it.startMillis,
                                if (it.hasSound && it.soundLevelDb > -100f) it.soundLevelDb else Float.NaN,
                                if (it.hasLight && it.lightLux >= 0f) it.lightLux else Float.NaN,
                            )
                        },
                    )
                },
            )
        } else null
        return StatsUiState(
            period = period, offset = offset, from = from, to = to, stats = stats, previous = previous,
            detailed = DetailedStatisticsCalculator.compute(records, goal),
            nights = nights, months = months, goalMinutes = goal,
            dayDetails = dayDetails, profile = profile, profileSkipped = profileSkipped,
            insights = if (period == StatPeriod.DAY) emptyList() else InsightsCalculator.compute(records),
            trendAlerts = de.sleeptracker.app.data.repo.AnalysisInputs.trends(all),
            trendsEnoughData = run {
                val now = System.currentTimeMillis()
                val d = 86_400_000L * de.sleeptracker.analysis.TrendAlertCalculator.WINDOW_DAYS
                val m = de.sleeptracker.analysis.TrendAlertCalculator.MIN_NIGHTS
                all.count { (it.endTimestamp ?: 0) in (now - d)..now } >= m && all.count { (it.endTimestamp ?: 0) in (now - 2 * d) until (now - d) } >= m
            },
            bundle = if (period == StatPeriod.DAY) de.sleeptracker.app.data.repo.InsightBundle()
            else runCatching { de.sleeptracker.app.data.repo.AnalysisInputs.bundle(c.database, nights) }.getOrDefault(de.sleeptracker.app.data.repo.InsightBundle()),
        )
    }
}

private fun nightDate(s: SleepSession): LocalDate? = runCatching { LocalDate.parse(s.sessionDate) }.getOrNull()

internal fun shortLabelPublic(s: SleepSession, period: StatPeriod) = shortLabel(s, period)

private fun shortLabel(s: SleepSession, period: StatPeriod): String {
    val d = nightDate(s) ?: return "?"
    return when (period) {
        StatPeriod.WEEK, StatPeriod.DAY -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        StatPeriod.MONTH -> "${d.dayOfMonth}"
        else -> Fmt.date(d)
    }
}

/** Y-axis label for hour values. */
private fun hoursAxis(v: Float): String = if (v % 1f == 0f) "%.0f h".format(v) else "%.1f h".format(v)

@Composable
fun StatisticsScreen(onOpenReport: (Long) -> Unit = {}, onOpenMonthly: () -> Unit = {}) {
    val c = LocalContext.current.container
    val vm: StatisticsViewModel = viewModel { StatisticsViewModel(c) }
    val st by vm.state.collectAsStateWithLifecycle()
    val s = st.stats

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            stringResource(R.string.tab_statistics),
            subtitle = if (st.from == st.to) Fmt.date(st.from) else "${Fmt.date(st.from)} – ${Fmt.date(st.to)} · ${stringResource(R.string.n_nights, s.nights)}",
        ) {
            IconButton(onClick = onOpenMonthly) {
                Icon(androidx.compose.material.icons.Icons.Filled.CalendarMonth, stringResource(R.string.monthly_reports), tint = MaterialTheme.colorScheme.primary)
            }
        }
        PeriodSelector(st.period, vm::select)
        if (st.period != StatPeriod.ALL) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.shift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous)) }
                Text(
                    if (st.from == st.to) Fmt.date(st.from) else "${Fmt.date(st.from)} – ${Fmt.date(st.to)}",
                    Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = { vm.shift(1) }, enabled = st.offset < 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next)) }
            }
        }
        if (s.nights == 0) {
            Text(stringResource(R.string.stats_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }

        if (st.period == StatPeriod.DAY) {
            val day = st.dayDetails
            if (day != null) NightOverviewPanel(day, st.noiseCalibration)
            else SectionCard(stringResource(R.string.night_overview)) {
                Text(stringResource(R.string.night_overview_imported), style = MaterialTheme.typography.bodySmall)
            }
        }
        KpiGrid(st)
        if (st.period != StatPeriod.DAY) {
            PhaseStackPanel(st)
            ProfilePanel(st.profile, st.noiseCalibration, st.profileSkipped)
            InsightsPanel(st.insights, s.nights, st.noiseCalibration)
            TrendAlertsCard(st.trendAlerts, st.trendsEnoughData)
            TagImpactCard(st.bundle.tagImpacts)
            ChronotypeCard(st.bundle.chronotype)
            RegularityIndexCard(st.bundle.sri)
        }
        DurationPanel(st, onOpenReport)
        if (st.months.size > 1) MonthsPanel(st, vm::openMonth)
        ScorePanel(st, onOpenReport)
        HabitsPanels(st)
        DetailPanels(st, onOpenReport)
        EnvironmentStatsCard(st)
        EstimateNote()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PeriodSelector(selected: StatPeriod, onSelect: (StatPeriod) -> Unit) {
    val periods = StatPeriod.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        periods.forEachIndexed { i, p ->
            SegmentedButton(
                selected = selected == p,
                onClick = { onSelect(p) },
                shape = SegmentedButtonDefaults.itemShape(i, periods.size, baseShape = MaterialTheme.shapes.small),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary,
                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                icon = {},
            ) {
                Text(
                    stringResource(
                        when (p) {
                            StatPeriod.DAY -> R.string.period_day
                            StatPeriod.WEEK -> R.string.period_week
                            StatPeriod.MONTH -> R.string.period_month
                            StatPeriod.YEAR -> R.string.period_year
                            StatPeriod.ALL -> R.string.period_all
                        },
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun KpiGrid(st: StatsUiState) {
    val s = st.stats
    val p = st.previous
    val d = st.detailed
    @Composable
    fun minutesDelta(now: Float, before: Float?): String? = before?.let {
        val diff = (now - it).roundToInt()
        stringResource(R.string.delta_vs_previous, (if (diff >= 0) "+" else "−") + durationText(abs(diff)))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KpiTile(
                stringResource(R.string.avg_time_asleep), durationText(s.avgSleepMinutes.toInt()), Modifier.weight(1f),
                delta = minutesDelta(s.avgSleepMinutes, p?.avgSleepMinutes),
                deltaPositive = p?.let { s.avgSleepMinutes >= it.avgSleepMinutes },
            )
            KpiTile(
                stringResource(R.string.average_score), "%.0f".format(s.avgScore), Modifier.weight(1f),
                delta = p?.let { stringResource(R.string.delta_vs_previous, "%+.0f".format(s.avgScore - it.avgScore)) },
                deltaPositive = p?.let { s.avgScore >= it.avgScore },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KpiTile(
                stringResource(R.string.efficiency), "%.0f %%".format(s.avgEfficiency), Modifier.weight(1f),
                delta = p?.let { stringResource(R.string.delta_vs_previous, "%+.1f pp".format(s.avgEfficiency - it.avgEfficiency)) },
                deltaPositive = p?.let { s.avgEfficiency >= it.avgEfficiency },
            )
            val bedDelta = p?.avgBedtimeMinuteOfDay?.let { before ->
                s.avgBedtimeMinuteOfDay?.let { now -> Math.floorMod(now - before + 720, 1440) - 720 }
            }
            KpiTile(
                stringResource(R.string.avg_bedtime), s.avgBedtimeMinuteOfDay?.let(Fmt::minuteOfDay) ?: "–", Modifier.weight(1f),
                delta = bedDelta?.let {
                    when {
                        it > 0 -> stringResource(R.string.later_by, durationText(it))
                        it < 0 -> stringResource(R.string.earlier_by, durationText(-it))
                        else -> stringResource(R.string.unchanged)
                    }
                },
            )
        }
        if (d != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KpiTile(stringResource(R.string.goal_reached), "%.0f %%".format(d.goalMetPercent), Modifier.weight(1f), delta = stringResource(R.string.n_of_m_nights, d.goalMetNights, s.nights))
                KpiTile(stringResource(R.string.longest_streak), stringResource(R.string.n_nights, d.longestGoalStreak), Modifier.weight(1f), delta = stringResource(R.string.goal_is, durationText(st.goalMinutes)))
            }
        }
    }
}

@Composable
private fun DurationPanel(st: StatsUiState, onOpenReport: (Long) -> Unit) {
    val zoom = rememberChartZoom(st.nights.size, st.period to st.offset)
    var picked by remember(st.nights) { mutableStateOf<Int?>(null) }
    val goalH = st.goalMinutes / 60f
    val primary = MaterialTheme.colorScheme.primary
    ChartPanel(
        stringResource(R.string.sleep_duration),
        zoom,
        footer = {
            Text(stringResource(R.string.duration_legend), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            picked?.let { i -> st.nights.getOrNull(i)?.let { n -> OpenNightButton(n, onOpenReport) } }
        },
    ) { expanded ->
        val hours = st.nights.map { it.estimatedSleepMinutes / 60f }
        val avg = DetailedStatisticsCalculator.rollingAverage(hours, 7)
        val readouts = st.nights.mapIndexed { i, n ->
            "${nightDate(n)?.let(Fmt::date) ?: n.sessionDate}: ${durationText(n.estimatedSleepMinutes)} · Ø7 ${durationText((avg[i] * 60).toInt())}"
        }
        TrendChart(
            labels = st.nights.map { shortLabel(it, st.period) },
            values = hours,
            color = primary,
            format = ::hoursAxis,
            readout = { readouts[it] },
            secondary = listOf(TrendLine(avg, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), 1.4f)),
            reference = goalH,
            referenceLabel = stringResource(R.string.goal_is, durationText(st.goalMinutes)),
            barColors = st.nights.map { if (it.estimatedSleepMinutes >= st.goalMinutes) primary else MaterialTheme.colorScheme.onSurfaceVariant },
            zoom = zoom,
            height = if (expanded) 320.dp else 180.dp,
            description = stringResource(R.string.cd_duration_chart),
            onSelect = { picked = it },
        )
    }
}

@Composable
private fun OpenNightButton(n: SleepSession, onOpenReport: (Long) -> Unit) {
    TextButton(onClick = { onOpenReport(n.id) }) {
        Text(stringResource(R.string.open_night, nightDate(n)?.let(Fmt::date) ?: n.sessionDate))
    }
}

@Composable
private fun MonthsPanel(st: StatsUiState, onOpenMonth: (LocalDate) -> Unit) {
    var picked by remember(st.months) { mutableStateOf<Int?>(null) }
    ChartPanel(
        stringResource(R.string.monthly_overview),
        zoom = null,
        footer = {
            picked?.let { i ->
                st.months.getOrNull(i)?.takeIf { it.nights > 0 }?.let { m ->
                    TextButton(onClick = { onOpenMonth(m.month) }) { Text(stringResource(R.string.zoom_into, Fmt.monthYear(m.month))) }
                }
            }
        },
    ) { expanded ->
        val readouts = st.months.map { m ->
            "${Fmt.monthYear(m.month)}: " + if (m.nights == 0) "–" else "${durationText(m.sleepMinutes.toInt())} · ${"%.0f".format(m.score)} · ${stringResource(R.string.n_nights, m.nights)}"
        }
        TrendChart(
            labels = st.months.map { it.month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) },
            values = st.months.map { if (it.nights == 0) null else it.sleepMinutes / 60f },
            color = MaterialTheme.colorScheme.primary,
            format = ::hoursAxis,
            readout = { readouts[it] },
            reference = st.goalMinutes / 60f,
            height = if (expanded) 300.dp else 150.dp,
            description = stringResource(R.string.monthly_overview),
            onSelect = { picked = it },
        )
        Text(stringResource(R.string.monthly_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScorePanel(st: StatsUiState, onOpenReport: (Long) -> Unit) {
    val s = st.stats
    val zoom = rememberChartZoom(st.nights.size, st.period to st.offset)
    ChartPanel(stringResource(R.string.sleep_quality), zoom) { expanded ->
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { MetricRow(stringResource(R.string.highest), "${s.maxScore}") }
            Spacer(Modifier.padding(horizontal = 8.dp))
            Column(Modifier.weight(1f)) { MetricRow(stringResource(R.string.lowest), "${s.minScore}") }
        }
        if (st.nights.size >= 2) {
            val readouts = st.nights.map { "${nightDate(it)?.let(Fmt::date) ?: it.sessionDate}: ${it.sleepQualityScore}" }
            TrendChart(
                labels = st.nights.map { shortLabel(it, st.period) },
                values = st.nights.map { it.sleepQualityScore.toFloat() },
                color = MaterialTheme.colorScheme.secondary,
                format = { "%.0f".format(it) },
                readout = { readouts[it] },
                yRange = 0f..100f,
                reference = s.avgScore,
                referenceLabel = "Ø %.0f".format(s.avgScore),
                barColors = st.nights.map { DataColors.score(it.sleepQualityScore) },
                zoom = zoom,
                height = if (expanded) 280.dp else 150.dp,
                description = stringResource(R.string.cd_score_chart),
            )
        }
    }
}

@Composable
private fun IndexRange(st: StatsUiState) {
    if (st.nights.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(shortLabel(st.nights.first(), st.period), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(shortLabel(st.nights.last(), st.period), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** When you go to bed, fall asleep and wake up; time in bed vs. asleep; weekday vs. weekend. */
@Composable
private fun HabitsPanels(st: StatsUiState) {
    val s = st.stats
    SectionCard(stringResource(R.string.habits)) {
        MetricRow(stringResource(R.string.avg_bedtime), s.avgBedtimeMinuteOfDay?.let(Fmt::minuteOfDay) ?: "–")
        MetricRow(stringResource(R.string.avg_fell_asleep), s.avgSleepOnsetMinuteOfDay?.let(Fmt::minuteOfDay) ?: "–")
        MetricRow(stringResource(R.string.avg_wake_time), s.avgWakeMinuteOfDay?.let(Fmt::minuteOfDay) ?: "–")
        MetricRow(stringResource(R.string.latency), durationText(s.avgLatencyMinutes.toInt()))
        MetricRow(stringResource(R.string.avg_time_in_bed), durationText(s.avgTimeInBedMinutes.toInt()))
        MetricRow(stringResource(R.string.avg_time_asleep), durationText(s.avgSleepMinutes.toInt()))
        MetricRow(stringResource(R.string.bedtime_variation), "± ${s.bedtimeSdMinutes.toInt()} min")
        MetricRow(stringResource(R.string.wake_variation), "± ${s.wakeSdMinutes.toInt()} min")
        MetricRow(stringResource(R.string.regularity_index), "%.0f / 100".format(s.regularity))
    }

    if (st.nights.isNotEmpty()) {
        val scheduleZoom = rememberChartZoom(st.nights.size, st.period to st.offset)
        ChartPanel(
            stringResource(R.string.sleep_schedule),
            scheduleZoom,
            footer = { Text(stringResource(R.string.schedule_legend), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        ) { expanded ->
            val schedule = st.nights.map { n ->
                val bed = SleepScoreCalculator.minutesSinceEvening(n.startTimestamp, n.zoneOffsetMinutes)
                val asleep = (bed + n.sleepLatencyMinutes).coerceAtMost(1439)
                val wake = (bed + n.timeInBedMinutes).coerceIn(asleep, 1439)
                ScheduleNight(
                    label = shortLabel(n, st.period),
                    bedMinute = bed, asleepMinute = asleep, wakeMinute = wake,
                    detail = "${Fmt.time(n.startTimestamp)} → ${Fmt.time(n.endTimestamp ?: n.startTimestamp)}",
                )
            }
            ScheduleChart(schedule, zoom = scheduleZoom, height = if (expanded) 380.dp else 200.dp, description = stringResource(R.string.cd_schedule_chart))
        }

        val bedZoom = rememberChartZoom(st.nights.size, st.period to st.offset)
        ChartPanel(stringResource(R.string.in_bed_vs_asleep), bedZoom) { expanded ->
            val readouts = st.nights.map { n ->
                "${shortLabel(n, st.period)}: ${durationText(n.estimatedSleepMinutes)} / ${durationText(n.timeInBedMinutes)} (%.0f %%)".format(n.sleepEfficiency)
            }
            val awakeColor = DataColors.stage(de.sleeptracker.core.SleepStage.AWAKE)
            TrendChart(
                labels = st.nights.map { shortLabel(it, st.period) },
                values = st.nights.map { it.estimatedSleepMinutes / 60f },
                color = DataColors.asleep,
                format = ::hoursAxis,
                readout = { readouts[it] },
                bandUpper = st.nights.map { it.timeInBedMinutes / 60f },
                bandColor = awakeColor,
                showBars = false,
                zoom = bedZoom,
                height = if (expanded) 300.dp else 160.dp,
                description = stringResource(R.string.cd_in_bed_chart),
            )
            ChartLegend(
                listOf(
                    LegendItem(DataColors.asleep, stringResource(R.string.asleep), LegendKind.AREA),
                    LegendItem(awakeColor, stringResource(R.string.legend_in_bed_awake), LegendKind.AREA),
                ),
            )
        }
    }

    val wd = s.weekdays
    val we = s.weekends
    if (wd != null || we != null) {
        SectionCard(stringResource(R.string.weekday_vs_weekend)) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                SectionLabel(stringResource(R.string.weekdays), Modifier.weight(0.7f))
                SectionLabel(stringResource(R.string.weekends), Modifier.weight(0.7f))
            }
            @Composable
            fun row(label: String, a: String, b: String) {
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(a, Modifier.weight(0.7f), style = MaterialTheme.typography.bodyMedium)
                    Text(b, Modifier.weight(0.7f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            fun t(m: Int?) = m?.let(Fmt::minuteOfDay) ?: "–"
            row(stringResource(R.string.nights), "${wd?.nights ?: 0}", "${we?.nights ?: 0}")
            row(stringResource(R.string.bedtime), t(wd?.avgBedtimeMinuteOfDay), t(we?.avgBedtimeMinuteOfDay))
            row(stringResource(R.string.wake_time), t(wd?.avgWakeMinuteOfDay), t(we?.avgWakeMinuteOfDay))
            row(stringResource(R.string.time_in_bed), wd?.let { durationText(it.avgTimeInBedMinutes.toInt()) } ?: "–", we?.let { durationText(it.avgTimeInBedMinutes.toInt()) } ?: "–")
            row(stringResource(R.string.asleep), wd?.let { durationText(it.avgSleepMinutes.toInt()) } ?: "–", we?.let { durationText(it.avgSleepMinutes.toInt()) } ?: "–")
            s.socialJetLagMinutes?.let {
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                MetricRow(stringResource(R.string.social_jet_lag_label), durationText(it))
                Text(stringResource(R.string.social_jet_lag_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DetailPanels(st: StatsUiState, onOpenReport: (Long) -> Unit) {
    val s = st.stats
    val d = st.detailed ?: return

    SectionCard(stringResource(R.string.sleep_by_weekday)) {
        val names = DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
        val readouts = d.sleepByWeekday.mapIndexed { i, v -> "${names[i]}: ${v?.let { durationText(it.toInt()) } ?: "–"}" }
        TrendChart(
            labels = names,
            values = d.sleepByWeekday.map { it?.div(60f) },
            color = MaterialTheme.colorScheme.primary,
            format = ::hoursAxis,
            readout = { readouts[it] },
            reference = st.goalMinutes / 60f,
            height = 150.dp,
            description = stringResource(R.string.sleep_by_weekday),
        )
    }

    SectionCard(stringResource(R.string.duration_distribution)) {
        val labels = listOf("<5 h", "5–6", "6–7", "7–8", "8–9", "≥9 h")
        val readouts = d.durationHistogram.mapIndexed { i, n -> "${labels[i]}: ${stringResource(R.string.n_nights, n)}" }
        TrendChart(
            labels = labels,
            values = d.durationHistogram.map { it.toFloat() },
            color = MaterialTheme.colorScheme.secondary,
            format = { "%.0f".format(it) },
            readout = { readouts[it] },
            height = 140.dp,
            description = stringResource(R.string.duration_distribution),
        )
    }

    val best = d.bestIndex?.let { st.nights.getOrNull(it) }
    val worst = d.worstIndex?.let { st.nights.getOrNull(it) }
    if (best != null && worst != null && st.nights.size > 1) {
        SectionCard(stringResource(R.string.best_and_worst)) {
            NightLink(stringResource(R.string.best_night), best, onOpenReport)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NightLink(stringResource(R.string.worst_night), worst, onOpenReport)
        }
    }

    SectionCard(stringResource(R.string.sleep_debt)) {
        val debt = s.sleepDebtMinutes
        Text(
            if (debt > 0) stringResource(R.string.sleep_debt_deficit, durationText(debt)) else stringResource(R.string.sleep_debt_surplus, durationText(-debt)),
            style = MaterialTheme.typography.titleMedium,
            color = if (debt > 0) DataColors.warn else DataColors.good,
        )
        Text(stringResource(R.string.sleep_debt_hint, durationText(st.goalMinutes)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    SectionCard(stringResource(R.string.phase_distribution)) {
        if (s.avgLightMinutes + s.avgDeepMinutes + s.avgRemMinutes <= 0f) {
            Text(stringResource(R.string.imported_no_phases), style = MaterialTheme.typography.bodySmall)
        } else {
            PhaseBar(s.avgAwakeMinutes.toInt(), s.avgLightMinutes.toInt(), s.avgDeepMinutes.toInt(), s.avgRemMinutes.toInt()) { stage, v ->
                "${stageName(stage)} ${durationText(v)}"
            }
        }
    }

    SectionCard(stringResource(R.string.sounds)) {
        MetricRow(stringResource(R.string.snoring_per_night), "%.1f".format(s.avgSnoreEvents))
        MetricRow(stringResource(R.string.snoring_total), durationText(s.totalSnoreMinutes.toInt()))
        MetricRow(stringResource(R.string.coughing_per_night), "%.1f".format(s.avgCoughEvents))
        MetricRow(stringResource(R.string.talking_per_night), "%.1f".format(s.avgTalkEvents))
        MetricRow(stringResource(R.string.noise_per_night), "%.1f".format(s.avgNoiseEvents))
    }
}

@Composable
private fun NightLink(label: String, n: SleepSession, onOpenReport: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpenReport(n.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${nightDate(n)?.let(Fmt::date) ?: n.sessionDate} · ${durationText(n.estimatedSleepMinutes)}", style = MaterialTheme.typography.bodyMedium)
        }
        Text("${n.sleepQualityScore}", style = MaterialTheme.typography.titleLarge, color = DataColors.score(n.sleepQualityScore))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EnvironmentStatsCard(st: StatsUiState) {
    val s = st.stats
    if (s.avgNoiseDb == null && s.avgLux == null) return
    val noisy = st.nights.filter { it.hasNoiseData }
    val zoom = rememberChartZoom(noisy.size, st.period to st.offset)
    ChartPanel(stringResource(R.string.environment), if (noisy.size >= 2) zoom else null) { expanded ->
        s.avgNoiseDb?.let { MetricRow(stringResource(R.string.avg_noise), dbText(it, st.noiseCalibration)) }
        s.avgLux?.let { MetricRow(stringResource(R.string.avg_light), luxText(it)) }
        s.avgDarkPercent?.let { MetricRow(stringResource(R.string.dark_share), "%.0f %%".format(it)) }
        if (noisy.size >= 2) {
            Spacer(Modifier.height(6.dp))
            SectionLabel(stringResource(R.string.noise_per_night_chart))
            val readouts = noisy.map { "${shortLabel(it, st.period)}: ${dbText(it.avgNoiseDb, st.noiseCalibration)}" }
            TrendChart(
                labels = noisy.map { shortLabel(it, st.period) },
                values = noisy.map { it.avgNoiseDb + st.noiseCalibration },
                color = de.sleeptracker.app.ui.components.LayerColors.noise,
                format = { "%.0f dB".format(it) },
                readout = { readouts[it] },
                includeZero = false,
                zoom = zoom,
                height = if (expanded) 260.dp else 140.dp,
                description = stringResource(R.string.cd_noise_chart),
            )
        }
        val lit = st.nights.filter { it.hasLightData && it.darkPercent >= 0f }
        if (lit.size >= 2) {
            Spacer(Modifier.height(6.dp))
            SectionLabel(stringResource(R.string.dark_per_night_chart))
            val readouts = lit.map { "${shortLabel(it, st.period)}: ${"%.0f %%".format(it.darkPercent)}" }
            TrendChart(
                labels = lit.map { shortLabel(it, st.period) },
                values = lit.map { it.darkPercent },
                color = DataColors.light,
                format = { "%.0f%%".format(it) },
                readout = { readouts[it] },
                yRange = 0f..100f,
                height = if (expanded) 220.dp else 130.dp,
                description = stringResource(R.string.cd_light_chart),
            )
        }
    }
}
