@file:OptIn(ExperimentalMaterial3Api::class)

package de.sleeptracker.app.ui.sleep

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.sleeptracker.app.R
import de.sleeptracker.app.container
import de.sleeptracker.app.tracking.StartResult
import de.sleeptracker.app.ui.Fmt
import de.sleeptracker.app.ui.SystemChecks
import de.sleeptracker.app.ui.components.ChoiceChips
import de.sleeptracker.app.ui.components.SectionCard
import de.sleeptracker.app.ui.components.SettingRow
import de.sleeptracker.app.ui.durationText
import de.sleeptracker.app.ui.theme.DataColors
import de.sleeptracker.core.SnoozeMode
import de.sleeptracker.core.TrackingMode
import kotlinx.coroutines.launch
import java.time.LocalDate

val WAKE_WINDOWS = listOf(10, 15, 20, 30, 45, 60, 90)

@Composable
fun SleepScreen(onTrackingStarted: () -> Unit, onOpenReport: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val vm: SleepViewModel = viewModel { SleepViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showTips by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val battery = remember(refresh) { SystemChecks.battery(context) }
    val alarm = state.alarm
    val mode = alarm.alarmMode

    val msgAlready = stringResource(R.string.msg_already_tracking)
    val msgNoSensor = stringResource(R.string.msg_no_sensor)
    val msgDegraded = stringResource(R.string.msg_sensor_fallback)

    fun doStart() = scope.launch {
        when (val r = vm.start()) {
            is StartResult.Started -> {
                if (r.degraded) snackbar.showSnackbar(msgDegraded)
                onTrackingStarted()
            }
            StartResult.AlreadyTracking -> onTrackingStarted()
            StartResult.NoSensorAvailable -> snackbar.showSnackbar(msgNoSensor)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
        doStart()
    }
    val ringtoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val uri = res.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            vm.setSound(uri?.toString() ?: "")
        }
    }

    LaunchedEffect(state.tracking) { if (state.tracking) onTrackingStarted() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(Fmt.fullDate(LocalDate.now()), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SnackbarHost(snackbar)

        state.interrupted.forEach { s ->
            SectionCard(stringResource(R.string.interrupted_title)) {
                Text(stringResource(R.string.interrupted_text, Fmt.time(s.startTimestamp), Fmt.date(s.sessionDate)))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.resume(s.id); onTrackingStarted() }) { Text(stringResource(R.string.interrupted_resume)) }
                    TextButton(onClick = { vm.finish(s.id) }) { Text(stringResource(R.string.interrupted_finish)) }
                    TextButton(onClick = { vm.discard(s.id) }) { Text(stringResource(R.string.interrupted_discard)) }
                }
            }
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val modes = listOf(TrackingMode.SMART_ALARM, TrackingMode.REGULAR_ALARM, TrackingMode.TRACKING_ONLY)
            modes.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { vm.setMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(modeLabel(m), maxLines = 1) }
            }
        }

        if (mode.hasAlarm) {
            SectionCard(null) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(if (mode == TrackingMode.SMART_ALARM) R.string.wake_up_latest else R.string.alarm_at),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        Fmt.minuteOfDay(alarm.targetWakeTime),
                        Modifier.clickable { showTimePicker = true }.padding(8.dp),
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Light,
                    )
                    if (mode == TrackingMode.SMART_ALARM) {
                        val start = (alarm.targetWakeTime - alarm.wakeupWindowMinutes + 1440) % 1440
                        Text(
                            stringResource(R.string.wake_window_range, Fmt.minuteOfDay(start), Fmt.minuteOfDay(alarm.targetWakeTime)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val untilMinutes = remember(alarm.targetWakeTime, refresh) {
                        val now = java.time.LocalTime.now()
                        val nowMin = now.hour * 60 + now.minute
                        ((alarm.targetWakeTime - nowMin + 1440) % 1440).let { if (it == 0) 1440 else it }
                    }
                    Text(stringResource(R.string.alarm_in, durationText(untilMinutes)), style = MaterialTheme.typography.bodySmall)
                }
                if (mode == TrackingMode.SMART_ALARM) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.wake_window), style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(
                        options = listOf(0) + WAKE_WINDOWS,
                        selected = alarm.wakeupWindowMinutes,
                        label = { if (it == 0) stringResource(R.string.no_smart_wake) else stringResource(R.string.minutes_short, it) },
                        onSelect = vm::setWindow,
                    )
                }
                SettingRow(
                    stringResource(R.string.alarm_sound),
                    value = ringtoneTitle(alarm.alarmSound),
                    icon = Icons.Filled.MusicNote,
                ) {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(
                            RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                            alarm.alarmSound.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        )
                    ringtoneLauncher.launch(intent)
                }
                SettingRow(
                    stringResource(R.string.snooze),
                    value = when (alarm.snoozeMode) {
                        SnoozeMode.OFF -> stringResource(R.string.off)
                        SnoozeMode.FIXED -> stringResource(R.string.snooze_fixed_summary, alarm.snoozeDurationMinutes, alarm.maximumSnoozes)
                        SnoozeMode.INTELLIGENT -> stringResource(R.string.snooze_intelligent)
                    },
                    icon = Icons.Filled.Snooze,
                    onClick = onOpenSettings,
                )
            }
        } else {
            SectionCard(null) {
                Text(stringResource(R.string.tracking_only_hint), style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionCard(stringResource(R.string.sensors)) {
            val user = state.user
            val sel = when {
                user.soundTrackingEnabled && user.motionTrackingEnabled -> 2
                user.soundTrackingEnabled -> 0
                else -> 1
            }
            ChoiceChips(
                options = listOf(0, 1, 2),
                selected = sel,
                label = {
                    stringResource(
                        when (it) { 0 -> R.string.sensor_sound; 1 -> R.string.sensor_motion; else -> R.string.sensor_both },
                    )
                },
                onSelect = { vm.setSensors(sound = it != 1, motion = it != 0) },
            )
            if (user.soundTrackingEnabled) {
                Text(stringResource(R.string.mic_privacy_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Readiness warnings
        val warnings = buildList {
            if (battery.percent in 0..19 && !battery.charging) add(stringResource(R.string.warn_battery_low, battery.percent))
            else if (!battery.charging) add(stringResource(R.string.warn_not_charging))
            if (mode.hasAlarm && !SystemChecks.exactAlarms(context)) add(stringResource(R.string.warn_exact_alarm))
            if (mode.hasAlarm && !SystemChecks.fullScreen(context)) add(stringResource(R.string.warn_full_screen))
            if (!SystemChecks.batteryOptimizationIgnored(context)) add(stringResource(R.string.warn_battery_optimization))
        }
        if (warnings.isNotEmpty()) {
            SectionCard(null) {
                warnings.forEach {
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            if (it.contains("%")) Icons.Filled.BatteryAlert else Icons.Filled.Warning,
                            contentDescription = null, tint = DataColors.warn,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row {
                    if (mode.hasAlarm && !SystemChecks.exactAlarms(context)) {
                        TextButton(onClick = { SystemChecks.open(context, SystemChecks.exactAlarmSettings(context)) }) { Text(stringResource(R.string.fix_exact_alarm)) }
                    }
                    if (!SystemChecks.batteryOptimizationIgnored(context)) {
                        TextButton(onClick = { SystemChecks.open(context, SystemChecks.batterySettings()) }) { Text(stringResource(R.string.fix_battery)) }
                    }
                }
            }
        }

        SectionCard(null) {
            Row(Modifier.fillMaxWidth().clickable { showTips = !showTips }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.placement_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(if (showTips) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            if (showTips) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.placement_text), style = MaterialTheme.typography.bodySmall)
            }
        }

        state.lastSession?.let { s ->
            SectionCard(stringResource(R.string.last_night)) {
                Row(Modifier.fillMaxWidth().clickable { onOpenReport(s.id) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Fmt.date(s.sessionDate), style = MaterialTheme.typography.bodyMedium)
                        Text(durationText(s.estimatedSleepMinutes), style = MaterialTheme.typography.titleLarge)
                    }
                    Text("${s.sleepQualityScore}", style = MaterialTheme.typography.displaySmall, color = DataColors.score(s.sleepQualityScore))
                }
            }
        }

        Button(
            onClick = {
                val needed = buildList {
                    if (state.user.soundTrackingEnabled && !SystemChecks.mic(context)) add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33 && !SystemChecks.notifications(context)) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (needed.isEmpty()) doStart() else permissionLauncher.launch(needed.toTypedArray())
            },
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Text(stringResource(R.string.start_tracking), style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(onClick = { refresh++ }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.battery_status, battery.percent, stringResource(if (battery.charging) R.string.charging else R.string.not_charging)))
        }
    }

    if (showTimePicker) {
        val tp = rememberTimePickerState(alarm.targetWakeTime / 60, alarm.targetWakeTime % 60, android.text.format.DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = { vm.setTarget(tp.hour * 60 + tp.minute); showTimePicker = false }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.cancel)) } },
            text = { TimePicker(state = tp) },
        )
    }
}

@Composable
fun modeLabel(m: TrackingMode): String = stringResource(
    when (m) {
        TrackingMode.SMART_ALARM -> R.string.mode_smart
        TrackingMode.REGULAR_ALARM -> R.string.mode_regular
        TrackingMode.TRACKING_ONLY -> R.string.mode_no_alarm
    },
)

@Composable
fun ringtoneTitle(uri: String): String {
    val context = LocalContext.current
    val default = stringResource(R.string.alarm_sound_default)
    return remember(uri) {
        if (uri.isBlank()) default else runCatching { RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context) }.getOrNull() ?: default
    }
}
