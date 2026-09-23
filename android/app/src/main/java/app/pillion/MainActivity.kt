package app.pillion

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.pillion.ui.RideRoute
import app.pillion.ui.SafetySettingsRoute
import app.pillion.ui.theme.PillionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so system bar icons are always light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            PillionTheme {
                // Two screens; the ride's ViewModel belongs to the activity, so it survives switching.
                var showSafety by rememberSaveable { mutableStateOf(false) }
                if (showSafety) {
                    SafetySettingsRoute(onBack = { showSafety = false })
                } else {
                    RideRoute(onOpenSafety = { showSafety = true })
                }
            }
        }
    }
}
