package app.pillion

import android.app.Application
import android.content.Context
import app.pillion.data.ActiveOrderSource
import app.pillion.data.EarningsDb
import app.pillion.data.UiPrefs
import app.pillion.order.OrderScanner
import app.pillion.safety.EmergencyContacts
import app.pillion.safety.SafetyMonitor
import app.pillion.ui.theme.preloadFonts
import kotlin.concurrent.thread

/**
 * Process-wide objects. Safety lives here rather than in a ViewModel: a crash alert must run with
 * the screen locked and the ride screen gone, driven by the ride's foreground service.
 */
class PillionApp : Application() {
    val db by lazy { EarningsDb(this) }
    val contacts by lazy { EmergencyContacts(this) }
    val safety by lazy { SafetyMonitor(this, db, contacts) }
    /** The active order, and the scanner that reads one from a shared screenshot, picked image or photo. */
    val orders by lazy { ActiveOrderSource(this) }
    val scanner by lazy { OrderScanner() }
    val uiPrefs by lazy { UiPrefs(this) }

    override fun onCreate() {
        super.onCreate()
        thread(name = "font-preload") { preloadFonts(this) }
    }
}

val Context.pillion: PillionApp get() = applicationContext as PillionApp
