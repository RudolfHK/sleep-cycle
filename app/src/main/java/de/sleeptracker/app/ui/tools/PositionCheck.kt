package de.sleeptracker.app.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.sleeptracker.analysis.PositionAssessment
import de.sleeptracker.analysis.PositionCheck
import de.sleeptracker.analysis.PositionInput
import de.sleeptracker.app.R
import de.sleeptracker.app.ui.theme.DataColors
import kotlinx.coroutines.delay

private const val CHECK_MILLIS = 12_000L

private fun texts(item: PositionCheck.Item): Pair<Int, Int> = when (item) {
    PositionCheck.Item.MIC_BLOCKED -> R.string.pc_mic_blocked to R.string.pc_mic_blocked_hint
    PositionCheck.Item.MIC_OK -> R.string.pc_mic_ok to R.string.pc_mic_ok_hint
    PositionCheck.Item.ROOM_LOUD -> R.string.pc_room_loud to R.string.pc_room_loud_hint
    PositionCheck.Item.BREATHING_FOUND -> R.string.pc_breathing_found to R.string.pc_breathing_found_hint
    PositionCheck.Item.BREATHING_NOT_FOUND -> R.string.pc_breathing_not_found to R.string.pc_breathing_not_found_hint
    PositionCheck.Item.COVERED -> R.string.pc_covered to R.string.pc_covered_hint
    PositionCheck.Item.FACE_DOWN -> R.string.pc_face_down to R.string.pc_face_down_hint
    PositionCheck.Item.HELD -> R.string.pc_held to R.string.pc_held_hint
    PositionCheck.Item.HARD_SURFACE -> R.string.pc_hard_surface to R.string.pc_hard_surface_hint
    PositionCheck.Item.ON_MATTRESS -> R.string.pc_on_mattress to R.string.pc_on_mattress_hint
    PositionCheck.Item.ROOM_BRIGHT -> R.string.pc_room_bright to R.string.pc_room_bright_hint
    PositionCheck.Item.NO_MOTION_SENSOR -> R.string.pc_no_motion to R.string.pc_no_motion_hint
}

/**
 * Samples microphone and sensors for a few seconds before the night starts and explains whether
 * the phone is placed well. [onStart] continues with tracking, [onDismiss] cancels.
 */
@Composable
fun PositionCheckDialog(soundEnabled: Boolean, motionEnabled: Boolean, onStart: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var run by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<List<PositionCheck>?>(null) }
    val mic = remember { MicProbe(context) }
    val sensors = remember { SensorProbe(context) }
    DisposableEffect(Unit) { onDispose { mic.stop(); sensors.stop() } }

    LaunchedEffect(run) {
        result = null
        progress = 0f
        if (soundEnabled) mic.start()
        sensors.start()
        val floors = ArrayList<Float>()
        var bpm: Float? = null
        val steps = 60
        for (i in 1..steps) {
            delay(CHECK_MILLIS / steps)
            progress = i / steps.toFloat()
            mic.reading.value?.let { r -> if (i > steps / 4) floors += r.floorDb; r.breathsPerMinute?.let { bpm = it } }
        }
        val s = sensors.reading.value
        mic.stop(); sensors.stop()
        result = PositionAssessment.assess(
            PositionInput(
                soundEnabled = soundEnabled,
                motionEnabled = motionEnabled,
                micFloorDb = if (!mic.available || floors.isEmpty()) null else floors.sorted()[floors.size / 2],
                breathsPerMinute = bpm,
                proximityNear = s.proximityNear,
                gravityZ = s.gravityZ,
                accelSd = s.accelSd,
                lux = s.lux,
            ),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pc_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val r = result
                if (r == null) {
                    Text(stringResource(R.string.pc_running), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    val worst = PositionAssessment.worst(r)
                    Text(
                        stringResource(
                            when (worst) {
                                PositionCheck.Status.OK -> R.string.pc_summary_ok
                                PositionCheck.Status.INFO -> R.string.pc_summary_info
                                PositionCheck.Status.WARNING -> R.string.pc_summary_warning
                            },
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    r.forEach { check ->
                        val (title, hint) = texts(check.item)
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                when (check.status) {
                                    PositionCheck.Status.OK -> Icons.Filled.CheckCircle
                                    PositionCheck.Status.INFO -> Icons.Filled.Info
                                    PositionCheck.Status.WARNING -> Icons.Filled.Warning
                                },
                                contentDescription = null,
                                tint = when (check.status) {
                                    PositionCheck.Status.OK -> DataColors.good
                                    PositionCheck.Status.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                                    PositionCheck.Status.WARNING -> DataColors.warn
                                },
                                modifier = Modifier.size(20.dp).padding(top = 2.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(stringResource(title), style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onStart, enabled = result != null, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.start_tracking)) }
        },
        dismissButton = {
            Row {
                if (result != null) TextButton(onClick = { run++ }) { Text(stringResource(R.string.pc_again)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
