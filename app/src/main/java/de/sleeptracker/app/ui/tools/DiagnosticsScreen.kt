@file:OptIn(ExperimentalMaterial3Api::class)

package de.sleeptracker.app.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sleeptracker.app.R
import de.sleeptracker.app.container
import de.sleeptracker.app.data.db.SleepSession
import de.sleeptracker.app.data.db.UserSettings
import de.sleeptracker.app.tracking.SleepTrackingService
import de.sleeptracker.app.ui.Fmt
import de.sleeptracker.app.ui.SystemChecks
import de.sleeptracker.app.ui.components.MetricRow
import de.sleeptracker.app.ui.components.SectionCard
import de.sleeptracker.app.ui.journal.luxText
import de.sleeptracker.app.ui.settings.formatBytes
import de.sleeptracker.app.ui.theme.DataColors

/** Everything needed to troubleshoot a night, in one place. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val user by c.settings.userSettings.collectAsStateWithLifecycle(initialValue = UserSettings())
    val live by SleepTrackingService.liveStatus.collectAsStateWithLifecycle()
    val storage by c.recordings.totalBytes.collectAsStateWithLifecycle(initialValue = 0L)
    val mic = remember { MicProbe(context) }
    val sensors = remember { SensorProbe(context) }
    val micReading by mic.reading.collectAsStateWithLifecycle()
    val sensorReading by sensors.reading.collectAsStateWithLifecycle()
    var last by remember { mutableStateOf<Triple<SleepSession, Int, Int>?>(null) }

    // The tracking service owns the microphone during a night; only probe when it is idle.
    DisposableEffect(live == null) {
        if (live == null) mic.start()
        sensors.start()
        onDispose { mic.stop(); sensors.stop() }
    }
    LaunchedEffect(Unit) {
        val s = c.sleep.completedBefore(System.currentTimeMillis() + 1, 1).firstOrNull { it.hasPhaseData }
        if (s != null) {
            val epochs = c.database.signalDao().epochs(s.id).size
            val events = c.database.signalDao().soundEvents(s.id).size
            last = Triple(s, epochs, events)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.diagnostics_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionCard(stringResource(R.string.diag_microphone)) {
                val threshold = 13f - 8f * user.soundSensitivity
                val r = micReading
                when {
                    live != null -> Text(stringResource(R.string.diag_mic_busy), style = MaterialTheme.typography.bodySmall)
                    !SystemChecks.mic(context) -> Text(stringResource(R.string.diag_mic_no_permission), style = MaterialTheme.typography.bodySmall)
                    !mic.available -> Text(stringResource(R.string.diag_mic_unavailable), style = MaterialTheme.typography.bodySmall)
                    r == null -> Text(stringResource(R.string.diag_mic_starting), style = MaterialTheme.typography.bodySmall)
                    else -> {
                        MicMeter(r.levelDb, r.floorDb, r.floorDb + threshold)
                        MetricRow(stringResource(R.string.diag_level), "%.0f dBFS · ≈ %.0f dB".format(r.levelDb, r.levelDb + user.noiseCalibrationDb))
                        MetricRow(stringResource(R.string.diag_background), "%.0f dBFS".format(r.floorDb))
                        MetricRow(stringResource(R.string.diag_threshold), "+%.1f dB → %.0f dBFS".format(threshold, r.floorDb + threshold))
                        MetricRow(stringResource(R.string.diag_breathing), r.breathsPerMinute?.let { "%.0f /min".format(it) } ?: "–")
                        Text(stringResource(R.string.diag_mic_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            SectionCard(stringResource(R.string.diag_sensors)) {
                val s = sensorReading
                MetricRow(stringResource(R.string.diag_accelerometer), if (!s.hasAccelerometer) stringResource(R.string.diag_missing) else s.accelSd?.let { "± %.3f m/s²".format(it) } ?: "…")
                MetricRow(stringResource(R.string.diag_orientation), s.gravityZ?.let { stringResource(if (it < -6.5f) R.string.diag_face_down else if (it > 6.5f) R.string.diag_face_up else R.string.diag_tilted) } ?: "–")
                MetricRow(stringResource(R.string.diag_light), if (!s.hasLight) stringResource(R.string.diag_missing) else s.lux?.let(::luxText) ?: stringResource(R.string.diag_waiting))
                MetricRow(stringResource(R.string.diag_proximity), if (!s.hasProximity) stringResource(R.string.diag_missing) else s.proximityNear?.let { stringResource(if (it) R.string.diag_covered else R.string.diag_free) } ?: "–")
            }

            SectionCard(stringResource(R.string.settings_permissions)) {
                fun state(ok: Boolean) = if (ok) DataColors.good else DataColors.warn
                MetricRow(stringResource(R.string.perm_microphone), stringResource(if (SystemChecks.mic(context)) R.string.granted else R.string.not_granted), state(SystemChecks.mic(context)))
                MetricRow(stringResource(R.string.perm_notifications), stringResource(if (SystemChecks.notifications(context)) R.string.granted else R.string.not_granted), state(SystemChecks.notifications(context)))
                MetricRow(stringResource(R.string.perm_exact_alarm), stringResource(if (SystemChecks.exactAlarms(context)) R.string.granted else R.string.not_granted), state(SystemChecks.exactAlarms(context)))
                MetricRow(stringResource(R.string.perm_full_screen), stringResource(if (SystemChecks.fullScreen(context)) R.string.granted else R.string.not_granted), state(SystemChecks.fullScreen(context)))
                MetricRow(stringResource(R.string.perm_battery), stringResource(if (SystemChecks.batteryOptimizationIgnored(context)) R.string.granted else R.string.not_granted), state(SystemChecks.batteryOptimizationIgnored(context)))
            }

            SectionCard(stringResource(R.string.diag_tracking)) {
                val l = live
                MetricRow(stringResource(R.string.diag_service), stringResource(if (l != null) R.string.diag_running else R.string.diag_idle))
                if (l != null) {
                    MetricRow(stringResource(R.string.diag_epochs), "${l.epochs}")
                    MetricRow(stringResource(R.string.diag_light_readings), "${l.lightReadings}")
                    MetricRow(stringResource(R.string.diag_episodes), "${l.soundEvents}")
                }
                val battery = SystemChecks.battery(context)
                MetricRow(stringResource(R.string.diag_battery), "${battery.percent} % · ${stringResource(if (battery.charging) R.string.charging else R.string.not_charging)}")
                MetricRow(stringResource(R.string.storage_used), formatBytes(storage))
            }

            last?.let { (s, epochs, events) ->
                SectionCard(stringResource(R.string.diag_last_night, Fmt.date(s.sessionDate))) {
                    MetricRow(stringResource(R.string.diag_epochs), "$epochs (≈ ${epochs / 2} min)")
                    MetricRow(stringResource(R.string.diag_light_readings), if (s.lightReadings < 0) stringResource(R.string.off) else "${s.lightReadings}")
                    MetricRow(stringResource(R.string.diag_episodes), "$events")
                    MetricRow(stringResource(R.string.diag_breathing_minutes), "${s.breathingMinutes} min")
                    MetricRow(stringResource(R.string.sensors), s.sensorSource.name.lowercase())
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Horizontal level meter: background (grey), current level (orange), detection threshold (line). */
@Composable
private fun MicMeter(level: Float, floor: Float, threshold: Float) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val bg = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(18.dp).padding(vertical = 2.dp)) {
        val lo = -100f
        val hi = -20f
        fun x(db: Float) = ((db - lo) / (hi - lo)).coerceIn(0f, 1f) * size.width
        drawRect(track, Offset.Zero, size)
        drawRect(bg.copy(alpha = 0.5f), Offset.Zero, Size(x(floor), size.height))
        drawRect(accent, Offset(x(floor), size.height * 0.2f), Size((x(level) - x(floor)).coerceAtLeast(0f), size.height * 0.6f))
        drawLine(ink, Offset(x(threshold), 0f), Offset(x(threshold), size.height), 3f)
    }
}
