package app.pillion.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pillion.pillion
import app.pillion.safety.SafetyState
import app.pillion.ui.theme.PillionTheme

/**
 * The crash / SOS alert, shown over the lock screen with the screen turned on. Its own task, and
 * only safety UI in it: nothing else of the app is reachable from the lock screen. Closes itself
 * when the alert is over.
 */
class SafetyAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val safety = pillion.safety
        setContent {
            PillionTheme {
                val state by safety.state.collectAsStateWithLifecycle()
                val voice by safety.voiceConnected.collectAsStateWithLifecycle()
                LaunchedEffect(state) { if (state == SafetyState.Idle) finish() }
                // During the countdown the rider has to answer; Back must not hide the alert.
                BackHandler(enabled = state is SafetyState.Countdown) {}
                SafetyAlertScreen(
                    state = state,
                    voiceConnected = voice,
                    onOk = safety::riderIsOk,
                    onSendNow = { safety.sendNow() },
                    onDial = ::dial,
                )
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Opens the dialer (no permission needed); on a locked phone Android asks to unlock first. */
    private fun dial(number: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        runCatching { startActivity(intent) }.onFailure { Log.w(TAG, "No dialer", it) }
    }

    companion object {
        private const val TAG = "SafetyAlert"

        fun intent(context: Context): Intent =
            Intent(context, SafetyAlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /**
         * Opens the alert directly. Works while Pillion is on screen; from the background Android
         * blocks it silently and the alert notification's full-screen intent shows it instead.
         */
        fun launch(context: Context) {
            runCatching { context.startActivity(intent(context)) }.onFailure { Log.w(TAG, "Alert screen not started", it) }
        }
    }
}
