@file:OptIn(ExperimentalMaterial3Api::class)

package de.sleeptracker.app.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.sleeptracker.analysis.NightProfileCalculator
import de.sleeptracker.app.R
import de.sleeptracker.app.container
import de.sleeptracker.app.data.db.SleepSession
import de.sleeptracker.app.ui.Fmt
import de.sleeptracker.app.ui.components.ConfirmDialog
import de.sleeptracker.app.ui.durationText
import de.sleeptracker.core.SessionStatus
import kotlinx.coroutines.launch

/** Why a session is suggested for deletion. */
private enum class CleanupReason { SHORT, DAYTIME, INTERRUPTED }

private fun reasonOf(s: SleepSession): CleanupReason? = when {
    s.sessionStatus == SessionStatus.ACTIVE -> null
    s.sessionStatus == SessionStatus.INTERRUPTED -> CleanupReason.INTERRUPTED
    s.timeInBedMinutes < 180 -> CleanupReason.SHORT
    !NightProfileCalculator.isMainSleep(s.startTimestamp, s.timeInBedMinutes, s.zoneOffsetMinutes) -> CleanupReason.DAYTIME
    else -> null
}

/** Finds very short, daytime and interrupted recordings (e.g. test runs) and deletes them in bulk. */
@Composable
fun CleanupScreen(onBack: () -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var candidates by remember { mutableStateOf<List<Pair<SleepSession, CleanupReason>>?>(null) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        val active = c.runtime.activeSession()
        candidates = c.database.sessionDao().all()
            .filter { it.id != active }
            .mapNotNull { s -> reasonOf(s)?.let { s to it } }
            .sortedByDescending { it.first.startTimestamp }
        selected = candidates.orEmpty().map { it.first.id }.toSet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cleanup_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    val list = candidates.orEmpty()
                    if (list.isNotEmpty()) {
                        TextButton(onClick = { selected = if (selected.size == list.size) emptySet() else list.map { it.first.id }.toSet() }) {
                            Text(stringResource(if (selected.size == list.size) R.string.select_none else R.string.select_all))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!candidates.isNullOrEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Button(
                            onClick = { confirm = true },
                            enabled = selected.isNotEmpty() && !busy,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().padding(16.dp).height(48.dp),
                        ) { Text(stringResource(R.string.cleanup_delete, selected.size)) }
                    }
                }
            }
        },
    ) { padding ->
        val list = candidates
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item {
                Text(stringResource(R.string.cleanup_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (list != null && list.isEmpty()) {
                item { Text(stringResource(R.string.cleanup_none), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp)) }
            }
            items(list.orEmpty(), key = { it.first.id }) { (s, reason) ->
                Row(
                    Modifier.fillMaxWidth().clickable { selected = if (s.id in selected) selected - s.id else selected + s.id }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(s.id in selected, onCheckedChange = { selected = if (it) selected + s.id else selected - s.id })
                    Column(Modifier.weight(1f)) {
                        Text("${Fmt.date(s.sessionDate)} · ${Fmt.time(s.startTimestamp)}–${s.endTimestamp?.let { Fmt.time(it) } ?: "?"}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${durationText(s.timeInBedMinutes)} · " + stringResource(
                                when (reason) {
                                    CleanupReason.SHORT -> R.string.cleanup_reason_short
                                    CleanupReason.DAYTIME -> R.string.cleanup_reason_daytime
                                    CleanupReason.INTERRUPTED -> R.string.cleanup_reason_interrupted
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    if (confirm) {
        ConfirmDialog(
            stringResource(R.string.cleanup_title),
            stringResource(R.string.cleanup_confirm, selected.size),
            stringResource(R.string.delete),
            onConfirm = {
                busy = true
                scope.launch {
                    selected.forEach { runCatching { c.sleep.deleteSession(it, deleteRecordings = true) } }
                    busy = false
                    reload++
                }
            },
            onDismiss = { confirm = false },
        )
    }
}
