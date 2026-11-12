package de.sleeptracker.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import de.sleeptracker.analysis.PeriodStatistics
import de.sleeptracker.analysis.StatisticsCalculator
import de.sleeptracker.analysis.SleepScoreCalculator
import de.sleeptracker.app.ui.components.PairedBarChart
import de.sleeptracker.app.ui.components.PairedEntry
import de.sleeptracker.app.ui.components.ScheduleChart
import de.sleeptracker.app.ui.components.ScheduleNight
import de.sleeptracker.app.ui.journal.dbText
import de.sleeptracker.app.ui.journal.luxText
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import de.sleeptracker.app.AppContainer
import de.sleeptracker.app.R
import de.sleeptracker.app.container
import de.sleeptracker.app.data.db.SleepSession
import de.sleeptracker.app.data.db.SleepStatistics
import de.sleeptracker.app.data.repo.toNightRecord
import de.sleeptracker.app.ui.Fmt
import de.sleeptracker.app.ui.components.BarChart
import de.sleeptracker.app.ui.components.BarEntry
import de.sleeptracker.app.ui.components.ChoiceChips
import de.sleeptracker.app.ui.components.EstimateNote
import de.sleeptracker.app.ui.components.PhaseBar
import de.sleeptracker.app.ui.components.SectionCard
import de.sleeptracker.app.ui.components.StatGrid
import de.sleeptracker.app.ui.durationText
import de.sleeptracker.app.ui.stageName
import de.sleeptracker.app.ui.theme.DataColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class StatPeriod { DAY, WEEK, MONTH, YEAR, ALL }

/** One bar: a day (week/month view) or a month (year/all view). */
data class Bucket(val start: LocalDate, val sleepMinutes: Float, val score: Float, val nights: Int)

data class StatsUiState(
    val period: StatPeriod = StatPeriod.WEEK,
    val offset: Int = 0,
    val from: LocalDate = LocalDate.now(),
    val to: LocalDate = LocalDate.now(),
    val stats: PeriodStatistics = PeriodStatistics.EMPTY,
    val buckets: List<Bucket> = emptyList(),
    val goalMinutes: Int = 480,
    /** Nights of the period (oldest first, at most the last 31) for the habit charts. */
    val nights: List<SleepSession> = emptyList(),
    val noiseCalibration: Int = 90,
)

class StatisticsViewModel(private val c: AppContainer) : ViewModel() {
    private val selection = MutableStateFlow(StatPeriod.WEEK to 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<StatsUiState> = combine(selection, c.sleep.observeCompleted(), c.settings.userSettings) { (period, offset), sessions, user ->
        compute(period, offset, sessions, user.sleepGoalMinutes).copy(noiseCalibration = user.noiseCalibrationDb)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun select(p: StatPeriod) { selection.value = p to 0 }
    fun shift(delta: Int) { selection.value = selection.value.let { it.first to (it.second + delta).coerceAtMost(0) } }

    private fun dateOf(s: SleepSession) = runCatching { LocalDate.parse(s.sessionDate) }.getOrNull()

    private suspend fun compute(period: StatPeriod, offset: Int, all: List<SleepSession>, goal: Int): StatsUiState {
        val today = LocalDate.now()
        val (from, to) = when (period) {
            StatPeriod.DAY -> today.plusDays(offset.toLong()).let { it to it }
            StatPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset.toLong()).let { it to it.plusDays(6) }
            StatPeriod.MONTH -> today.withDayOfMonth(1).plusMonths(offset.toLong()).let { it to it.plusMonths(1).minusDays(1) }
            StatPeriod.YEAR -> today.withDayOfYear(1).plusYears(offset.toLong()).let { it to it.plusYears(1).minusDays(1) }
            StatPeriod.ALL -> (all.mapNotNull(::dateOf).minOrNull() ?: today) to today
        }
        val inRange = all.filter { s -> dateOf(s)?.let { !it.isBefore(from) && !it.isAfter(to) } ?: false }
        val stats = StatisticsCalculator.compute(inRange.map { it.toNightRecord() }, goal)

        val buckets = when (period) {
            StatPeriod.DAY -> emptyList()
            StatPeriod.WEEK, StatPeriod.MONTH -> generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { d ->
                val nights = inRange.filter { dateOf(it) == d }
                bucket(d, nights)
            }.toList()
            StatPeriod.YEAR, StatPeriod.ALL -> generateSequence(from.withDayOfMonth(1)) { it.plusMonths(1) }.takeWhile { !it.isAfter(to) }.map { m ->
                bucket(m, inRange.filter { s -> dateOf(s)?.let { it.year == m.year && it.month == m.month } ?: false })
            }.toList()
        }
        // Cache the aggregate locally (SleepStatistics table).
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
        val nights = inRange.sortedBy { it.startTimestamp }.takeLast(31)
        return StatsUiState(period, offset, from, to, stats, buckets, goal, nights)
    }

    private fun bucket(d: LocalDate, nights: List<SleepSession>) = Bucket(
        start = d,
        sleepMinutes = if (nights.isEmpty()) 0f else nights.sumOf { it.estimatedSleepMinutes }.toFloat() / nights.size,
        score = if (nights.isEmpty()) 0f else nights.sumOf { it.sleepQualityScore }.toFloat() / nights.size,
        nights = nights.size,
    )
}

@Composable
fun StatisticsScreen() {
    val c = LocalContext.current.container
    val vm: StatisticsViewModel = viewModel { StatisticsViewModel(c) }
    val st by vm.state.collectAsStateWithLifecycle()
    val s = st.stats

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_statistics), style = MaterialTheme.typography.headlineSmall)
        ChoiceChips(
            options = StatPeriod.entries.toList(),
            selected = st.period,
            label = {
                stringResource(
                    when (it) {
                        StatPeriod.DAY -> R.string.period_day
                        StatPeriod.WEEK -> R.string.period_week
                        StatPeriod.MONTH -> R.string.period_month
                        StatPeriod.YEAR -> R.string.period_year
                        StatPeriod.ALL -> R.string.period_all
                    },
                )
            },
            onSelect = vm::select,
        )
        if (st.period != StatPeriod.ALL) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.shift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous)) }
                Text(
                    if (st.from == st.to) Fmt.date(st.from) else "${Fmt.date(st.from)} – ${Fmt.date(st.to)}",
                    Modifier.weight(1f), textAlign = TextAlign.Center,
                )
                IconButton(onClick = { vm.shift(1) }, enabled = st.offset < 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next)) }
            }
        }
        if (s.nights == 0) {
            Text(stringResource(R.string.stats_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }

        SectionCard(stringResource(R.string.sleep_duration)) {
            StatGrid(
                listOf(
                    stringResource(R.string.average) to durationText(s.avgSleepMinutes.toInt()),
                    stringResource(R.string.nights) to "${s.nights}",
                    stringResource(R.string.longest) to durationText(s.maxSleepMinutes),
                    stringResource(R.string.shortest) to durationText(s.minSleepMinutes),
                ),
            )
            if (st.buckets.isNotEmpty()) {
                val monthly = st.period == StatPeriod.YEAR || st.period == StatPeriod.ALL
                BarChart(
                    st.buckets.map { b ->
                        BarEntry(
                            label = if (monthly) b.start.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) else if (st.period == StatPeriod.WEEK) Fmt.dayShort(b.start) else "${b.start.dayOfMonth}",
                            value = b.sleepMinutes / 60f,
                            display = if (b.nights == 0) "–" else durationText(b.sleepMinutes.toInt()),
                        )
                    },
                    goal = st.goalMinutes / 60f,
                    description = stringResource(R.string.cd_duration_chart),
                )
            }
        }

        HabitsCard(st)

        SectionCard(stringResource(R.string.sleep_quality)) {
            StatGrid(
                listOf(
                    stringResource(R.string.average_score) to "%.0f".format(s.avgScore),
                    stringResource(R.string.efficiency) to "%.0f %%".format(s.avgEfficiency),
                    stringResource(R.string.highest) to "${s.maxScore}",
                    stringResource(R.string.lowest) to "${s.minScore}",
                    stringResource(R.string.latency) to durationText(s.avgLatencyMinutes.toInt()),
                ),
            )
            if (st.buckets.isNotEmpty()) {
                BarChart(
                    st.buckets.map { b ->
                        BarEntry(
                            label = if (st.period == StatPeriod.YEAR || st.period == StatPeriod.ALL) "${b.start.monthValue}" else "${b.start.dayOfMonth}",
                            value = b.score,
                            display = if (b.nights == 0) "–" else "%.0f".format(b.score),
                            color = if (b.nights == 0) null else DataColors.score(b.score.toInt()),
                        )
                    },
                    height = 120.dp,
                    description = stringResource(R.string.cd_score_chart),
                )
            }
        }

        SectionCard(stringResource(R.string.regularity)) {
            StatGrid(
                listOf(
                    stringResource(R.string.avg_bedtime) to (s.avgBedtimeMinuteOfDay?.let(Fmt::minuteOfDay) ?: "–"),
                    stringResource(R.string.avg_wake_time) to (s.avgWakeMinuteOfDay?.let(Fmt::minuteOfDay) ?: "–"),
                    stringResource(R.string.bedtime_variation) to "± ${s.bedtimeSdMinutes.toInt()} min",
                    stringResource(R.string.wake_variation) to "± ${s.wakeSdMinutes.toInt()} min",
                    stringResource(R.string.regularity_index) to "%.0f / 100".format(s.regularity),
                ),
            )
        }

        SectionCard(stringResource(R.string.sleep_debt)) {
            val debt = s.sleepDebtMinutes
            Text(
                if (debt > 0) stringResource(R.string.sleep_debt_deficit, durationText(debt)) else stringResource(R.string.sleep_debt_surplus, durationText(-debt)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.sleep_debt_hint, durationText(st.goalMinutes)), style = MaterialTheme.typography.bodySmall)
        }

        SectionCard(stringResource(R.string.phase_distribution)) {
            PhaseBar(s.avgAwakeMinutes.toInt(), s.avgLightMinutes.toInt(), s.avgDeepMinutes.toInt(), s.avgRemMinutes.toInt()) { stage, v ->
                "${stageName(stage)} ${durationText(v)}"
            }
        }

        SectionCard(stringResource(R.string.sounds)) {
            StatGrid(
                listOf(
                    stringResource(R.string.snoring_per_night) to "%.1f".format(s.avgSnoreEvents),
                    stringResource(R.string.snoring_total) to durationText(s.totalSnoreMinutes.toInt()),
                    stringResource(R.string.coughing_per_night) to "%.1f".format(s.avgCoughEvents),
                    stringResource(R.string.talking_per_night) to "%.1f".format(s.avgTalkEvents),
                    stringResource(R.string.noise_per_night) to "%.1f".format(s.avgNoiseEvents),
                ),
            )
        }
        EnvironmentStatsCard(st)
        EstimateNote()
    }
}

private fun nightLabel(s: SleepSession, many: Boolean): String {
    val d = runCatching { LocalDate.parse(s.sessionDate) }.getOrNull() ?: return "?"
    return if (many) "${d.dayOfMonth}" else d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
}

/** When you go to bed, fall asleep and wake up; time in bed vs. asleep; weekday vs. weekend. */
@Composable
private fun HabitsCard(st: StatsUiState) {
    val s = st.stats
    SectionCard(stringResource(R.string.habits)) {
        StatGrid(
            listOf(
                stringResource(R.string.avg_bedtime) to (s.avgBedtimeMinuteOfDay?.let(Fmt::minuteOfDay) ?: "–"),
                stringResource(R.string.avg_fell_asleep) to (s.avgSleepOnsetMinuteOfDay?.let(Fmt::minuteOfDay) ?: "–"),
                stringResource(R.string.avg_wake_time) to (s.avgWakeMinuteOfDay?.let(Fmt::minuteOfDay) ?: "–"),
                stringResource(R.string.latency) to durationText(s.avgLatencyMinutes.toInt()),
                stringResource(R.string.avg_time_in_bed) to durationText(s.avgTimeInBedMinutes.toInt()),
                stringResource(R.string.avg_time_asleep) to durationText(s.avgSleepMinutes.toInt()),
            ),
        )
        if (st.nights.isNotEmpty()) {
            val many = st.nights.size > 7
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.sleep_schedule), style = MaterialTheme.typography.titleSmall)
            val schedule = st.nights.map { n ->
                val bed = SleepScoreCalculator.minutesSinceEvening(n.startTimestamp, n.zoneOffsetMinutes)
                val asleep = (bed + n.sleepLatencyMinutes).coerceAtMost(1439)
                val wake = (bed + n.timeInBedMinutes).coerceIn(asleep, 1439)
                ScheduleNight(
                    label = nightLabel(n, many),
                    bedMinute = bed,
                    asleepMinute = asleep,
                    wakeMinute = wake,
                    detail = "${Fmt.time(n.startTimestamp)} → ${Fmt.time(n.endTimestamp ?: n.startTimestamp)}",
                )
            }
            ScheduleChart(schedule, description = stringResource(R.string.cd_schedule_chart))
            Text(stringResource(R.string.schedule_legend), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.in_bed_vs_asleep), style = MaterialTheme.typography.titleSmall)
            val inBed = stringResource(R.string.time_in_bed)
            val asleepLabel = stringResource(R.string.asleep)
            PairedBarChart(
                st.nights.map { n ->
                    PairedEntry(
                        label = nightLabel(n, many),
                        first = n.timeInBedMinutes / 60f,
                        second = n.estimatedSleepMinutes / 60f,
                        display = "${durationText(n.estimatedSleepMinutes)} / ${durationText(n.timeInBedMinutes)} (%.0f %%)".format(n.sleepEfficiency),
                    )
                },
                firstLabel = inBed,
                secondLabel = asleepLabel,
                description = stringResource(R.string.cd_in_bed_chart),
            )
        }
        val wd = s.weekdays
        val we = s.weekends
        if (wd != null || we != null) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.weekday_vs_weekend), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.weekdays), Modifier.weight(0.7f), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.weekends), Modifier.weight(0.7f), style = MaterialTheme.typography.labelSmall)
            }
            @Composable
            fun row(label: String, a: String, b: String) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(a, Modifier.weight(0.7f))
                    Text(b, Modifier.weight(0.7f))
                }
            }
            fun t(m: Int?) = m?.let(Fmt::minuteOfDay) ?: "–"
            row(stringResource(R.string.nights), "${wd?.nights ?: 0}", "${we?.nights ?: 0}")
            row(stringResource(R.string.bedtime), t(wd?.avgBedtimeMinuteOfDay), t(we?.avgBedtimeMinuteOfDay))
            row(stringResource(R.string.wake_time), t(wd?.avgWakeMinuteOfDay), t(we?.avgWakeMinuteOfDay))
            row(stringResource(R.string.time_in_bed), wd?.let { durationText(it.avgTimeInBedMinutes.toInt()) } ?: "–", we?.let { durationText(it.avgTimeInBedMinutes.toInt()) } ?: "–")
            row(stringResource(R.string.asleep), wd?.let { durationText(it.avgSleepMinutes.toInt()) } ?: "–", we?.let { durationText(it.avgSleepMinutes.toInt()) } ?: "–")
            s.socialJetLagMinutes?.let {
                Text(stringResource(R.string.social_jet_lag, durationText(it)), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.social_jet_lag_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EnvironmentStatsCard(st: StatsUiState) {
    val s = st.stats
    if (s.avgNoiseDb == null && s.avgLux == null) return
    SectionCard(stringResource(R.string.environment)) {
        StatGrid(
            listOfNotNull(
                s.avgNoiseDb?.let { stringResource(R.string.avg_noise) to dbText(it, st.noiseCalibration) },
                s.avgLux?.let { stringResource(R.string.avg_light) to luxText(it) },
                s.avgDarkPercent?.let { stringResource(R.string.dark_share) to "%.0f %%".format(it) },
            ),
        )
        val noisy = st.nights.filter { it.hasNoiseData }
        if (noisy.size >= 2) {
            Text(stringResource(R.string.noise_per_night_chart), style = MaterialTheme.typography.labelMedium)
            BarChart(
                noisy.map { BarEntry(nightLabel(it, noisy.size > 7), (it.avgNoiseDb + st.noiseCalibration).coerceAtLeast(0f), dbText(it.avgNoiseDb, st.noiseCalibration)) },
                height = 100.dp,
                description = stringResource(R.string.cd_noise_chart),
            )
        }
        val lit = st.nights.filter { it.hasLightData }
        if (lit.size >= 2) {
            Text(stringResource(R.string.dark_per_night_chart), style = MaterialTheme.typography.labelMedium)
            BarChart(
                lit.map { BarEntry(nightLabel(it, lit.size > 7), it.darkPercent, "%.0f %%".format(it.darkPercent), DataColors.light) },
                height = 100.dp,
                description = stringResource(R.string.cd_light_chart),
            )
        }
    }
}
