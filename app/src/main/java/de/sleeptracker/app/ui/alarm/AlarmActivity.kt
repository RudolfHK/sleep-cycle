package de.sleeptracker.app.ui.alarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sleeptracker.app.R
import de.sleeptracker.app.alarm.AlarmRingService
import de.sleeptracker.app.alarm.AlarmRingState
import de.sleeptracker.app.alarm.RingUiState
import de.sleeptracker.app.data.db.VolumeButtonAction
import de.sleeptracker.app.ui.Fmt
import de.sleeptracker.app.ui.MainActivity
import de.sleeptracker.app.ui.theme.SleepTheme
import de.sleeptracker.core.TrackingMode
import kotlinx.coroutines.delay

/** Full-screen alarm shown over the lock screen. A visible Stop button is always present. */
class AlarmActivity : ComponentActivity() {
    private var dismissed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            SleepTheme {
                val ring by AlarmRingState.state.collectAsStateWithLifecycle()
                LaunchedEffect(ring) {
                    if (ring == null) {
                        delay(1_500) // allow the service to publish its state
                        if (AlarmRingState.state.value == null) finishAndMaybeOpenReport()
                    }
                }
                AlarmContent(ring, onSnooze = ::snooze, onStop = ::stop)
            }
        }
    }

    private fun snooze() = AlarmRingService.send(this, AlarmRingService.ACTION_SNOOZE)

    private fun stop() {
        dismissed = true
        AlarmRingService.send(this, AlarmRingService.ACTION_DISMISS)
    }

    private fun finishAndMaybeOpenReport() {
        if (dismissed) {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            AlarmRingState.lastReportSessionId?.let { intent.putExtra(MainActivity.EXTRA_REPORT_SESSION, it) }
            AlarmRingState.lastReportSessionId = null
            startActivity(intent)
        }
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            when (AlarmRingState.state.value?.volumeButtonAction) {
                VolumeButtonAction.SNOOZE -> { snooze(); return true }
                VolumeButtonAction.STOP -> { stop(); return true }
                else -> Unit
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlarmContent(ring: RingUiState?, onSnooze: () -> Unit, onStop: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(Fmt.time(now), fontSize = 80.sp, fontWeight = FontWeight.Light)
            Text(stringResource(R.string.good_morning), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            if (ring != null) {
                if (ring.mode == TrackingMode.SMART_ALARM && ring.windowMinutes > 0) {
                    Text(
                        stringResource(
                            R.string.wake_window_range,
                            Fmt.time(ring.deadlineMillis - ring.windowMinutes * 60_000L), Fmt.time(ring.deadlineMillis),
                        ),
                    )
                    val early = ((ring.deadlineMillis - ring.alarmMillis) / 60_000L).toInt()
                    if (early > 0) Text(stringResource(R.string.woke_early, early), textAlign = TextAlign.Center)
                } else {
                    Text(stringResource(R.string.alarm_at_time, Fmt.time(ring.deadlineMillis)))
                }
                Text(ring.soundTitle, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(48.dp))
            val snoozeMinutes = ring?.nextSnoozeMinutes
            if (snoozeMinutes != null) {
                Button(onClick = onSnooze, modifier = Modifier.fillMaxWidth().height(72.dp), shape = MaterialTheme.shapes.small) {
                    Text(stringResource(R.string.snooze_minutes, snoozeMinutes), style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(16.dp))
            }
            val confirm = ring?.confirmationRequired == true
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                    .combinedClickable(
                        role = Role.Button,
                        onClick = { if (!confirm) onStop() },
                        onLongClick = onStop,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (confirm) R.string.alarm_stop_hold else R.string.alarm_stop),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}
