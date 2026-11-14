package de.sleeptracker.app.ui.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.sleeptracker.app.AppContainer
import de.sleeptracker.app.R
import de.sleeptracker.app.container
import de.sleeptracker.app.data.db.AlarmSettings
import de.sleeptracker.app.data.db.ThemeMode
import de.sleeptracker.app.data.db.UserSettings
import de.sleeptracker.app.data.db.VolumeButtonAction
import de.sleeptracker.app.ui.SystemChecks
import de.sleeptracker.app.ui.components.ChoiceChips
import de.sleeptracker.app.ui.components.ConfirmDialog
import de.sleeptracker.app.ui.components.SectionCard
import de.sleeptracker.app.ui.components.SettingRow
import de.sleeptracker.app.ui.components.SettingSlider
import de.sleeptracker.app.ui.components.SettingSwitch
import de.sleeptracker.app.ui.durationText
import de.sleeptracker.app.ui.sleep.ringtoneTitle
import de.sleeptracker.core.SnoozeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class SettingsViewModel(val c: AppContainer) : ViewModel() {
    val user = c.settings.userSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())
    val alarm = c.settings.alarmSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmSettings())
    val storage = c.recordings.totalBytes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun user(t: (UserSettings) -> UserSettings) = viewModelScope.launch { c.settings.updateUser(t) }
    fun alarm(t: (AlarmSettings) -> AlarmSettings) = viewModelScope.launch { c.settings.updateAlarm(t) }

    fun setRetention(days: Int) = viewModelScope.launch {
        c.settings.updateUser { it.copy(automaticRecordingDeletionDays = days) }
        c.recordings.applyRetention(days)
        c.recordings.deleteExpired()
    }

    fun deleteAllRecordings() = viewModelScope.launch { c.recordings.deleteAll() }

    fun deleteAllData(done: () -> Unit) = viewModelScope.launch {
        c.tracking.stopAndFinalize()
        c.alarmScheduler.cancel()
        c.exporter.deleteAllData()
        c.runtime.clear()
        done()
    }
}

private val SNOOZE_DURATIONS = listOf(1, 2, 5, 10, 15, 20)
private val RETENTION = listOf(0, 7, 14, 30, 90)

@Composable
fun SettingsScreen(onOpenRecordings: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val vm: SettingsViewModel = viewModel { SettingsViewModel(c) }
    val user by vm.user.collectAsStateWithLifecycle()
    val alarm by vm.alarm.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val hasLightSensor = remember {
        context.getSystemService(android.hardware.SensorManager::class.java)?.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT) != null
    }

    val msgDone = stringResource(R.string.msg_done)
    val msgFailed = stringResource(R.string.msg_failed)
    fun runIo(block: suspend () -> Unit) = scope.launch {
        val ok = runCatching { block() }.isSuccess
        snackbar.showSnackbar(if (ok) msgDone else msgFailed)
    }

    val today = LocalDate.now()
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runIo { c.exporter.exportCsv(uri) }
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runIo { c.exporter.exportJson(uri) }
    }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) runIo { c.exporter.createBackup(uri) }
    }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var importPreview by remember { mutableStateOf<de.sleeptracker.app.data.importer.ImportPreview?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    val msgImportUnknown = stringResource(R.string.import_unknown_format)
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importBusy = true
            val result = runCatching { c.sleepCycleImport.preview(uri) }
            importBusy = false
            result.onSuccess { p ->
                if (p.format == de.sleeptracker.importer.ParseResult.Format.UNKNOWN) snackbar.showSnackbar(msgImportUnknown) else importPreview = p
            }.onFailure { snackbar.showSnackbar(msgFailed) }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { restoreUri = uri; confirm = "restore" }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        de.sleeptracker.app.ui.components.ScreenHeader(stringResource(R.string.tab_settings), subtitle = stringResource(R.string.settings_subtitle))
        SnackbarHost(snackbar)

        SectionCard(stringResource(R.string.settings_alarm)) {
            SettingRow(stringResource(R.string.alarm_sound), ringtoneTitle(alarm.alarmSound))
            SettingSlider(stringResource(R.string.alarm_volume), alarm.alarmVolume.toFloat(), "${alarm.alarmVolume} %", 10f..100f) { v ->
                vm.alarm { it.copy(alarmVolume = v.toInt()) }
            }
            SettingSwitch(stringResource(R.string.gradual_volume), alarm.gradualVolumeEnabled, { v -> vm.alarm { it.copy(gradualVolumeEnabled = v) } })
            SettingSwitch(stringResource(R.string.vibration), alarm.vibrationEnabled, { v -> vm.alarm { it.copy(vibrationEnabled = v) } })
            Text(stringResource(R.string.snooze), style = MaterialTheme.typography.labelLarge)
            ChoiceChips(
                SnoozeMode.entries.toList(), alarm.snoozeMode,
                label = {
                    stringResource(
                        when (it) { SnoozeMode.OFF -> R.string.off; SnoozeMode.FIXED -> R.string.snooze_fixed; SnoozeMode.INTELLIGENT -> R.string.snooze_intelligent },
                    )
                },
                onSelect = { m -> vm.alarm { it.copy(snoozeMode = m) } },
            )
            if (alarm.snoozeMode == SnoozeMode.INTELLIGENT) {
                Text(stringResource(R.string.snooze_intelligent_hint), style = MaterialTheme.typography.bodySmall)
            }
            if (alarm.snoozeMode != SnoozeMode.OFF) {
                Text(stringResource(R.string.snooze_duration), style = MaterialTheme.typography.labelLarge)
                ChoiceChips(SNOOZE_DURATIONS, alarm.snoozeDurationMinutes, label = { stringResource(R.string.minutes_short, it) }, onSelect = { d ->
                    vm.alarm { it.copy(snoozeDurationMinutes = d) }
                })
                SettingSlider(stringResource(R.string.max_snoozes), alarm.maximumSnoozes.toFloat(), "${alarm.maximumSnoozes}", 1f..10f, steps = 8) { v ->
                    vm.alarm { it.copy(maximumSnoozes = v.toInt()) }
                }
                SettingSwitch(stringResource(R.string.double_tap_snooze), alarm.doubleTapSnoozeEnabled, { v -> vm.alarm { it.copy(doubleTapSnoozeEnabled = v) } })
                SettingSwitch(stringResource(R.string.pickup_snooze), alarm.pickupSnoozeEnabled, { v -> vm.alarm { it.copy(pickupSnoozeEnabled = v) } })
            }
            Text(stringResource(R.string.volume_buttons), style = MaterialTheme.typography.labelLarge)
            ChoiceChips(
                VolumeButtonAction.entries.toList(), alarm.volumeButtonAction,
                label = {
                    stringResource(
                        when (it) { VolumeButtonAction.NONE -> R.string.off; VolumeButtonAction.SNOOZE -> R.string.snooze; VolumeButtonAction.STOP -> R.string.alarm_stop },
                    )
                },
                onSelect = { a -> vm.alarm { it.copy(volumeButtonAction = a) } },
            )
            SettingSwitch(
                stringResource(R.string.alarm_confirmation), alarm.confirmationRequired,
                { v -> vm.alarm { it.copy(confirmationRequired = v) } },
                subtitle = stringResource(R.string.alarm_confirmation_hint),
            )
        }

        SectionCard(stringResource(R.string.settings_tracking)) {
            SettingSwitch(stringResource(R.string.sensor_sound), user.soundTrackingEnabled, { v ->
                if (v || user.motionTrackingEnabled) vm.user { it.copy(soundTrackingEnabled = v) }
            }, subtitle = stringResource(R.string.mic_privacy_hint))
            SettingSwitch(stringResource(R.string.sensor_motion), user.motionTrackingEnabled, { v ->
                if (v || user.soundTrackingEnabled) vm.user { it.copy(motionTrackingEnabled = v) }
            })
            SettingSwitch(
                stringResource(R.string.light_tracking), user.lightTrackingEnabled,
                { v -> vm.user { it.copy(lightTrackingEnabled = v) } },
                subtitle = stringResource(if (hasLightSensor) R.string.light_tracking_hint else R.string.light_sensor_missing),
                enabled = hasLightSensor,
            )
            SettingSlider(
                stringResource(R.string.noise_calibration), user.noiseCalibrationDb.toFloat(),
                "+${user.noiseCalibrationDb} dB", 60f..130f,
            ) { v -> vm.user { it.copy(noiseCalibrationDb = v.toInt()) } }
            Text(stringResource(R.string.noise_calibration_hint), style = MaterialTheme.typography.bodySmall)
            SettingSlider(stringResource(R.string.motion_sensitivity), user.motionSensitivity, "%.0f %%".format(user.motionSensitivity * 100), 0f..1f) { v ->
                vm.user { it.copy(motionSensitivity = v) }
            }
            SettingSlider(
                stringResource(R.string.sound_sensitivity), user.soundSensitivity,
                "%.0f %% · %.1f dB".format(user.soundSensitivity * 100, 11f - 7f * user.soundSensitivity), 0f..1f,
            ) { v ->
                vm.user { it.copy(soundSensitivity = v) }
            }
            SettingSlider(stringResource(R.string.sleep_goal), user.sleepGoalMinutes.toFloat(), durationText(user.sleepGoalMinutes), 300f..660f, steps = 23) { v ->
                vm.user { it.copy(sleepGoalMinutes = (v / 15).toInt() * 15) }
            }
            Text(stringResource(R.string.placement_text), style = MaterialTheme.typography.bodySmall)
        }

        SectionCard(stringResource(R.string.settings_sound_detection)) {
            SettingSwitch(stringResource(R.string.snoring), user.detectSnoring, { v -> vm.user { it.copy(detectSnoring = v) } })
            SettingSwitch(stringResource(R.string.coughing), user.detectCoughing, { v -> vm.user { it.copy(detectCoughing = v) } })
            SettingSwitch(stringResource(R.string.talking), user.detectTalking, { v -> vm.user { it.copy(detectTalking = v) } }, subtitle = stringResource(R.string.talk_no_transcription))
            SettingSwitch(stringResource(R.string.noise), user.detectNoise, { v -> vm.user { it.copy(detectNoise = v) } })
            SettingSwitch(
                stringResource(R.string.audio_recording), user.audioRecordingEnabled,
                { v -> vm.user { it.copy(audioRecordingEnabled = v) } },
                subtitle = stringResource(R.string.audio_recording_hint),
            )
            Text(stringResource(R.string.auto_delete), style = MaterialTheme.typography.labelLarge)
            ChoiceChips(RETENTION, user.automaticRecordingDeletionDays, label = {
                if (it == 0) stringResource(R.string.never) else stringResource(R.string.days, it)
            }, onSelect = vm::setRetention)
            SettingRow(stringResource(R.string.storage_used), formatBytes(storage))
            SettingRow(stringResource(R.string.manage_recordings), icon = Icons.AutoMirrored.Filled.KeyboardArrowRight, onClick = onOpenRecordings)
            TextButton(onClick = { confirm = "recordings" }) { Text(stringResource(R.string.delete_all_recordings)) }
        }

        SectionCard(stringResource(R.string.settings_score)) {
            Text(stringResource(R.string.score_weights_hint), style = MaterialTheme.typography.bodySmall)
            SettingSlider(stringResource(R.string.sleep_duration), user.weightDuration.toFloat(), "${user.weightDuration}", 0f..50f) { v -> vm.user { it.copy(weightDuration = v.toInt()) } }
            SettingSlider(stringResource(R.string.efficiency), user.weightEfficiency.toFloat(), "${user.weightEfficiency}", 0f..50f) { v -> vm.user { it.copy(weightEfficiency = v.toInt()) } }
            SettingSlider(stringResource(R.string.interruptions), user.weightInterruptions.toFloat(), "${user.weightInterruptions}", 0f..50f) { v -> vm.user { it.copy(weightInterruptions = v.toInt()) } }
            SettingSlider(stringResource(R.string.sleep_phases), user.weightPhases.toFloat(), "${user.weightPhases}", 0f..50f) { v -> vm.user { it.copy(weightPhases = v.toInt()) } }
            SettingSlider(stringResource(R.string.regularity), user.weightRegularity.toFloat(), "${user.weightRegularity}", 0f..50f) { v -> vm.user { it.copy(weightRegularity = v.toInt()) } }
            TextButton(onClick = {
                val d = UserSettings()
                vm.user { it.copy(weightDuration = d.weightDuration, weightEfficiency = d.weightEfficiency, weightInterruptions = d.weightInterruptions, weightPhases = d.weightPhases, weightRegularity = d.weightRegularity) }
            }) { Text(stringResource(R.string.reset_defaults)) }
        }

        SectionCard(stringResource(R.string.settings_appearance)) {
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.labelLarge)
            ChoiceChips(ThemeMode.entries.toList(), user.theme, label = {
                stringResource(when (it) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark })
            }, onSelect = { t -> vm.user { it.copy(theme = t) } })
            Text(stringResource(R.string.language), style = MaterialTheme.typography.labelLarge)
            ChoiceChips(listOf("", "en", "de"), user.language, label = {
                when (it) { "" -> stringResource(R.string.language_system); "en" -> "English"; else -> "Deutsch" }
            }, onSelect = { lang ->
                vm.user { it.copy(language = lang) }
                AppCompatDelegate.setApplicationLocales(if (lang.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(lang))
            })
        }

        SectionCard(stringResource(R.string.settings_permissions)) {
            val r = refresh
            PermissionRow(context, stringResource(R.string.perm_microphone), SystemChecks.mic(context)) { SystemChecks.appSettings(context) }
            PermissionRow(context, stringResource(R.string.perm_notifications), SystemChecks.notifications(context)) { SystemChecks.notificationSettings(context) }
            PermissionRow(context, stringResource(R.string.perm_exact_alarm), SystemChecks.exactAlarms(context)) { SystemChecks.exactAlarmSettings(context) }
            PermissionRow(context, stringResource(R.string.perm_full_screen), SystemChecks.fullScreen(context)) { SystemChecks.fullScreenSettings(context) }
            PermissionRow(context, stringResource(R.string.perm_battery), SystemChecks.batteryOptimizationIgnored(context)) { SystemChecks.batterySettings() }
            TextButton(onClick = { refresh = r + 1 }) { Text(stringResource(R.string.refresh)) }
        }

        SectionCard(stringResource(R.string.settings_data)) {
            SettingRow(stringResource(R.string.export_csv), icon = Icons.Filled.FileDownload) { exportCsv.launch("sleep-$today.csv") }
            SettingRow(stringResource(R.string.export_json), icon = Icons.Filled.FileDownload) { exportJson.launch("sleep-$today.json") }
            SettingRow(stringResource(R.string.create_backup), icon = Icons.Filled.Backup) { backup.launch("sleep-backup-$today.zip") }
            SettingRow(stringResource(R.string.restore_backup), icon = Icons.Filled.Restore) { restore.launch(arrayOf("application/zip", "application/octet-stream")) }
            SettingRow(
                stringResource(R.string.import_sleep_cycle),
                value = if (importBusy) stringResource(R.string.import_reading) else null,
                icon = Icons.Filled.FileUpload,
            ) {
                importLauncher.launch(
                    arrayOf(
                        "text/csv", "text/comma-separated-values", "text/plain", "text/*",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel", "application/octet-stream",
                    ),
                )
            }
            Text(stringResource(R.string.import_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.backup_hint), style = MaterialTheme.typography.bodySmall)
            SettingRow(stringResource(R.string.delete_all_data), icon = Icons.Filled.DeleteForever) { confirm = "all" }
        }

        SectionCard(stringResource(R.string.settings_privacy)) {
            Text(stringResource(R.string.privacy_text), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.medical_disclaimer), style = MaterialTheme.typography.bodySmall)
        }
    }

    importPreview?.let { p ->
        val msgImported = stringResource(R.string.import_done, p.newNights.size)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { importPreview = null },
            title = { Text(stringResource(R.string.import_sleep_cycle)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.import_rows, p.rowsInFile))
                    Text(stringResource(R.string.import_new, p.newNights.size), style = MaterialTheme.typography.titleMedium)
                    if (p.firstDate != null && p.lastDate != null) {
                        Text(stringResource(R.string.import_range, de.sleeptracker.app.ui.Fmt.date(p.firstDate), de.sleeptracker.app.ui.Fmt.date(p.lastDate)))
                    }
                    if (p.duplicates > 0) Text(stringResource(R.string.import_duplicates, p.duplicates))
                    if (p.invalidRows > 0) Text(stringResource(R.string.import_invalid, p.invalidRows))
                    Text(stringResource(R.string.import_limitations), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = p.newNights.isNotEmpty(),
                    onClick = {
                        importPreview = null
                        scope.launch {
                            val ok = runCatching { c.sleepCycleImport.commit(p) }.isSuccess
                            snackbar.showSnackbar(if (ok) msgImported else msgFailed)
                        }
                    },
                ) { Text(stringResource(R.string.import_action)) }
            },
            dismissButton = { TextButton(onClick = { importPreview = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    when (confirm) {
        "recordings" -> ConfirmDialog(
            stringResource(R.string.delete_all_recordings), stringResource(R.string.confirm_irreversible), stringResource(R.string.delete),
            onConfirm = { vm.deleteAllRecordings() }, onDismiss = { confirm = null },
        )
        "all" -> ConfirmDialog(
            stringResource(R.string.delete_all_data), stringResource(R.string.delete_all_data_text), stringResource(R.string.delete),
            onConfirm = { vm.deleteAllData { scope.launch { snackbar.showSnackbar(msgDone) } } }, onDismiss = { confirm = null },
        )
        "restore" -> ConfirmDialog(
            stringResource(R.string.restore_backup), stringResource(R.string.restore_text), stringResource(R.string.restore),
            onConfirm = { restoreUri?.let { u -> runIo { c.exporter.restoreBackup(u) } } }, onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun PermissionRow(context: Context, title: String, ok: Boolean, intent: () -> android.content.Intent) {
    SettingRow(
        title,
        value = stringResource(if (ok) R.string.granted else R.string.not_granted),
        onClick = { SystemChecks.open(context, intent()) },
    )
}

fun formatBytes(b: Long): String = when {
    b >= 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024.0)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}
