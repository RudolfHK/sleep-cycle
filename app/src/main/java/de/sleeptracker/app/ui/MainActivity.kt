package de.sleeptracker.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sleeptracker.app.container
import de.sleeptracker.app.data.db.UserSettings
import de.sleeptracker.app.ui.theme.SleepTheme

/** Navigation requests coming from notifications or the alarm screen. */
sealed interface LaunchTarget {
    data class Report(val sessionId: Long) : LaunchTarget
    data object Tracking : LaunchTarget
}

class MainActivity : AppCompatActivity() {
    private val launchTarget = mutableStateOf<LaunchTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        val settingsFlow = container.settings.userSettings
        setContent {
            val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = UserSettings())
            SleepTheme(settings.theme) {
                AppRoot(launchTarget = launchTarget.value, onLaunchHandled = { launchTarget.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val report = intent.getLongExtra(EXTRA_REPORT_SESSION, -1L)
        launchTarget.value = when {
            report > 0 -> LaunchTarget.Report(report)
            intent.getBooleanExtra(EXTRA_OPEN_TRACKING, false) -> LaunchTarget.Tracking
            else -> launchTarget.value
        }
    }

    companion object {
        const val EXTRA_REPORT_SESSION = "report_session"
        const val EXTRA_OPEN_TRACKING = "open_tracking"
    }
}
