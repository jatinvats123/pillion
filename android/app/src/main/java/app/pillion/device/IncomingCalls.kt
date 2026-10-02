package app.pillion.device

import app.pillion.data.Order
import app.pillion.safety.EmergencyContact
import app.pillion.safety.SafetyLine
import app.pillion.safety.SafetyPhrases

/** Who is calling, as Pillion may say it. `wireName` is what the backend sees; never the number. */
enum class CallerKind(val wireName: String) {
    Customer("customer"),
    EmergencyContact("emergency_contact"),
    Unknown("unknown"),
}

/** [name]: the customer's first name or the contact's name as the rider saved it. */
data class Caller(val kind: CallerKind, val name: String? = null)

/**
 * Matches an incoming call's number against the active order's customer, then the emergency
 * contacts, on the phone. Both sides are compared by their last 10 digits, so +91 / 91 / 0
 * prefixes, spaces and dashes don't matter. A sample order (invented customer) never matches;
 * no number (hidden, or Android 12+ without it) is an unknown caller.
 */
fun identifyCaller(number: String?, order: Order?, contacts: List<EmergencyContact>): Caller {
    val digits = number?.let(::lastTenDigits) ?: return Caller(CallerKind.Unknown)
    if (order != null && !order.isSample && lastTenDigits(order.customerPhone) == digits) {
        return Caller(CallerKind.Customer, order.customerName.trim().split(Regex("\\s+")).first().ifBlank { null })
    }
    contacts.firstOrNull { lastTenDigits(it.number) == digits }?.let {
        return Caller(CallerKind.EmergencyContact, it.name.trim().ifBlank { null })
    }
    return Caller(CallerKind.Unknown)
}

/** The last 10 digits of a phone number, or null if it has fewer (short codes, nothing). */
internal fun lastTenDigits(number: String): String? = number.filter { it in '0'..'9' }.takeIf { it.length >= 10 }?.takeLast(10)

/**
 * What the phone itself says while it rings (Android mutes other apps' media sound during a ring,
 * Pillion's voice included, but not the alarm channel). No word in it counts as a yes or no in
 * [CallReplies] (a test checks it), since the mic may pick it up from the loudspeaker.
 */
fun callQuestion(caller: Caller, orderActive: Boolean): SafetyLine = when {
    caller.kind == CallerKind.Customer && caller.name != null ->
        SafetyLine("Customer ${caller.name} का call आ रहा है। उठाऊँ?", "Your customer ${caller.name} is calling. Take it?")
    caller.kind == CallerKind.Customer -> SafetyLine("Customer का call आ रहा है। उठाऊँ?", "Your customer is calling. Take it?")
    caller.kind == CallerKind.EmergencyContact && caller.name != null ->
        SafetyLine("${caller.name} का call आ रहा है। उठाऊँ?", "${caller.name} is calling. Take it?")
    orderActive -> SafetyLine("Unknown number से call है, शायद customer हो। उठाऊँ?", "Unknown number calling, maybe your customer. Take it?")
    else -> SafetyLine("Unknown number से call है। उठाऊँ?", "Unknown number calling. Take it?")
}

/** What the rider's words mean for a ringing call. */
enum class CallReply { Answer, Decline, Unclear }

/**
 * The phone's own check of the rider's words before it answers a call (the LLM decided, this
 * confirms from the transcript, as SafetyPhrases does for the crash check). Whole words after
 * [SafetyPhrases.normalize]; "no" phrases first, since "mat uthao" contains "uthao".
 */
object CallReplies {
    val DECLINE = listOf(
        "no", "nope", "not now", "later", "call later", "reject", "decline", "cut", "cut it", "cut karo", "busy", "dont answer", "do not answer",
        "nahi", "nahin", "nhi", "na", "mat", "mat uthao", "mat utha", "baad mein", "baad me", "bad me", "abhi nahi", "kaat do", "kaato", "kaat de",
        "नहीं", "नही", "ना", "मत", "मत उठाओ", "बाद में", "अभी नहीं", "कट", "कट करो", "काट दो", "काटो", "काट दे", "रिजेक्ट", "नो", "बिजी", "लेटर",
    )

    val ANSWER = listOf(
        "yes", "yeah", "yep", "ok", "okay", "answer", "answer it", "pick up", "pick it up", "connect", "accept",
        "haan", "han", "haa", "ha", "haanji", "haan ji", "ji haan", "utha", "uthao", "utha lo", "uthalo", "utha do", "uthado", "theek hai", "thik hai", "kar do",
        "हां", "हा", "हांजी", "हां जी", "जी हां", "उठा", "उठाओ", "उठा लो", "उठालो", "उठा दो", "उठाइए", "ठीक है", "कर दो", "यस", "ओके", "आंसर", "कनेक्ट",
    )

    fun classify(transcript: String): CallReply {
        val text = " ${SafetyPhrases.normalize(transcript)} "
        fun List<String>.found() = any { phrase -> " ${SafetyPhrases.normalize(phrase)} " in text }
        return when {
            DECLINE.found() -> CallReply.Decline
            ANSWER.found() -> CallReply.Answer
            else -> CallReply.Unclear
        }
    }
}

/**
 * Whether Pillion's audio steps aside for the call. Today's rule: for any call, ringing or on.
 * With "Answer calls by voice" live for this ring ([askWhileRinging]), Pillion stays on while it
 * rings, so it can ask, and steps aside once the call is on.
 */
fun pausesPillion(state: CallState, askWhileRinging: Boolean): Boolean = when (state) {
    CallState.Idle -> false
    is CallState.Ringing -> !askWhileRinging
    CallState.Offhook -> true
}
