package app.pillion

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pillion.data.ThemeMode
import app.pillion.device.RiderLocation
import app.pillion.order.ScanState
import app.pillion.safety.SafetyState
import app.pillion.ui.EarningsRoute
import app.pillion.ui.FirstRunRoute
import app.pillion.ui.IntroVideo
import app.pillion.ui.OrderCameraRoute
import app.pillion.ui.RideRoute
import app.pillion.ui.RideStatus
import app.pillion.ui.RideViewModel
import app.pillion.ui.SafetyRoute
import app.pillion.ui.SettingsRoute
import app.pillion.ui.theme.DEFAULT_LAT
import app.pillion.ui.theme.DEFAULT_LNG
import app.pillion.ui.theme.Pillion
import app.pillion.ui.theme.PillionTheme
import app.pillion.ui.theme.animationsOff
import app.pillion.ui.theme.isNight
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private enum class Screen(@StringRes val label: Int = 0, @DrawableRes val icon: Int = 0, @DrawableRes val selectedIcon: Int = 0) {
        Ride(R.string.nav_ride, R.drawable.ic_two_wheeler, R.drawable.ic_two_wheeler_filled),
        Earnings(R.string.nav_earnings, R.drawable.ic_payments, R.drawable.ic_payments_filled),
        Safety(R.string.nav_safety, R.drawable.ic_shield, R.drawable.ic_shield_filled),
        Settings,
        Camera,
    }

    private var screen by mutableStateOf(Screen.Ride)
    /** The 2 s intro video, over the app, on a launcher cold start. */
    private var showIntro by mutableStateOf(false)
    /** Where Settings goes back to. */
    private var lastTab = Screen.Ride

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = savedInstanceState?.getString(KEY_SCREEN)?.let(Screen::valueOf) ?: Screen.Ride
        // Not on re-creation, a share, "Remove animations", or while a safety alert is on.
        showIntro = savedInstanceState == null &&
            intent?.action == Intent.ACTION_MAIN &&
            !animationsOff(this) &&
            pillion.safety.state.value == SafetyState.Idle
        // The launch window is black like the intro's first frame; without the intro, the app's own colour.
        if (!showIntro) useAppWindowBackground()
        val prefs = pillion.uiPrefs
        setContent {
            val themeMode by prefs.themeMode.collectAsStateWithLifecycle()
            // The ride's ViewModel belongs to the activity, so it survives switching screens.
            val ride: RideViewModel = viewModel()
            val rideState by ride.uiState.collectAsStateWithLifecycle()
            val inRide = rideState.rideActive || rideState.status == RideStatus.Ending
            val night by rememberNight(enabled = inRide)
            // A ride at night is always dark: light text on dark avoids glare and after-images.
            val dark = when (themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            } || (inRide && night)
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_NAV_SCRIM, DARK_NAV_SCRIM) { dark },
                )
            }
            val firstRunDone by prefs.firstRunDone.collectAsStateWithLifecycle()
            val scanState by pillion.scanner.state.collectAsStateWithLifecycle()
            PillionTheme(dark = dark) {
                // The welcome screen, once; never in the way of a ride or a shared order screenshot.
                if (!firstRunDone && !inRide && scanState == ScanState.Idle && screen != Screen.Camera) {
                    Box(Modifier.fillMaxSize().background(Pillion.colors.background)) {
                        FirstRunRoute(onDone = { prefs.setFirstRunDone(true) })
                    }
                    return@PillionTheme
                }
                // No tabs during a ride or while a scanned order is checked: one task on screen.
                val tabs = screen in TABS && !inRide && scanState == ScanState.Idle
                BackHandler(enabled = screen == Screen.Earnings) { screen = Screen.Ride }
                Scaffold(
                    containerColor = Pillion.colors.background,
                    // Screens draw under the status bar (the ride screen's glow) and pad themselves.
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = { if (tabs) Tabs(screen) { screen = it } },
                ) { padding ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .consumeWindowInsets(padding),
                    ) {
                        when (screen) {
                            Screen.Ride -> RideRoute(
                                onOpenSafety = { screen = Screen.Safety },
                                onOpenCamera = { screen = Screen.Camera },
                                onOpenSettings = ::openSettings,
                                viewModel = ride,
                            )
                            Screen.Earnings -> EarningsRoute(onOpenSettings = ::openSettings)
                            Screen.Safety -> SafetyRoute(onBack = { screen = Screen.Ride }, onOpenSettings = ::openSettings)
                            Screen.Settings -> SettingsRoute(onBack = { screen = lastTab })
                            Screen.Camera -> OrderCameraRoute(
                                onCaptured = { source, load ->
                                    pillion.scanner.scan(source, load)
                                    screen = Screen.Ride
                                },
                                onClose = { screen = Screen.Ride },
                            )
                        }
                    }
                }
            }
            if (showIntro) {
                IntroVideo(onDone = {
                    showIntro = false
                    useAppWindowBackground()
                })
            }
        }
    }

    private fun useAppWindowBackground() {
        window.setBackgroundDrawable(ColorDrawable(getColor(R.color.background)))
    }

    private fun openSettings() {
        if (screen in TABS) lastTab = screen
        screen = Screen.Settings
    }

    @Composable
    private fun Tabs(current: Screen, onSelect: (Screen) -> Unit) {
        val colors = Pillion.colors
        Column {
            HorizontalDivider(color = colors.hairline)
            NavigationBar(containerColor = colors.background, tonalElevation = 0.dp) {
                TABS.forEach { tab ->
                    val selected = tab == current
                    NavigationBarItem(
                        selected = selected,
                        onClick = { onSelect(tab) },
                        icon = { Icon(painterResource(if (selected) tab.selectedIcon else tab.icon), contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.onAccent,
                            selectedTextColor = colors.ink,
                            indicatorColor = colors.accent,
                            unselectedIconColor = colors.inkSecondary,
                            unselectedTextColor = colors.inkSecondary,
                        ),
                    )
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
        private val TABS = listOf(Screen.Ride, Screen.Earnings, Screen.Safety)
        // androidx.activity's own defaults, for 3-button navigation on Android 8–9.
        private val LIGHT_NAV_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_NAV_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}

/** Whether it's between sunset and sunrise where the rider is (checked each minute while [enabled]). */
@Composable
private fun rememberNight(enabled: Boolean): State<Boolean> {
    val context = LocalContext.current
    return produceState(false, enabled) {
        val location = RiderLocation(context)
        while (enabled) {
            val fix = location.newestKnown()
            value = isNight(System.currentTimeMillis(), fix?.latitude ?: DEFAULT_LAT, fix?.longitude ?: DEFAULT_LNG)
            delay(60_000)
        }
    }
}
