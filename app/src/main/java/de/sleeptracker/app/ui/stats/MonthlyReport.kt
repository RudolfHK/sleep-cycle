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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sleeptracker.analysis.DetailedStatistics
import de.sleeptracker.analysis.DetailedStatisticsCalculator
import de.sleeptracker.analysis.PeriodStatistics
import de.sleeptracker.analysis.StatisticsCalculator
import de.sleeptracker.analysis.TrendAlert
import de.sleeptracker.analysis.TrendAlertCalculator
import de.sleeptracker.app.R
import de.sleeptracker.app.container
import de.sleeptracker.app.data.db.SleepSession
import de.sleeptracker.app.data.repo.AnalysisInputs
import de.sleeptracker.app.data.repo.InsightBundle
import de.sleeptracker.app.data.repo.toNightRecord
import de.sleeptracker.app.ui.Fmt
import de.sleeptracker.app.ui.components.KpiTile
import de.sleeptracker.app.ui.components.MetricRow
import de.sleeptracker.app.ui.components.ScreenHeader
import de.sleeptracker.app.ui.components.SectionCard
import de.sleeptracker.app.ui.durationText
import de.sleeptracker.app.ui.theme.DataColors
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

private fun monthOf(s: SleepSession): YearMonth? = runCatching { YearMonth.from(LocalDate.parse(s.sessionDate)) }.getOrNull()

/** All months with recorded nights, newest first. */
@Composable
fun MonthlyReportsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = LocalContext.current.container
    val sessions by c.sleep.observeCompleted().collectAsStateWithLifecycle(initialValue = emptyList())
    val months = sessions.groupBy(::monthOf).filterKeys { it != null }.toSortedMap(compareByDescending { it })
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.monthly_reports)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item { Text(stringResource(R.string.monthly_reports_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp)) }
            if (months.isEmpty()) item { Text(stringResource(R.string.journal_empty)) }
            items(months.entries.toList(), key = { it.key.toString() }) { (ym, nights) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(ym.toString()) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(Fmt.monthYear(ym!!.atDay(1)), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${stringResource(R.string.n_nights, nights.size)} · Ø ${durationText(nights.sumOf { it.estimatedSleepMinutes } / nights.size)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val score = nights.sumOf { it.sleepQualityScore } / nights.size
                    Text("$score", style = MaterialTheme.typography.titleLarge, color = DataColors.score(score))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

private data class MonthData(
    val month: YearMonth,
    val nights: List<SleepSession>,
    val stats: PeriodStatistics,
    val previous: PeriodStatistics?,
    val detailed: DetailedStatistics,
    val trends: List<TrendAlert>,
    val bundle: InsightBundle,
    val goal: Int,
)

/** One month: KPIs vs. the previous month, highlights, changes and insights. */
@Composable
fun MonthlyReportScreen(month: String, onBack: () -> Unit, onOpenReport: (Long) -> Unit) {
    val c = LocalContext.current.container
    var data by remember { mutableStateOf<MonthData?>(null) }
    LaunchedEffect(month) {
        val ym = runCatching { YearMonth.parse(month) }.getOrNull() ?: return@LaunchedEffect
        val all = c.sleep.allCompleted()
        val nights = all.filter { monthOf(it) == ym }.sortedBy { it.startTimestamp }
        val prev = all.filter { monthOf(it) == ym.minusMonths(1) }
        val goal = c.settings.user().sleepGoalMinutes
        val endOfMonth = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        data = MonthData(
            month = ym,
            nights = nights,
            stats = StatisticsCalculator.compute(nights.map { it.toNightRecord() }, goal),
            previous = prev.takeIf { it.isNotEmpty() }?.let { StatisticsCalculator.compute(it.map { s -> s.toNightRecord() }, goal) },
            detailed = DetailedStatisticsCalculator.compute(nights.map { it.toNightRecord() }, goal),
            trends = TrendAlertCalculator.compute(all.map { it.toNightRecord() }, endOfMonth),
            bundle = runCatching { AnalysisInputs.bundle(c.database, nights) }.getOrDefault(InsightBundle()),
            goal = goal,
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(data?.let { Fmt.monthYear(it.month.atDay(1)) } ?: stringResource(R.string.monthly_reports)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        val d = data
        if (d == null) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val s = d.stats
        val p = d.previous
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader(Fmt.monthYear(d.month.atDay(1)), subtitle = stringResource(R.string.monthly_subtitle, d.nights.size))
            if (d.nights.isEmpty()) {
                Text(stringResource(R.string.stats_empty))
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KpiTile(
                    stringResource(R.string.avg_time_asleep), durationText(s.avgSleepMinutes.toInt()), Modifier.weight(1f),
                    delta = p?.let { val diff = (s.avgSleepMinutes - it.avgSleepMinutes).roundToInt(); stringResource(R.string.delta_vs_last_month, (if (diff >= 0) "+" else "−") + durationText(abs(diff))) },
                    deltaPositive = p?.let { s.avgSleepMinutes >= it.avgSleepMinutes },
                )
                KpiTile(
                    stringResource(R.string.average_score), "%.0f".format(s.avgScore), Modifier.weight(1f),
                    delta = p?.let { stringResource(R.string.delta_vs_last_month, "%+.0f".format(s.avgScore - it.avgScore)) },
                    deltaPositive = p?.let { s.avgScore >= it.avgScore },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KpiTile(
                    stringResource(R.string.efficiency), "%.0f %%".format(s.avgEfficiency), Modifier.weight(1f),
                    delta = p?.let { stringResource(R.string.delta_vs_last_month, "%+.1f pp".format(s.avgEfficiency - it.avgEfficiency)) },
                    deltaPositive = p?.let { s.avgEfficiency >= it.avgEfficiency },
                )
                KpiTile(stringResource(R.string.avg_bedtime), s.avgBedtimeMinuteOfDay?.let(Fmt::minuteOfDay) ?: "–", Modifier.weight(1f), delta = stringResource(R.string.goal_reached) + " " + "%.0f %%".format(d.detailed.goalMetPercent))
            }

            SectionCard(stringResource(R.string.monthly_highlights)) {
                val best = d.detailed.bestIndex?.let { d.nights.getOrNull(it) }
                val worst = d.detailed.worstIndex?.let { d.nights.getOrNull(it) }
                best?.let { HighlightRow(stringResource(R.string.best_night), it, onOpenReport) }
                worst?.takeIf { it.id != best?.id }?.let { HighlightRow(stringResource(R.string.worst_night), it, onOpenReport) }
                MetricRow(stringResource(R.string.longest_streak), stringResource(R.string.n_nights, d.detailed.longestGoalStreak))
                MetricRow(stringResource(R.string.longest), durationText(s.maxSleepMinutes))
                MetricRow(stringResource(R.string.shortest), durationText(s.minSleepMinutes))
                MetricRow(stringResource(R.string.sleep_debt), durationText(s.sleepDebtMinutes))
                val breathing = d.nights.map { it.avgBreathingRate }.filter { it > 0f }
                if (breathing.isNotEmpty()) MetricRow(stringResource(R.string.breathing_rate), "%.1f /min".format(breathing.average()))
                if (s.totalSnoreMinutes > 0f) MetricRow(stringResource(R.string.snoring_total), durationText(s.totalSnoreMinutes.toInt()))
            }

            TrendAlertsCard(d.trends, enoughData = true)
            TagImpactCard(d.bundle.tagImpacts.take(4))
            ChronotypeCard(d.bundle.chronotype)
            RegularityIndexCard(d.bundle.sri)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HighlightRow(label: String, n: SleepSession, onOpenReport: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpenReport(n.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${Fmt.date(n.sessionDate)} · ${durationText(n.estimatedSleepMinutes)}", style = MaterialTheme.typography.bodyMedium)
        }
        Text("${n.sleepQualityScore}", style = MaterialTheme.typography.titleLarge, color = DataColors.score(n.sleepQualityScore))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
