package app.pillion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.pillion.R
import app.pillion.ui.components.ScreenHeader

/** This week's earnings (built in the next step). */
@Composable
fun EarningsRoute() {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(stringResource(R.string.nav_earnings))
    }
}
