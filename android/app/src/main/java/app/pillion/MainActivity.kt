package app.pillion

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.pillion.ui.OrderCameraRoute
import app.pillion.ui.RideRoute
import app.pillion.ui.SafetySettingsRoute
import app.pillion.ui.theme.PillionTheme

class MainActivity : ComponentActivity() {

    private enum class Screen { Ride, Safety, Camera }

    private var screen by mutableStateOf(Screen.Ride)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = savedInstanceState?.getString(KEY_SCREEN)?.let(Screen::valueOf) ?: Screen.Ride
        // The app is always dark, so system bar icons are always light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            PillionTheme {
                // Three screens; the ride's ViewModel belongs to the activity, so it survives switching.
                when (screen) {
                    Screen.Safety -> SafetySettingsRoute(onBack = { screen = Screen.Ride })
                    Screen.Camera -> OrderCameraRoute(
                        onCaptured = { source, load ->
                            pillion.scanner.scan(source, load)
                            screen = Screen.Ride
                        },
                        onClose = { screen = Screen.Ride },
                    )
                    Screen.Ride -> RideRoute(onOpenSafety = { screen = Screen.Safety }, onOpenCamera = { screen = Screen.Camera })
                }
            }
        }
    }

    // A screenshot shared to Pillion: the ride screen shows it for checking.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_ORDER, false)) screen = Screen.Ride
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen.name)
    }

    companion object {
        const val EXTRA_SHOW_ORDER = "app.pillion.SHOW_ORDER"
        private const val KEY_SCREEN = "screen"
    }
}
