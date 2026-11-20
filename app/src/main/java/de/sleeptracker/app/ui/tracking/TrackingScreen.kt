package de.sleeptracker.app.ui.tracking

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sleeptracker.app.R
import de.sleeptracker.app.container
import de.sleeptracker.app.tracking.SleepTrackingService
import de.sleeptracker.app.tracking.StopState
import de.sleeptracker.app.ui.Fmt
import de.sleeptracker.app.ui.components.ConfirmDialog
import de.sleeptracker.app.ui.sleep.modeLabel
import de.sleeptracker.core.TrackingMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Deliberately dim, low-contrast night screen; updates once per minute only. */
@Composable
fun TrackingScreen(onFinished: (Long?) -> Unit) {
    val context = LocalContext.current
    val status by SleepTrackingService.liveStatus.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirm by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var waitedForStart by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Very dim, warm tones: readable at night without lighting up the room.
    val dim = Color(0xFF8C7A68)

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L)
        }
    }
    LaunchedEffect(Unit) { delay(8_000); waitedForStart = true }
    val stopState by context.container.tracking.stopState.collectAsStateWithLifecycle()
    LaunchedEffect(status, waitedForStart, stopState) {
        if (status == null && waitedForStart && !stopping && stopState is StopState.Idle) onFinished(null)
    }
    LaunchedEffect(stopState) {
        val st = stopState
        if (st is StopState.Done) {
            context.container.tracking.acknowledgeStop()
            onFinished(st.sessionId)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) {
        val st = stopState
        if (st is StopState.Stopping || stopping && st !is StopState.Failed) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = dim)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.stop_saving), color = dim)
            }
            return@Box
        }
        if (st is StopState.Failed) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.stop_failed), color = Color(0xFFB07070))
                Spacer(Modifier.height(16.dp))
                Button(onClick = { context.container.tracking.acknowledgeStop(); onFinished(null) }) { Text(stringResource(R.string.back)) }
            }
            return@Box
        }
        val s = status
        if (s == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = dim)
            return@Box
        }
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(48.dp))
            Text(Fmt.time(now), color = dim, fontSize = 72.sp, fontWeight = FontWeight.Thin)
            Text(stringResource(R.string.tracking_active), color = dim)
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.tracking_since, Fmt.time(s.startMillis)), color = dim)
            Text(modeLabel(s.mode), color = dim)
            if (s.mode != TrackingMode.TRACKING_ONLY && s.alarmDeadlineMillis != null) {
                val d = s.alarmDeadlineMillis
                if (s.mode == TrackingMode.SMART_ALARM && s.alarmWindowMinutes > 0) {
                    Text(
                        stringResource(R.string.wake_window_range, Fmt.time(d - s.alarmWindowMinutes * 60_000L), Fmt.time(d)),
                        color = dim,
                    )
                } else {
                    Text(stringResource(R.string.alarm_at_time, Fmt.time(d)), color = dim)
                }
                val remaining = ((d - now) / 60_000L).coerceAtLeast(0)
                Text(stringResource(R.string.fmt_remaining, remaining / 60, remaining % 60), color = dim)
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(
                    if (s.micActive) Icons.Filled.Mic else Icons.Filled.MicOff,
                    stringResource(if (s.micActive) R.string.mic_on else R.string.mic_off), dim,
                )
                StatusIcon(
                    Icons.Filled.Vibration,
                    stringResource(if (s.motionActive) R.string.motion_on else R.string.motion_off), dim,
                )
                StatusIcon(
                    if (s.charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
                    if (s.batteryPercent >= 0) "${s.batteryPercent} %" else "–", dim,
                )
            }
            val calibration = context.container.let { c ->
                androidx.compose.runtime.produceState(90) { value = c.settings.user().noiseCalibrationDb }.value
            }
            val env = buildList {
                if (s.lightActive) {
                    add(
                        if (s.currentLux >= 0f) stringResource(R.string.live_light_readings, de.sleeptracker.app.ui.journal.luxText(s.currentLux), s.lightReadings)
                        else stringResource(R.string.live_light_waiting),
                    )
                }
                s.currentNoiseDb?.let { add(stringResource(R.string.live_noise, de.sleeptracker.app.ui.journal.dbText(it, calibration))) }
                s.currentBreathing?.let { add(stringResource(R.string.live_breathing, it)) }
            }
            if (env.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(env.joinToString("  ·  "), color = dim)
            }
            if (s.micActive) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.mic_active_notice), color = dim, fontSize = 12.sp)
            }
            s.problem?.let { Text(it, color = Color(0xFFB07070)) }
            if (!s.charging && s.batteryPercent in 0..19) Text(stringResource(R.string.warn_battery_low, s.batteryPercent), color = Color(0xFFB09070))
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { confirm = true },
                enabled = !stopping,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1512), contentColor = Color(0xFFB0703A)),
                shape = androidx.compose.material3.MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(stringResource(R.string.tracking_stop)) }
        }
    }

    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.stop_confirm_title),
            text = stringResource(R.string.stop_confirm_text),
            confirm = stringResource(R.string.tracking_stop),
            onConfirm = {
                stopping = true
                context.container.tracking.requestStop()
            },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun StatusIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = label, tint = tint)
        Spacer(Modifier.width(4.dp))
        Text(label, color = tint, fontSize = 12.sp)
    }
}
