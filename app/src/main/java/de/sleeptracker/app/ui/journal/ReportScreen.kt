@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.sleeptracker.app.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.sleeptracker.analysis.SleepStager
import de.sleeptracker.app.AppContainer
import de.sleeptracker.app.R
import de.sleeptracker.app.container
import de.sleeptracker.app.data.db.JournalTags
import de.sleeptracker.app.data.db.SleepSession
import de.sleeptracker.app.data.db.SoundEventRecord
import de.sleeptracker.app.data.repo.SessionDetails
import de.sleeptracker.app.ui.Fmt
import de.sleeptracker.app.ui.components.ActivityChart
import de.sleeptracker.app.ui.components.EpisodeTimeline
import de.sleeptracker.audio.SoundEpisodes
import de.sleeptracker.core.SoundEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import de.sleeptracker.app.ui.components.ChartPanel
import de.sleeptracker.app.ui.components.rememberChartZoom
import androidx.compose.foundation.layout.width
import de.sleeptracker.app.ui.components.LineChart
import de.sleeptracker.app.ui.components.TimeAxis
import de.sleeptracker.app.data.db.UserSettings
import de.sleeptracker.analysis.EnvironmentSummary
import de.sleeptracker.app.ui.components.EstimateNote
import de.sleeptracker.app.ui.components.Hypnogram
import de.sleeptracker.app.ui.components.PhaseBar
import de.sleeptracker.app.ui.components.ScoreRing
import de.sleeptracker.app.ui.components.SectionCard
import de.sleeptracker.app.ui.components.SoundTimeline
import de.sleeptracker.app.ui.components.StageRun
import de.sleeptracker.app.ui.components.StatGrid
import de.sleeptracker.app.ui.durationText
import de.sleeptracker.app.ui.soundName
import de.sleeptracker.app.ui.stageName
import de.sleeptracker.app.ui.theme.DataColors
import de.sleeptracker.core.SoundEventType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ReportViewModel(private val c: AppContainer, private val sessionId: Long) : ViewModel() {
    private val _details = MutableStateFlow<SessionDetails?>(null)
    val details: StateFlow<SessionDetails?> = _details
    private val _previous = MutableStateFlow<SleepSession?>(null)
    val previous: StateFlow<SleepSession?> = _previous
    private val _missing = MutableStateFlow(false)
    val missing: StateFlow<Boolean> = _missing
    val player = AudioPlayer()
    val playing = MutableStateFlow<Long?>(null)
    val playError = MutableStateFlow(false)

    init { reload() }

    fun reload() = viewModelScope.launch {
        val d = c.sleep.details(sessionId)
        _details.value = d
        _missing.value = d == null
        if (d != null) _previous.value = c.sleep.previousAverage(d.session.startTimestamp)
    }

    fun reanalyze() = viewModelScope.launch {
        val s = c.sleep.session(sessionId) ?: return@launch
        c.sleep.finalizeSession(sessionId, s.endTimestamp ?: s.startTimestamp)
        reload()
    }

    fun saveJournal(note: String, mood: Int, tags: Set<String>) = viewModelScope.launch {
        c.sleep.saveJournal(sessionId, note, mood, tags)
        reload()
    }

    fun delete(deleteRecordings: Boolean, done: () -> Unit) = viewModelScope.launch {
        player.stop()
        c.sleep.deleteSession(sessionId, deleteRecordings)
        done()
    }

    fun togglePlay(event: SoundEventRecord) = viewModelScope.launch {
        if (playing.value == event.id) { player.stop(); playing.value = null; return@launch }
        val path = event.audioFilePath ?: return@launch
        val bytes = c.recordings.load(path)
        if (bytes == null) { playError.value = true; return@launch }
        try {
            player.play(bytes) { playing.value = null }
            playing.value = event.id
        } catch (e: Exception) {
            playError.value = true
        }
    }

    fun deleteRecording(event: SoundEventRecord) = viewModelScope.launch {
        event.audioFilePath?.let { c.recordings.deleteByPath(it) }
        reload()
    }

    override fun onCleared() { player.stop() }
}

@Composable
fun ReportScreen(sessionId: Long, onBack: () -> Unit, onDeleted: () -> Unit) {
    val c = LocalContext.current.container
    val vm: ReportViewModel = viewModel(key = "report$sessionId") { ReportViewModel(c, sessionId) }
    val details by vm.details.collectAsStateWithLifecycle()
    val previous by vm.previous.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val playError by vm.playError.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val user by c.settings.userSettings.collectAsStateWithLifecycle(initialValue = UserSettings())
    val calibration = user.noiseCalibrationDb

    DisposableEffect(Unit) { onDispose { vm.player.stop() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(details?.session?.let { Fmt.date(it.sessionDate) } ?: stringResource(R.string.report_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    if (details?.session?.hasPhaseData == true && details?.epochs?.isNotEmpty() == true) {
                        IconButton(onClick = { vm.reanalyze() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.reanalyze)) }
                    }
                    if (details != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete_session)) }
                },
            )
        },
    ) { padding ->
        val d = details
        if (d == null) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (missing) Text(stringResource(R.string.report_missing)) else CircularProgressIndicator()
            }
            return@Scaffold
        }
        val s = d.session
        val start = s.startTimestamp
        val end = s.endTimestamp ?: (start + s.timeInBedMinutes * 60_000L)
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Hero: score + the three figures people look at first.
            SectionCard(null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ScoreRing(s.sleepQualityScore, size = 112.dp, label = stringResource(R.string.sleep_score))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        HeroFigure(stringResource(R.string.asleep), durationText(s.estimatedSleepMinutes))
                        HeroFigure(stringResource(R.string.time_in_bed), durationText(s.timeInBedMinutes))
                        HeroFigure(stringResource(R.string.efficiency), "%.0f %%".format(s.sleepEfficiency))
                    }
                }
                if (!s.hasPhaseData) {
                    Text(
                        stringResource(R.string.imported_from_sleep_cycle),
                        Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                EstimateNote(Modifier.padding(top = 8.dp))
            }

            if (s.hasPhaseData && d.epochs.isNotEmpty()) de.sleeptracker.app.ui.stats.NightOverviewPanel(d, calibration)

            SectionCard(stringResource(R.string.report_general)) {
                StatGrid(
                    listOf(
                        stringResource(R.string.bedtime) to Fmt.time(start),
                        stringResource(R.string.fell_asleep) to Fmt.time(start + s.sleepLatencyMinutes * 60_000L),
                        stringResource(R.string.wake_time) to Fmt.time(end),
                        stringResource(R.string.asleep_of_in_bed) to "%.0f %%".format(s.sleepEfficiency),
                        stringResource(R.string.time_in_bed) to durationText(s.timeInBedMinutes),
                        stringResource(R.string.sleep_duration) to durationText(s.estimatedSleepMinutes),
                        stringResource(R.string.latency) to durationText(s.sleepLatencyMinutes),
                        stringResource(R.string.interruptions) to "${s.interruptions}",
                        stringResource(R.string.stability) to "%.0f %%".format(s.stability * 100),
                    ),
                )
                s.alarmRangMillis?.let { Text(stringResource(R.string.alarm_rang_at, Fmt.time(it)), style = MaterialTheme.typography.bodySmall) }
                if (s.externalRegularity >= 0 || s.breathingDisruptionsPerHour >= 0f) {
                    StatGrid(
                        listOfNotNull(
                            s.externalRegularity.takeIf { it >= 0 }?.let { stringResource(R.string.sleep_cycle_regularity) to "$it %" },
                            s.breathingDisruptionsPerHour.takeIf { it >= 0f }?.let { stringResource(R.string.breathing_disruptions) to "%.1f / h".format(it) },
                        ),
                    )
                }
            }

            val hypnoZoom = rememberChartZoom(((end - start) / 60_000L).toInt().coerceAtLeast(1) / 5, s.id)
            ChartPanel(stringResource(R.string.sleep_phases), if (d.stages.isEmpty()) null else hypnoZoom) { expanded ->
                val runs = d.stages.map { StageRun(it.timestamp, it.durationSeconds * 1000L, it.stageType) }
                if (runs.isEmpty()) {
                    Text(stringResource(if (s.hasPhaseData) R.string.no_data else R.string.imported_no_phases), style = MaterialTheme.typography.bodySmall)
                    if (!s.hasPhaseData) {
                        Spacer(Modifier.height(8.dp))
                        StatGrid(
                            listOf(
                                stringResource(R.string.asleep) to durationText(s.estimatedSleepMinutes),
                                stringResource(R.string.stage_awake) to durationText(s.awakeMinutes),
                            ),
                        )
                    }
                } else {
                    Hypnogram(runs, start, end, description = stringResource(R.string.cd_hypnogram), zoom = hypnoZoom, height = if (expanded) 300.dp else 150.dp)
                    Spacer(Modifier.height(12.dp))
                    PhaseBar(s.awakeMinutes, s.lightMinutes, s.deepMinutes, s.remMinutes) { stage, v -> "${stageName(stage)} ${durationText(v)}" }
                }
            }

            SectionCard(stringResource(R.string.movement)) {
                val activity = d.epochs.map { SleepStager.activityOf(it).let { a -> if (a.isNaN()) 0f else a } }
                if (activity.isNotEmpty()) ActivityChart(activity, description = stringResource(R.string.cd_movement), zoom = hypnoZoom)
                Text(stringResource(R.string.movements_per_hour, s.movementsPerHour), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.large_movements, d.movements.size), style = MaterialTheme.typography.bodySmall)
            }

            SectionCard(stringResource(R.string.sounds)) {
                StatGrid(
                    listOf(
                        stringResource(R.string.snoring) to "${s.snoreEvents}",
                        stringResource(R.string.snoring_duration) to durationText(s.snoreSeconds / 60),
                        stringResource(R.string.coughing) to "${s.coughEvents}",
                        stringResource(R.string.talking) to "${s.talkEvents}",
                        stringResource(R.string.noise) to "${s.noiseEvents}",
                    ),
                )
                if (d.epochs.any { it.snoreSeconds > 0 }) {
                    Text(stringResource(R.string.snoring_graph), style = MaterialTheme.typography.labelMedium)
                    ActivityChart(d.epochs.map { it.snoreSeconds }, color = DataColors.sound(SoundEventType.SNORE), height = 50.dp, description = stringResource(R.string.snoring_graph))
                }
                if (d.soundEvents.isNotEmpty()) {
                    val episodes = remember(d.soundEvents) {
                        SoundEpisodes.group(
                            d.soundEvents.map { SoundEvent(it.timestamp, (it.durationSeconds * 1000).toLong(), it.eventType, it.confidence, it.intensityDb) },
                        )
                    }
                    EpisodeTimeline(episodes, start, end, description = stringResource(R.string.cd_sound_timeline))
                    Spacer(Modifier.height(8.dp))
                    var showAll by remember { mutableStateOf(false) }
                    episodes.forEach { ep ->
                        val withAudio = ep.eventIndices.map { d.soundEvents[it] }.firstOrNull { it.audioFilePath != null }
                        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(width = 3.dp, height = 28.dp).background(DataColors.sound(ep.type)))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${Fmt.time(ep.startMillis)}–${Fmt.time(ep.endMillis)}  ${soundName(ep.type)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    stringResource(
                                        R.string.episode_detail,
                                        ep.eventIndices.sumOf { d.soundEvents[it].eventCount },
                                        durationSecondsText(
                                            ep.eventIndices.sumOf { i -> d.soundEvents[i].let { e -> if (e.soundSeconds > 0f) e.soundSeconds else e.durationSeconds }.toDouble() }.toLong(),
                                        ),
                                        "%.0f".format(ep.peakDb),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (withAudio != null) {
                                IconButton(onClick = { vm.togglePlay(withAudio) }) {
                                    Icon(if (playing == withAudio.id) Icons.Filled.Stop else Icons.Filled.PlayArrow, stringResource(R.string.play))
                                }
                                IconButton(onClick = { vm.deleteRecording(withAudio) }) {
                                    Icon(Icons.Filled.DeleteOutline, stringResource(R.string.delete_recording))
                                }
                            }
                        }
                    }
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(stringResource(if (showAll) R.string.hide_events else R.string.show_all_events, d.soundEvents.size))
                    }
                    if (showAll) {
                        d.soundEvents.forEach { e ->
                            Text(
                                "${Fmt.time(e.timestamp)}  ${soundName(e.eventType)} · %.1f s · %.0f dB".format(e.durationSeconds, e.intensityDb),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (playError) Text(stringResource(R.string.recording_corrupted), color = MaterialTheme.colorScheme.error)
            }

            EnvironmentCard(d, calibration)

            previous?.let { p -> ComparisonCard(s, p) }

            JournalCard(d) { note, mood, tags -> vm.saveJournal(note, mood, tags) }
        }
    }

    if (confirmDelete) {
        var alsoRecordings by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_session)) },
            text = {
                Column {
                    Text(stringResource(R.string.delete_session_text))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(alsoRecordings, onCheckedChange = { alsoRecordings = it })
                        Text(stringResource(R.string.delete_recordings_too))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(alsoRecordings, onDeleted) }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Noise and light over the night, with the per-night figures. */
@Composable
private fun EnvironmentCard(d: SessionDetails, calibration: Int) {
    val zoom = rememberChartZoom(d.epochs.size.coerceAtLeast(1), d.session.id)
    val s = d.session
    SectionCard(stringResource(R.string.environment)) {
        Text(stringResource(R.string.noise_level), style = MaterialTheme.typography.titleSmall)
        if (!s.hasNoiseData) {
            Text(stringResource(R.string.noise_not_measured), style = MaterialTheme.typography.bodySmall)
        } else {
            StatGrid(
                listOfNotNull(
                    stringResource(R.string.average) to dbText(s.avgNoiseDb, calibration),
                    if (s.hasPhaseData) stringResource(R.string.peak) to dbText(s.peakNoiseDb, calibration) else null,
                    if (s.hasPhaseData) stringResource(R.string.loud_time) to durationText(s.loudMinutes) else null,
                ),
            )
            if (d.epochs.isNotEmpty()) {
                LineChart(
                    d.epochs.map { if (it.hasSound && it.soundLevelDb > -100f) it.soundLevelDb + calibration else Float.NaN },
                    color = DataColors.sound(SoundEventType.NOISE_OTHER),
                    format = { "%.0f dB".format(it) },
                    zoom = zoom,
                    description = stringResource(R.string.cd_noise_chart),
                )
                val span = ((s.endTimestamp ?: s.startTimestamp) - s.startTimestamp).toFloat()
                TimeAxis(s.startTimestamp + (zoom.start * span).toLong(), s.startTimestamp + (zoom.end * span).toLong())
            }
            Text(stringResource(R.string.noise_uncalibrated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.light_level), style = MaterialTheme.typography.titleSmall)
        if (!s.hasLightData) {
            Text(
                stringResource(if (s.lightReadings == 0) R.string.light_no_readings else R.string.light_not_measured),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            StatGrid(
                listOfNotNull(
                    stringResource(R.string.average) to luxText(s.avgLux),
                    if (s.maxLux >= 0f) stringResource(R.string.brightest) to luxText(s.maxLux) else null,
                    if (s.darkPercent >= 0f) stringResource(R.string.dark_share) to "%.0f %%".format(s.darkPercent) else null,
                    if (s.hasPhaseData) stringResource(R.string.bright_time) to durationText(s.brightMinutes) else null,
                    if (s.luxAtOnset >= 0) stringResource(R.string.light_at_onset) to luxText(s.luxAtOnset) else null,
                ),
            )
            if (d.epochs.isNotEmpty()) {
                LineChart(
                    d.epochs.map { if (it.hasLight && it.lightLux >= 0f) it.lightLux else Float.NaN },
                    color = DataColors.light,
                    format = ::luxText,
                    reference = EnvironmentSummary.DARK_LUX,
                    zoom = zoom,
                    description = stringResource(R.string.cd_light_chart),
                )
                val span = ((s.endTimestamp ?: s.startTimestamp) - s.startTimestamp).toFloat()
                TimeAxis(s.startTimestamp + (zoom.start * span).toLong(), s.startTimestamp + (zoom.end * span).toLong())
            }
            Text(stringResource(R.string.light_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun durationSecondsText(seconds: Long): String =
    if (seconds < 60) "$seconds s" else durationText((seconds / 60).toInt())

fun dbText(dbfs: Float, calibration: Int) = "%.0f dB".format(dbfs + calibration)
fun luxText(lux: Float) = if (lux < 10f) "%.1f lx".format(lux) else "%.0f lx".format(lux)

@Composable
private fun HeroFigure(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ComparisonCard(s: SleepSession, avg: SleepSession) {
    SectionCard(stringResource(R.string.compare_previous)) {
        @Composable
        fun row(label: String, value: String, avgValue: String, better: Boolean?) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(value, Modifier.weight(0.6f), color = when (better) { true -> DataColors.good; false -> DataColors.bad; null -> MaterialTheme.colorScheme.onSurface })
                Text(avgValue, Modifier.weight(0.6f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.this_night), Modifier.weight(0.6f), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.average_7), Modifier.weight(0.6f), style = MaterialTheme.typography.labelSmall)
        }
        row(stringResource(R.string.sleep_score), "${s.sleepQualityScore}", "${avg.sleepQualityScore}", s.sleepQualityScore > avg.sleepQualityScore)
        row(stringResource(R.string.sleep_duration), durationText(s.estimatedSleepMinutes), durationText(avg.estimatedSleepMinutes), s.estimatedSleepMinutes > avg.estimatedSleepMinutes)
        row(stringResource(R.string.efficiency), "%.0f %%".format(s.sleepEfficiency), "%.0f %%".format(avg.sleepEfficiency), s.sleepEfficiency > avg.sleepEfficiency)
        row(stringResource(R.string.latency), durationText(s.sleepLatencyMinutes), durationText(avg.sleepLatencyMinutes), s.sleepLatencyMinutes < avg.sleepLatencyMinutes)
        row(stringResource(R.string.stage_deep), durationText(s.deepMinutes), durationText(avg.deepMinutes), null)
        row(stringResource(R.string.stage_rem), durationText(s.remMinutes), durationText(avg.remMinutes), null)
        row(stringResource(R.string.snoring), "${s.snoreEvents}", "${avg.snoreEvents}", s.snoreEvents < avg.snoreEvents)
        row(stringResource(R.string.interruptions), "${s.interruptions}", "${avg.interruptions}", s.interruptions < avg.interruptions)
    }
}

@Composable
private fun JournalCard(d: SessionDetails, onSave: (String, Int, Set<String>) -> Unit) {
    val j = d.journal
    var note by rememberSaveable(j?.id) { mutableStateOf(j?.note ?: "") }
    var mood by rememberSaveable(j?.id) { mutableStateOf(j?.mood ?: 0) }
    var tags by remember(j?.id) { mutableStateOf(j?.personalTags?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(note, mood, tags) { saved = false }
    SectionCard(stringResource(R.string.sleep_notes)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JournalTags.ALL.forEach { t ->
                FilterChip(
                    selected = t in tags,
                    onClick = { tags = if (t in tags) tags - t else tags + t },
                    label = { Text(tagLabel(t)) },
                )
            }
        }
        Text(stringResource(R.string.mood), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..5).forEach { m ->
                FilterChip(selected = mood == m, onClick = { mood = if (mood == m) 0 else m }, label = { Text(listOf("😫", "🙁", "😐", "🙂", "😄")[m - 1]) })
            }
        }
        OutlinedTextField(note, { note = it.take(2000) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.note)) }, minLines = 2)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onSave(note, mood, tags); saved = true }) { Text(stringResource(if (saved) R.string.saved else R.string.save)) }
    }
}

@Composable
fun tagLabel(tag: String): String = stringResource(
    when (tag) {
        "stress" -> R.string.tag_stress
        "coffee" -> R.string.tag_coffee
        "alcohol" -> R.string.tag_alcohol
        "exercise" -> R.string.tag_exercise
        "late_meal" -> R.string.tag_late_meal
        "travel" -> R.string.tag_travel
        "illness" -> R.string.tag_illness
        else -> R.string.tag_unusual_place
    },
)
