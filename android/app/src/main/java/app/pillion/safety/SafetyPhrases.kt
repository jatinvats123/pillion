package app.pillion.safety

import java.util.Locale

/** The rider's language, for what Pillion says itself in a safety alert. */
enum class RiderLanguage { Hindi, English, Unknown }

/** What the rider's spoken reply during an alert means. */
enum class Reply { Cancel, Help, Unclear }

/**
 * Safety phrases, all in one place: what cancels an alert or sends the SOS at once, and what
 * Pillion says during one. Classification runs on the phone on purpose — it must not depend on
 * the backend, Jev or the LLM — and anything unclear keeps the countdown running: a false alarm
 * costs an awkward SMS, a missed crash can cost a life.
 *
 * Replies arrive as final ASR transcripts (Sarvam writes Hindi and most Hinglish in Devanagari,
 * English in Latin script), so every phrase has both spellings. Phrases match whole words after
 * [normalize]; the lists are checked in order: [NOT_HELP], then [HELP], then [CANCEL].
 */
object SafetyPhrases {

    /** Turning help down ("I don't need help", "SOS mat bhejo"): cancels, although it contains "help"/"SOS". */
    val NOT_HELP = listOf(
        "dont need help", "do not need help", "no help needed", "dont need any help", "dont send", "do not send",
        "madad nahi chahiye", "madad nahin chahiye", "help nahi chahiye", "help nahin chahiye", "koi madad nahi",
        "sos mat", "mat bhejo", "sos nahi",
        "मदद नहीं चाहिए", "हेल्प नहीं चाहिए", "कोई मदद नहीं", "एसओएस मत", "मत भेजो",
    )

    /** Send the SOS now. Includes "not OK" and "don't cancel", which also contain cancel words. */
    val HELP = listOf(
        "help", "help me", "sos", "emergency", "ambulance", "hospital", "call 112",
        "not ok", "not okay", "not fine", "not alright", "dont cancel", "do not cancel",
        "madad", "madad karo", "bachao", "bachaao", "bacha lo", "chot lagi", "ambulance bulao",
        "theek nahi", "theek nahin", "thik nahi", "thik nahin", "theek nhi", "thik nhi", "cancel mat", "mat cancel",
        "मदद", "बचाओ", "बचा लो", "हेल्प", "एसओएस", "इमरजेंसी", "एंबुलेंस", "एम्बुलेंस", "हॉस्पिटल", "अस्पताल",
        "चोट लगी", "ठीक नहीं", "कैंसल मत", "मत कैंसल",
    )

    /** The rider is fine: stop the alert. Bare "theek hai", "haan", "okay" are too loose (noise, echo). */
    val CANCEL = listOf(
        "im fine", "i am fine", "im ok", "i am ok", "im okay", "i am okay", "im alright", "i am alright",
        "im good", "i am good", "all good", "all fine", "no problem", "false alarm", "cancel", "cancel it", "cancel karo",
        "theek hoon", "theek hu", "theek hun", "thik hoon", "thik hu", "thik hun", "main theek", "mai theek",
        "main thik", "mai thik", "sab theek", "sab thik", "koi baat nahi", "koi baat nahin", "kuch nahi hua", "kuch nahin hua",
        "ठीक हूं", "ठीक हु", "ठीक हुं", "मैं ठीक", "में ठीक", "सब ठीक", "कोई बात नहीं", "कुछ नहीं हुआ",
        "कैंसल", "कैन्सल", "नो प्रॉब्लम", "आई एम फाइन", "आई एम ओके", "फाइन हूं", "ओके हूं",
    )

    /** Latin-script words that mark a sentence as Hinglish (answered in Hindi). */
    private val HINGLISH_WORDS = setOf(
        "hai", "hain", "hoon", "hu", "kya", "nahi", "nahin", "mujhe", "mera", "meri", "kitna", "kitni", "karo",
        "bolo", "batao", "bhai", "aaj", "kaise", "kahan", "kyun", "accha", "acha", "theek", "thik", "haan",
        "aap", "tum", "chahiye", "bhejo", "jaldi", "raha", "rahi", "gaya", "gayi", "wala", "abhi",
    )

    fun classify(transcript: String): Reply {
        val text = " ${normalize(transcript)} "
        fun List<String>.found() = any { phrase -> " ${normalize(phrase)} " in text }
        return when {
            NOT_HELP.found() -> Reply.Cancel
            HELP.found() -> Reply.Help
            CANCEL.found() -> Reply.Cancel
            else -> Reply.Unclear
        }
    }

    fun languageOf(transcript: String): RiderLanguage {
        if (transcript.any { it in '\u0900'..'\u097F' }) return RiderLanguage.Hindi
        val words = normalize(transcript).split(' ').filter { it.isNotEmpty() }
        return when {
            words.isEmpty() -> RiderLanguage.Unknown
            words.any { it in HINGLISH_WORDS } -> RiderLanguage.Hindi
            else -> RiderLanguage.English
        }
    }

    /**
     * Lower case, apostrophes dropped ("I'm" → "im"), चन्द्रबिन्दु folded into अनुस्वार (हूँ → हूं),
     * nukta dropped, punctuation (incl. ।) turned into spaces. Devanagari vowel signs are kept.
     */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text.lowercase(Locale.ROOT)) {
            when {
                c == '\'' || c == '\u2019' || c == '\u093C' -> Unit
                c == '\u0901' -> out.append('\u0902')
                c.isLetterOrDigit() || Character.getType(c).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() } -> out.append(c)
                else -> out.append(' ')
            }
        }
        return out.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }
}

/** What Pillion says itself during a safety alert, in Hindi (Devanagari) and English. */
data class SafetyLine(val hindi: String, val english: String) {
    /** Unknown language: Hindi (most riders speak it) then English. */
    fun forLanguage(language: RiderLanguage): String = when (language) {
        RiderLanguage.Hindi -> hindi
        RiderLanguage.English -> english
        RiderLanguage.Unknown -> "$hindi $english"
    }

    companion object {
        val ARE_YOU_OK = SafetyLine("आप ठीक हो? कुछ बोलो।", "Are you okay? Say something.")
        // Lines spoken during a countdown contain no phrase from the lists above (a test checks it):
        // if the mic picked them up, Pillion must not cancel or send its own alert.
        val ARE_YOU_OK_AGAIN = SafetyLine(
            "ठीक हो तो जवाब दो, या बटन दबाओ। वरना 10 second में आपके contacts को message जाएगा।",
            "If you're okay, answer me or press the button. Otherwise your contacts get a message in 10 seconds.",
        )
        val MANUAL_COUNTDOWN = SafetyLine(
            "5 second में आपके contacts को message जाएगा। रोकना हो तो बटन दबाइए।",
            "Messaging your contacts in 5 seconds. Press the button to stop.",
        )
        val CANCELLED = SafetyLine("ठीक है, SOS रोक दिया। ध्यान से चलिए।", "Okay, SOS cancelled. Ride safe.")
        val SENDING_NOW = SafetyLine("अभी SOS भेज रही हूँ।", "Sending SOS now.")
        val SOS_FAILED = SafetyLine("SOS SMS नहीं जा पाया। 112 पर call कीजिए।", "The SOS SMS could not be sent. Call 112.")
        val NO_SMS_PERMISSION = SafetyLine(
            "SMS की permission बंद है, SOS नहीं भेज सकती। 112 पर call कीजिए।",
            "SMS permission is off, so I can't send the SOS. Call 112.",
        )
        val NO_CONTACTS = SafetyLine("कोई emergency contact नहीं है। 112 पर call कीजिए।", "There's no emergency contact. Call 112.")
        val RIDER_OK_TOLD = SafetyLine("ठीक है, मैंने आपके contacts को बता दिया कि आप ठीक हैं।", "Okay, I've told your contacts you're okay.")
        val NOT_SET_UP = SafetyLine(
            "ध्यान दें: SOS के लिए कोई emergency contact नहीं है। App में contact जोड़ लीजिए।",
            "Note: SOS has no emergency contact yet. Add one in the app.",
        )

        fun sosSent(sent: Int, total: Int) = when {
            sent == total && sent == 1 -> SafetyLine("मैंने आपके contact को आपकी location भेज दी है।", "I've sent your location to your contact.")
            sent == total -> SafetyLine("मैंने आपके $sent contacts को आपकी location भेज दी है।", "I've sent your location to $sent contacts.")
            else -> SafetyLine(
                "$total में से $sent contacts को location गई, बाकी को नहीं जा पाई।",
                "Your location went to $sent of $total contacts; the rest failed.",
            )
        }

        fun takeABreak(ridingMinutes: Long) = if (ridingMinutes >= 60) {
            val hours = ridingMinutes / 60
            SafetyLine(
                "आप $hours घंटे से लगातार चला रहे हैं। थोड़ा break लीजिए, पानी पी लीजिए।",
                "You've been riding for $hours ${if (hours == 1L) "hour" else "hours"}. Take a short break and drink some water.",
            )
        } else {
            SafetyLine(
                "आप $ridingMinutes minute से लगातार चला रहे हैं। थोड़ा break लीजिए, पानी पी लीजिए।",
                "You've been riding for $ridingMinutes minutes. Take a short break and drink some water.",
            )
        }
    }
}
