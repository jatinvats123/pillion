package app.pillion.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import java.util.Calendar
import kotlin.math.roundToInt
import kotlin.random.Random
import org.json.JSONObject

/**
 * The rider's trips, stored only on the phone.
 *
 * Delivery apps have no API, so on first launch the history is filled with SEEDED DEMO DATA:
 * 14 days of plausible East Delhi trips (12–18 a day, ₹600–1200 a day), marked `source = 'seed'`,
 * plus two past safety alerts. Rides ended in Pillion are added on top as `source = 'live'`.
 */
class EarningsDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "earnings.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE trips (
                id INTEGER PRIMARY KEY,
                started_at INTEGER NOT NULL,
                ended_at INTEGER NOT NULL,
                area TEXT NOT NULL,
                earned_rupees INTEGER NOT NULL,
                source TEXT NOT NULL)"""
        )
        db.execSQL("CREATE INDEX trips_ended_at ON trips(ended_at)")
        db.execSQL(
            """CREATE TABLE safety_alerts (
                id INTEGER PRIMARY KEY,
                at INTEGER NOT NULL,
                kind TEXT NOT NULL,
                note TEXT NOT NULL,
                source TEXT NOT NULL)"""
        )
        seedDemoHistory(db, System.currentTimeMillis())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun addLiveTrip(startedAt: Long, endedAt: Long, area: String, earnedRupees: Int) {
        writableDatabase.insert("trips", null, trip(startedAt, endedAt, area, earnedRupees, source = "live"))
    }

    /**
     * Today vs yesterday, with the arithmetic done here rather than by the LLM. "Yesterday until
     * this time" is the fair comparison while today is still running.
     */
    fun summary(now: Long = System.currentTimeMillis()): JSONObject {
        val todayStart = startOfDay(now)
        val yesterdayStart = startOfDay(now, daysAgo = 1)
        val today = totals(todayStart, now)
        val yesterday = totals(yesterdayStart, todayStart)
        val yesterdaySoFar = totals(yesterdayStart, yesterdayStart + (now - todayStart))
        return JSONObject()
            .put("today", today.toJson())
            .put("yesterday_full_day", yesterday.toJson())
            .put("yesterday_until_this_time", yesterdaySoFar.toJson())
            .put("today_minus_yesterday_until_this_time_rupees", today.rupees - yesterdaySoFar.rupees)
            .put("last_7_days", totals(startOfDay(now, daysAgo = 6), now).toJson())
    }

    private data class Totals(val trips: Int, val rupees: Int) {
        fun toJson(): JSONObject = JSONObject().put("trips", trips).put("earned_rupees", rupees)
    }

    private fun totals(from: Long, to: Long): Totals =
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(earned_rupees), 0) FROM trips WHERE ended_at >= ? AND ended_at < ?",
            arrayOf(from.toString(), to.toString()),
        ).use { cursor ->
            cursor.moveToFirst()
            Totals(trips = cursor.getInt(0), rupees = cursor.getInt(1))
        }

    // SEEDED DEMO DATA — generated once, never presented as live.
    private fun seedDemoHistory(db: SQLiteDatabase, now: Long) {
        val random = Random(SEED)
        db.transaction {
            for (daysAgo in 14 downTo 0) {
                val dayStart = startOfDay(now, daysAgo)
                val trips = random.nextInt(12, 19)
                val dayTotal = random.nextInt(600, 1201)
                val weights = List(trips) { random.nextDouble(0.6, 1.4) }
                var clock = dayStart + (9 * 60 + random.nextInt(0, 45)) * MINUTE
                for ((i, weight) in weights.withIndex()) {
                    val started = clock
                    val ended = started + random.nextInt(18, 41) * MINUTE
                    // Today only has the trips that already happened.
                    if (ended > now || ended > dayStart + 22 * 60 * MINUTE) break
                    val rupees = (dayTotal * weight / weights.sum()).roundToInt()
                    db.insert("trips", null, trip(started, ended, AREAS.random(random), rupees, source = "seed"))
                    // Lunch break halfway through the day.
                    clock = ended + random.nextInt(5, 21) * MINUTE + if (i == trips / 2) 45 * MINUTE else 0
                }
            }
            db.insert("safety_alerts", null, alert(startOfDay(now, 4) + 19 * 60 * MINUTE, "hard_brake", "Sudden braking near Karkardooma flyover"))
            db.insert("safety_alerts", null, alert(startOfDay(now, 9) + 13 * 60 * MINUTE, "fall_check", "Phone fall detected in Mayur Vihar; rider replied OK"))
        }
    }

    private fun trip(startedAt: Long, endedAt: Long, area: String, rupees: Int, source: String) = ContentValues().apply {
        put("started_at", startedAt)
        put("ended_at", endedAt)
        put("area", area)
        put("earned_rupees", rupees)
        put("source", source)
    }

    private fun alert(at: Long, kind: String, note: String) = ContentValues().apply {
        put("at", at)
        put("kind", kind)
        put("note", note)
        put("source", "seed")
    }

    private fun startOfDay(time: Long, daysAgo: Int = 0): Long = Calendar.getInstance().run {
        timeInMillis = time
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    private companion object {
        const val SEED = 2026
        const val MINUTE = 60_000L
        val AREAS = listOf(
            "Laxmi Nagar", "Preet Vihar", "Mayur Vihar Phase 1", "Patparganj", "Shakarpur", "Pandav Nagar",
            "Nirman Vihar", "Karkardooma", "Anand Vihar", "Vivek Vihar", "Krishna Nagar", "Geeta Colony",
            "Gandhi Nagar", "IP Extension",
        )
    }
}
