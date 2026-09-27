package app.pillion.safety

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** A position for the SOS SMS: where, how exact, and how old. */
data class SosFix(val lat: Double, val lng: Double, val accuracyM: Float, val ageMs: Long)

/**
 * The SOS text messages. Plain Latin script on purpose: one Devanagari character switches the whole
 * SMS to UCS-2 (70 characters a part instead of 160), so the Hindi line is written in Roman Hindi
 * and the first SOS fits in two parts. The link is a plain Google Maps URL (no key, opens anywhere).
 */
object SosMessages {

    /** Why the SOS went: a detected crash, or the rider asked for it. */
    enum class Reason { Crash, RiderAsked }

    /** [liveLink]: Live Guardian page (hear, talk to and follow the rider), when the ride has one. */
    fun first(riderName: String, reason: Reason, atMs: Long, fix: SosFix?, liveLink: String? = null): String {
        val name = displayName(riderName)
        val (what, hindi) = when (reason) {
            Reason.Crash -> "may have had a road accident" to "$name ka accident ho sakta hai, turant call karein."
            Reason.RiderAsked -> "asked for help" to "$name ko madad chahiye, turant call karein."
        }
        val live = liveLink?.let { " Hear and talk to $name live: $it" }.orEmpty()
        return "PILLION SOS: $name $what at ${clock(atMs)}. Location: ${location(fix)}. Please call now.$live $hindi"
    }

    fun followUp(riderName: String, atMs: Long, fix: SosFix?): String =
        "PILLION SOS update ${clock(atMs)}: ${displayName(riderName)}'s location ${location(fix)}."

    fun riderOk(riderName: String, atMs: Long): String {
        val name = displayName(riderName)
        return "PILLION: $name pressed I'M OK at ${clock(atMs)}. $name ne bataya ki woh theek hain."
    }

    fun location(fix: SosFix?): String {
        if (fix == null) return "not available (no GPS fix)"
        val link = String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f", fix.lat, fix.lng)
        val minutes = fix.ageMs / 60_000
        val age = when {
            minutes >= 120 -> ", from ${minutes / 60} h ago"
            minutes >= 1 -> ", from $minutes min ago"
            else -> ""
        }
        return "$link (accuracy ${fix.accuracyM.roundToInt()} m$age)"
    }

    private fun displayName(riderName: String) = riderName.trim().ifEmpty { "Your contact" }

    private fun clock(atMs: Long) = SimpleDateFormat("h:mm a", Locale.US).format(Date(atMs))
}
