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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
            Text(stringResource(R.string.tab_journal), style = MaterialTheme.typography.headlineSmall)
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
                Text(
                    if (month == LocalDate.MIN) "–" else Fmt.monthYear(month),
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(list, key = { it.id }) { s -> JournalEntry(s) { onOpen(s.id) } }
        }
    }
}

@Composable
private fun JournalEntry(s: SleepSession, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Fmt.date(s.sessionDate), style = MaterialTheme.typography.titleSmall)
                Text(durationText(s.estimatedSleepMinutes), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "${Fmt.time(s.startTimestamp)} – ${s.endTimestamp?.let { Fmt.time(it) } ?: "?"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("${s.sleepQualityScore}", style = MaterialTheme.typography.displaySmall, color = DataColors.score(s.sleepQualityScore))
        }
    }
}
