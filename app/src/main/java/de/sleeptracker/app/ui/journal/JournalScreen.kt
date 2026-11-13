package de.sleeptracker.app.ui.journal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import de.sleeptracker.app.ui.components.ScreenHeader
import de.sleeptracker.app.ui.components.SectionLabel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sleeptracker.app.R
import de.sleeptracker.app.container
import de.sleeptracker.app.data.db.SleepSession
import de.sleeptracker.app.ui.Fmt
import de.sleeptracker.app.ui.components.ChoiceChips
import de.sleeptracker.app.ui.durationText
import de.sleeptracker.app.ui.theme.DataColors
import java.time.LocalDate

private enum class JournalRange(val days: Long?) { WEEK(7), MONTH(31), YEAR(366), ALL(null) }

@Composable
fun JournalScreen(onOpen: (Long) -> Unit) {
    val c = LocalContext.current.container
    val sessions by c.sleep.observeCompleted().collectAsStateWithLifecycle(initialValue = emptyList())
    var range by rememberSaveable { mutableStateOf(JournalRange.ALL) }
    val cutoff = range.days?.let { LocalDate.now().minusDays(it) }
    val filtered = sessions.filter { s -> cutoff == null || runCatching { !LocalDate.parse(s.sessionDate).isBefore(cutoff) }.getOrDefault(true) }
    val grouped = filtered.groupBy { runCatching { LocalDate.parse(it.sessionDate).withDayOfMonth(1) }.getOrDefault(LocalDate.MIN) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ScreenHeader(
                stringResource(R.string.tab_journal),
                subtitle = if (filtered.isEmpty()) null else stringResource(
                    R.string.journal_summary, filtered.size,
                    durationText(filtered.sumOf { it.estimatedSleepMinutes } / filtered.size),
                    filtered.sumOf { it.sleepQualityScore } / filtered.size,
                ),
            )
            ChoiceChips(
                options = JournalRange.entries.toList(),
                selected = range,
                label = {
                    stringResource(
                        when (it) {
                            JournalRange.WEEK -> R.string.range_week
                            JournalRange.MONTH -> R.string.range_month
                            JournalRange.YEAR -> R.string.range_year
                            JournalRange.ALL -> R.string.range_all
                        },
                    )
                },
                onSelect = { range = it },
            )
        }
        if (filtered.isEmpty()) {
            item { Text(stringResource(R.string.journal_empty), Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        grouped.forEach { (month, list) ->
            item(key = "h$month") {
                SectionLabel(
                    if (month == LocalDate.MIN) "–" else Fmt.monthYear(month),
                    Modifier.padding(top = 12.dp, bottom = 2.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(list, key = { it.id }) { s -> JournalEntry(s) { onOpen(s.id) } }
        }
    }
}

@Composable
private fun JournalEntry(s: SleepSession, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(DataColors.score(s.sleepQualityScore)))
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    runCatching { LocalDate.parse(s.sessionDate) }.getOrNull()?.let { "${it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())}, ${Fmt.date(it)}" } ?: s.sessionDate,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(durationText(s.estimatedSleepMinutes), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "${Fmt.time(s.startTimestamp)} – ${s.endTimestamp?.let { Fmt.time(it) } ?: "?"}" +
                        if (s.hasPhaseData) "" else " · " + stringResource(R.string.source_sleep_cycle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("${s.sleepQualityScore}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.sleep_score).uppercase(), style = de.sleeptracker.app.ui.theme.OverlineStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            }
        }
    }
}
