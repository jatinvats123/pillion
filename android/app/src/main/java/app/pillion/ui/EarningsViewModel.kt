package app.pillion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pillion.data.EarningsDb
import app.pillion.pillion
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The last 7 days (today last) and the chosen day's trips, from the on-phone trip history. */
data class EarningsWeek(
    val days: List<EarningsDb.DayTotal>,
    val selected: Int,
    val trips: List<EarningsDb.Trip>,
    /** Some of these trips are the seeded demo history (EarningsDb), not real rides. */
    val hasSample: Boolean,
)

class EarningsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = application.pillion.db
    private val _week = MutableStateFlow<EarningsWeek?>(null)
    val week: StateFlow<EarningsWeek?> = _week.asStateFlow()

    /** Reloads (the screen calls it on every return: a ride may have ended meanwhile). */
    fun refresh() = load(selected = _week.value?.selected ?: TODAY)

    fun select(index: Int) = load(selected = index)

    private fun load(selected: Int) {
        viewModelScope.launch {
            _week.value = withContext(Dispatchers.IO) {
                val days = db.dailyTotals(DAYS)
                val day = days[selected]
                EarningsWeek(
                    days = days,
                    selected = selected,
                    trips = db.trips(day.dayStart, nextDay(day.dayStart)),
                    hasSample = db.hasSeededTrips(days.first().dayStart, nextDay(days.last().dayStart)),
                )
            }
        }
    }

    private fun nextDay(dayStart: Long) = Calendar.getInstance().run {
        timeInMillis = dayStart
        add(Calendar.DAY_OF_YEAR, 1)
        timeInMillis
    }

    private companion object {
        const val DAYS = 7
        const val TODAY = DAYS - 1
    }
}
