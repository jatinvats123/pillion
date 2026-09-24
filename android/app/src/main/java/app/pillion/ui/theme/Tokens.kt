package app.pillion.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Generous radii; buttons, chips and the status pill are fully round (CircleShape / 50 %). */
val PillionShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** 8 dp grid (4 only inside compact chips). */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    /** Side margin of every screen. */
    val gutter = 24.dp
}

/** Touch targets: 48 dp everywhere, much bigger on the ride screen (gloves, a moving bike). */
object Targets {
    val min = 48.dp
    /** Start / End Ride and SOS. */
    val ride = 88.dp
    /** Other ride-screen controls (mute). */
    val rideSmall = 76.dp
    /** Space between ride-screen targets. */
    val rideGap = 24.dp
}

/**
 * Motion carries state, never decoration: short springs (~200 ms) for state changes, a slow
 * breath for the idle orb, a fast pulse only for a safety alert. With the system's "Remove
 * animations" on, everything snaps and the orb stands still.
 */
object Motion {
    const val COLOR_MS = 250
    /** Orb breathing and drift repeat within this period, so its clock wraps without a jump. */
    const val ORB_LOOP_S = 60f

    fun <T> state(reduced: Boolean): AnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.9f, stiffness = 600f)

    fun <T> color(reduced: Boolean): AnimationSpec<T> = if (reduced) snap() else tween(COLOR_MS)
}
