@file:OptIn(ExperimentalMaterial3Api::class)

package de.sleeptracker.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sleeptracker.app.R
import de.sleeptracker.app.container
import de.sleeptracker.app.ui.Fmt
import de.sleeptracker.app.ui.journal.AudioPlayer
import de.sleeptracker.app.ui.soundName
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

@Composable
fun RecordingsScreen(onBack: () -> Unit) {
    val c = LocalContext.current.container
    val recordings by c.recordings.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val player = remember { AudioPlayer() }
    var playing by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { player.stop() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manage_recordings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (recordings.isEmpty()) item { ListItem(headlineContent = { Text(stringResource(R.string.no_recordings)) }) }
            if (error) item { ListItem(headlineContent = { Text(stringResource(R.string.recording_corrupted)) }) }
            items(recordings, key = { it.id }) { r ->
                val date = Instant.ofEpochMilli(r.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
                ListItem(
                    headlineContent = { Text(soundName(r.eventType)) },
                    supportingContent = {
                        Text("${Fmt.date(date)} ${Fmt.time(r.timestamp)} · %.1f s · %s".format(r.durationSeconds, formatBytes(r.fileSize)))
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = {
                                if (playing == r.id) { player.stop(); playing = null } else scope.launch {
                                    val bytes = c.recordings.load(r.filePath)
                                    if (bytes == null) { error = true; return@launch }
                                    runCatching { player.play(bytes) { playing = null }; playing = r.id }.onFailure { error = true }
                                }
                            }) { Icon(if (playing == r.id) Icons.Filled.Stop else Icons.Filled.PlayArrow, stringResource(R.string.play)) }
                            IconButton(onClick = { scope.launch { if (playing == r.id) player.stop(); c.recordings.delete(r) } }) {
                                Icon(Icons.Filled.DeleteOutline, stringResource(R.string.delete_recording))
                            }
                        }
                    },
                )
            }
        }
    }
}
