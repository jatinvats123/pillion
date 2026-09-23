package app.pillion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Plain dark placeholder theme; the real visual direction comes in a later phase.
private val PillionColors = darkColorScheme(
    primary = Color(0xFFFFC400),
    onPrimary = Color(0xFF1A1400),
    primaryContainer = Color(0xFF3A3000),
    onPrimaryContainer = Color(0xFFFFE08A),
    secondaryContainer = Color(0xFF2A2D33),
    onSecondaryContainer = Color(0xFFE3E5EA),
    error = Color(0xFFFF6B5E),
    errorContainer = Color(0xFF4A1512),
    onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF101114),
    onBackground = Color(0xFFF2F3F5),
    surface = Color(0xFF101114),
    onSurface = Color(0xFFF2F3F5),
    surfaceVariant = Color(0xFF1C1E23),
    onSurfaceVariant = Color(0xFFB9BDC6),
)

@Composable
fun PillionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PillionColors, content = content)
}
