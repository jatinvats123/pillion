package app.pillion.order

import kotlin.math.max
import kotlin.math.min

/** One line of OCR text and its box on the image, in pixels. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

enum class Confidence { High, Medium, Low }

/** A value read off the screen and how sure the parser is. Only High and Medium are pre-filled for the rider. */
data class Field(val value: String, val confidence: Confidence)

/** Whose number it is, from the label next to it or the screen section it's in. */
enum class NumberRole { Customer, Store, Support, Other, Unknown }

/** A phone number found on the screen. [dial] is what the phone texts or calls. */
data class PhoneNumber(val dial: String, val shown: String, val role: NumberRole, val ocrFixed: Boolean)

data class ParsedOrder(
    val customerName: Field?,
    /** Set only when one number is clearly the customer's; otherwise the rider picks from [phoneNumbers]. */
    val customerPhone: Field?,
    /** Every real number found (customer, restaurant, support…), in screen order. */
    val phoneNumbers: List<PhoneNumber>,
    /** The customer's number as the delivery app shows it masked ("98XXX XX123"). Never un-masked. */
    val maskedPhone: String?,
    /** No number next to the customer, only a Call button: the delivery app calls through its own line. */
    val callButtonOnly: Boolean,
    val dropAddress: Field?,
    /** The locality ("Laxmi Nagar"), for the trip log and the spoken confirmation. Best effort. */
    val dropArea: String?,
    val orderId: Field?,
) {
    val isEmpty: Boolean
        get() = customerName == null && customerPhone == null && phoneNumbers.isEmpty() && maskedPhone == null && dropAddress == null
}

/**
 * Reads a delivery app's order screen from OCR lines: customer name, phone number, drop address
 * and order ID. Works from the screen's structure — labels ("Customer", "Drop address", "पता"),
 * section headings (Pickup / Drop) and the shape of the text — and never fills in anything it
 * didn't read: a masked number stays masked, a missing field stays empty.
 */
object OrderParser {

    /** Plain text, one OCR line per text line (tests, and OCR without boxes). */
    fun parse(text: String): ParsedOrder =
        parse(text.lines().mapIndexed { i, line -> OcrLine(line, 0, i * 50, 1000, i * 50 + 40) })

    fun parse(lines: List<OcrLine>): ParsedOrder {
        val rows = groupRows(lines).mapIndexed { i, cells -> Row(i, cells) }
        var section: Section? = null
        for (row in rows) {
            val match = matchLabel(row)
            row.label = match
            row.section = match?.section ?: section
            // A heading, or "Customer: Rahul" / "Restaurant: …", starts a block; a one-line
            // "Customer care: 1800…" or "Order ID: …" doesn't.
            if (match?.section != null && (match.value.isEmpty() || (match.section != Section.Support && match.kind != Kind.OrderId))) {
                section = match.section
            }
        }
        val numbers = rows.flatMap { findNumbers(it) }
        val real = numbers.filterIsInstance<Found.Real>()
        val customerNumbers = real.filter { it.number.role == NumberRole.Customer }.distinctBy { it.number.dial }
        val masked = numbers.filterIsInstance<Found.Masked>()
            .firstOrNull { it.role == NumberRole.Customer || it.role == NumberRole.Unknown }
            ?.takeIf { customerNumbers.isEmpty() }
        val phone = when {
            customerNumbers.size == 1 -> customerNumbers[0].number.let { Field(it.dial, if (it.ocrFixed) Confidence.Medium else Confidence.High) }
            customerNumbers.size > 1 || masked != null -> null
            else -> real.filter { it.number.role == NumberRole.Unknown && it.mobile }.distinctBy { it.number.dial }
                .singleOrNull()?.number?.let { Field(it.dial, if (it.ocrFixed) Confidence.Low else Confidence.Medium) }
        }
        val address = findAddress(rows)
        return ParsedOrder(
            customerName = findName(rows),
            customerPhone = phone,
            phoneNumbers = real.map { it.number }.distinctBy { it.dial },
            maskedPhone = masked?.shown,
            callButtonOnly = phone == null && masked == null && customerNumbers.isEmpty() && hasCallButton(rows),
            dropAddress = address,
            dropArea = address?.let { areaOf(it.value) },
            orderId = findOrderId(rows),
        )
    }

    // ---- Rows: OCR lines at the same height are one row (label on the left, value on the right).

    private class Row(val index: Int, val cells: List<String>) {
        val text = cells.joinToString("  ")
        var label: LabelMatch? = null
        var section: Section? = null
    }

    private fun groupRows(lines: List<OcrLine>): List<List<String>> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in lines.filter { it.text.isNotBlank() }.sortedBy { it.top + it.bottom }) {
            val row = rows.lastOrNull()
            if (row != null && row.all { sameRow(it, line) }) row += line else rows += mutableListOf(line)
        }
        return rows.map { row -> row.sortedBy { it.left }.map { it.text.trim() } }
    }

    private fun sameRow(a: OcrLine, b: OcrLine): Boolean {
        val vertical = min(a.bottom, b.bottom) - max(a.top, b.top)
        val horizontal = min(a.right, b.right) - max(a.left, b.left)
        return vertical >= 0.5 * min(a.height, b.height) && horizontal <= 0
    }

    // ---- Labels and sections.

    private enum class Section { Customer, Store, Support, Other }

    /** What a label introduces. Heading: a section title. Stop: never a value, and it ends an address. */
    private enum class Kind { Name, Address, Phone, OrderId, Heading, Stop }

    private class Label(val words: List<String>, val kind: Kind, val section: Section?)

    private class LabelMatch(val label: Label, val value: String) {
        val kind get() = label.kind
        val section get() = label.section
    }

    private fun label(text: String, kind: Kind, section: Section? = null) = Label(text.split(' ').map(::norm), kind, section)

    private val LABELS = listOf(
        // The customer and the drop.
        label("customer", Kind.Name, Section.Customer),
        label("customer name", Kind.Name, Section.Customer),
        label("customer details", Kind.Heading, Section.Customer),
        label("customer info", Kind.Heading, Section.Customer),
        label("customer address", Kind.Address, Section.Customer),
        label("customer phone", Kind.Phone, Section.Customer),
        label("customer mobile", Kind.Phone, Section.Customer),
        label("customer number", Kind.Phone, Section.Customer),
        label("customer contact", Kind.Phone, Section.Customer),
        label("deliver to", Kind.Name, Section.Customer),
        label("delivering to", Kind.Name, Section.Customer),
        label("delivery to", Kind.Name, Section.Customer),
        label("receiver", Kind.Name, Section.Customer),
        label("receiver name", Kind.Name, Section.Customer),
        label("recipient", Kind.Name, Section.Customer),
        label("grahak", Kind.Name, Section.Customer),
        label("grahak naam", Kind.Name, Section.Customer),
        label("grahak ka naam", Kind.Name, Section.Customer),
        label("ग्राहक", Kind.Name, Section.Customer),
        label("ग्राहक का नाम", Kind.Name, Section.Customer),
        label("drop", Kind.Address, Section.Customer),
        label("drop at", Kind.Address, Section.Customer),
        label("drop address", Kind.Address, Section.Customer),
        label("drop location", Kind.Address, Section.Customer),
        label("drop point", Kind.Address, Section.Customer),
        label("drop ka pata", Kind.Address, Section.Customer),
        label("drop details", Kind.Heading, Section.Customer),
        label("deliver at", Kind.Address, Section.Customer),
        label("delivery address", Kind.Address, Section.Customer),
        label("delivery location", Kind.Address, Section.Customer),
        label("delivery details", Kind.Heading, Section.Customer),
        label("shipping address", Kind.Address, Section.Customer),
        label("grahak ka pata", Kind.Address, Section.Customer),
        label("ग्राहक का पता", Kind.Address, Section.Customer),
        // Generic: they belong to whatever section they're in.
        label("name", Kind.Name),
        label("naam", Kind.Name),
        label("नाम", Kind.Name),
        label("address", Kind.Address),
        label("pata", Kind.Address),
        label("पता", Kind.Address),
        label("location", Kind.Address),
        label("phone", Kind.Phone),
        label("phone no", Kind.Phone),
        label("phone number", Kind.Phone),
        label("mobile", Kind.Phone),
        label("mobile no", Kind.Phone),
        label("mobile number", Kind.Phone),
        label("mob", Kind.Phone),
        label("ph", Kind.Phone),
        label("contact", Kind.Phone),
        label("contact no", Kind.Phone),
        label("contact number", Kind.Phone),
        label("फोन", Kind.Phone),
        label("मोबाइल", Kind.Phone),
        // Order ID and the blocks that aren't about the customer.
        label("order", Kind.OrderId, Section.Other),
        label("order id", Kind.OrderId, Section.Other),
        label("order no", Kind.OrderId, Section.Other),
        label("order number", Kind.OrderId, Section.Other),
        label("ऑर्डर", Kind.OrderId, Section.Other),
        label("ऑर्डर आईडी", Kind.OrderId, Section.Other),
        label("order details", Kind.Heading, Section.Other),
        label("order summary", Kind.Heading, Section.Other),
        label("items", Kind.Heading, Section.Other),
        label("bill details", Kind.Heading, Section.Other),
        label("bill", Kind.Heading, Section.Other),
        label("payment", Kind.Heading, Section.Other),
        label("item total", Kind.Heading, Section.Other),
        label("total", Kind.Heading, Section.Other),
        // The pickup side.
        label("pickup", Kind.Heading, Section.Store),
        label("pick up", Kind.Heading, Section.Store),
        label("pickup from", Kind.Heading, Section.Store),
        label("pickup details", Kind.Heading, Section.Store),
        label("pickup location", Kind.Heading, Section.Store),
        label("pickup address", Kind.Heading, Section.Store),
        label("restaurant", Kind.Heading, Section.Store),
        label("restaurant name", Kind.Heading, Section.Store),
        label("store", Kind.Heading, Section.Store),
        label("shop", Kind.Heading, Section.Store),
        label("merchant", Kind.Heading, Section.Store),
        label("outlet", Kind.Heading, Section.Store),
        label("seller", Kind.Heading, Section.Store),
        label("sender", Kind.Heading, Section.Store),
        label("kitchen", Kind.Heading, Section.Store),
        label("dukaan", Kind.Heading, Section.Store),
        label("दुकान", Kind.Heading, Section.Store),
        // Support lines.
        label("customer care", Kind.Heading, Section.Support),
        label("customer support", Kind.Heading, Section.Support),
        label("support", Kind.Heading, Section.Support),
        label("helpline", Kind.Heading, Section.Support),
        label("help centre", Kind.Heading, Section.Support),
        label("help center", Kind.Heading, Section.Support),
        label("toll free", Kind.Heading, Section.Support),
        // Never a name, number or address.
        label("otp", Kind.Stop),
        label("landmark", Kind.Stop),
        label("note", Kind.Stop),
        label("instructions", Kind.Stop),
        label("delivery instructions", Kind.Stop),
    )

    private fun words(vararg words: String): Set<String> = words.map(::norm).toSet()

    private val PREPOSITIONS = words("to", "at", "from")
    private val CALL = norm("call")

    private val TOKEN = Regex("""[^\s:]+|:""")
    private val SEPARATORS = setOf(":", "-", "–", "—", "|", "·", "•", "=")

    /**
     * The label at the start of the row's first cell, and the value after it. A value on the same
     * row must be set apart from the label (":", "-", its own cell, or "deliver to …"), so a
     * sentence that merely starts with a label word isn't read as one.
     */
    private fun matchLabel(row: Row): LabelMatch? {
        val first = row.cells.first()
        val tokens = TOKEN.findAll(first).toList()
        val start = tokens.indexOfFirst { norm(it.value).isNotEmpty() }.takeIf { it in 0..1 } ?: return null
        val words = tokens.drop(start).map { norm(it.value) }
        val label = LABELS
            .filter { label -> label.words.size <= words.size && label.words.indices.all { similar(label.words[it], words[it]) } }
            .maxByOrNull { it.words.size } ?: return null
        var next = start + label.words.size
        val separated = next < tokens.size && tokens[next].value in SEPARATORS
        while (next < tokens.size && tokens[next].value in SEPARATORS) next++
        val inCell = if (next < tokens.size) first.substring(tokens[next].range.first).trim() else ""
        val prepositional = label.words.last() in PREPOSITIONS || inCell.startsWith("#")
        if (inCell.isNotEmpty() && !separated && !prepositional) return null
        val value = (listOf(inCell) + row.cells.drop(1)).filter { it.isNotBlank() }.joinToString(" ")
        return LabelMatch(label, value)
    }

    /** Lower case, OCR look-alikes folded (0→o; 1, l, | → i; 5→s; rn→m), letters only. Every word list goes through it too. */
    private fun norm(word: String): String = word.lowercase()
        .replace("़", "") // nukta: फ़ोन = फोन
        .map { c ->
            when (c) {
                '0' -> 'o'
                '1', '|', 'l' -> 'i'
                '5' -> 's'
                else -> c
            }
        }
        .joinToString("")
        .replace("rn", "m")
        .filter { it.isLetter() || it in 'ऀ'..'ॿ' }

    /** Equal, or for words of 5+ letters one misread letter (OCR substitutes; it rarely adds or drops). */
    private fun similar(label: String, word: String): Boolean {
        if (label == word) return true
        if (label.length < 5 || label.length != word.length) return false
        return label.indices.count { label[it] != word[it] } <= 1
    }

    // ---- Phone numbers.

    private sealed interface Found {
        class Real(val number: PhoneNumber, val mobile: Boolean) : Found
        class Masked(val shown: String, val role: NumberRole) : Found
    }

    private val NUMERIC = Regex("""^\+?[0-9OoIl|][0-9OoIl|\-.]*$""")
    private val MASKABLE = Regex("""^\+?[0-9Xx*•●∙\-.]+$""")
    private const val MASK_CHARS = "Xx*•●∙"

    private fun findNumbers(row: Row): List<Found> {
        if (row.label?.kind == Kind.OrderId || row.label?.kind == Kind.Stop) return emptyList()
        val tokens = Regex("""\S+""").findAll(row.text).map { match ->
            val core = match.value.trim('(', ')', '[', ']', ',', ';', ':', '.')
            val numeric = NUMERIC.matches(core) && core.any { it.isDigit() }
            val masked = !numeric && MASKABLE.matches(core) && core.any { it in MASK_CHARS }
            Triple(match, core, if (numeric) 'n' else if (masked) 'm' else ' ')
        }.toList()

        val found = mutableListOf<Found>()
        var i = 0
        while (i < tokens.size) {
            if (tokens[i].third == ' ') {
                i++
                continue
            }
            var end = i
            while (end + 1 < tokens.size && tokens[end + 1].third != ' ') end++
            val run = tokens.subList(i, end + 1)
            val before = row.text.substring(0, run.first().first.range.first)
            if (run.any { it.third == 'm' }) {
                maskedNumber(run.joinToString("") { it.second })?.let {
                    val shown = row.text.substring(run.first().first.range.first, run.last().first.range.last + 1)
                    found += Found.Masked(shown.trim('(', ')', ',', ';', '.'), roleOf(before, row))
                }
            } else {
                found += realNumbers(run.map { it.second }).map { (number, mobile) ->
                    Found.Real(number.copy(role = if (number.role == NumberRole.Support) NumberRole.Support else roleOf(before, row)), mobile)
                }
            }
            i = end + 1
        }
        return found
    }

    /** Numbers in a run of numeric tokens, longest first from each token: "48 98765 43210" → one mobile. */
    private fun realNumbers(tokens: List<String>): List<Pair<PhoneNumber, Boolean>> {
        val numbers = mutableListOf<Pair<PhoneNumber, Boolean>>()
        var i = 0
        while (i < tokens.size) {
            val hit = (min(tokens.lastIndex, i + 5) downTo i).firstNotNullOfOrNull { j ->
                val raw = tokens.subList(i, j + 1).joinToString("")
                phoneNumber(raw)?.let { it to j }
            }
            if (hit == null) {
                i++
            } else {
                numbers += hit.first
                i = hit.second + 1
            }
        }
        return numbers
    }

    /**
     * An Indian number from OCR characters, or null. Only visually identical glyphs are corrected
     * (O→0, I/l/|→1); a digit is never changed or added.
     */
    private fun phoneNumber(raw: String): Pair<PhoneNumber, Boolean>? {
        val body = raw.filter { it != '-' && it != '.' && it != '+' }
        val digits = body.map { c ->
            when (c) {
                'O', 'o' -> '0'
                'I', 'l', '|' -> '1'
                else -> c
            }
        }.joinToString("")
        if (!digits.all { it.isDigit() }) return null
        val fixed = digits != body
        fun mobile(ten: String) = PhoneNumber("+91$ten", "+91 ${ten.take(5)} ${ten.drop(5)}", NumberRole.Unknown, fixed) to true
        fun landline(withZero: String) = PhoneNumber(withZero, withZero, NumberRole.Unknown, fixed) to false
        return when {
            (digits.length == 10 || digits.length == 11) && (digits.startsWith("1800") || digits.startsWith("1860")) ->
                PhoneNumber(digits, digits, NumberRole.Support, fixed) to false
            digits.length == 10 && digits[0] in '6'..'9' -> mobile(digits)
            digits.length == 11 && digits[0] == '0' && digits[1] in '6'..'9' -> mobile(digits.drop(1))
            digits.length == 12 && digits.startsWith("91") && digits[2] in '6'..'9' -> mobile(digits.drop(2))
            digits.length == 11 && digits[0] == '0' && digits[1] in '1'..'5' -> landline(digits)
            digits.length == 12 && digits.startsWith("91") && digits[2] in '1'..'5' -> landline("0" + digits.drop(2))
            else -> null
        }
    }

    /** "98XXX XX123", "+91 ******3210", "98•••••210": 10 places, at least two hidden. */
    private fun maskedNumber(raw: String): String? {
        var places = raw.filter { it.isDigit() || it in MASK_CHARS }
        if (raw.startsWith("+") && places.startsWith("91")) places = places.drop(2)
        if (places.length == 12 && places.startsWith("91")) places = places.drop(2)
        if (places.length == 11 && places.startsWith("0")) places = places.drop(1)
        return places.takeIf { it.length == 10 && it.count { c -> c in MASK_CHARS } >= 2 }
    }

    private val SUPPORT_WORDS = words("care", "support", "helpline", "toll", "help")
    private val STORE_WORDS = words("restaurant", "store", "shop", "merchant", "outlet", "seller", "sender", "pickup", "kitchen", "dukaan", "दुकान")
    private val CUSTOMER_WORDS = words("customer", "grahak", "ग्राहक", "receiver", "recipient", "drop")
    private val OTHER_WORDS = words("partner", "rider", "driver", "executive", "agent", "your")

    /** The label just before the number on its row wins; otherwise the section it's in. */
    private fun roleOf(before: String, row: Row): NumberRole {
        val words = before.split(Regex("""\s+""")).map(::norm).filter { it.isNotEmpty() }.toSet()
        return when {
            words.any { it in SUPPORT_WORDS } -> NumberRole.Support
            words.any { it in OTHER_WORDS } -> NumberRole.Other
            words.any { it in STORE_WORDS } -> NumberRole.Store
            words.any { it in CUSTOMER_WORDS } -> NumberRole.Customer
            else -> when (row.section) {
                Section.Customer -> NumberRole.Customer
                Section.Store -> NumberRole.Store
                Section.Support -> NumberRole.Support
                Section.Other -> NumberRole.Other
                null -> NumberRole.Unknown
            }
        }
    }

    private fun hasCallButton(rows: List<Row>): Boolean {
        val customerRows = rows.filter { it.section == Section.Customer }.ifEmpty { rows.filter { it.section == null } }
        return customerRows.any { row -> row.cells.any { cell -> norm(cell.substringBefore(' ')) == CALL } }
    }

    // ---- Customer name.

    private val UI_WORDS = words(
        "call", "chat", "message", "navigate", "navigation", "directions", "direction", "map", "maps", "view", "details",
        "order", "orders", "items", "item", "paid", "cash", "cod", "online", "prepaid", "delivered", "deliver", "delivery",
        "pickup", "picked", "drop", "dropped", "arrived", "reached", "reach", "start", "slide", "swipe", "accept", "reject",
        "decline", "confirm", "cancel", "help", "support", "total", "bill", "amount", "earning", "earnings", "pay",
        "payment", "rs", "inr", "min", "mins", "minutes", "km", "am", "pm", "today", "otp", "id", "phone", "mobile",
        "number", "contact", "customer", "restaurant", "store", "shop", "address", "name", "status", "ready", "preparing",
        "trip", "back", "next", "home", "menu", "settings", "profile", "rating", "tip", "collect", "qty", "eta", "mark",
        "go", "ok", "done", "sms", "whatsapp", "new", "pending", "active", "completed", "location", "hindi", "english",
    )

    private fun findName(rows: List<Row>): Field? {
        for (row in rows) {
            val match = row.label ?: continue
            if (match.kind != Kind.Name && !(match.kind == Kind.Heading && match.section == Section.Customer)) continue
            val confidence = when {
                match.section == Section.Customer || row.section == Section.Customer -> Confidence.High
                row.section == null -> Confidence.Medium
                else -> continue // a generic "Name" in the restaurant or bill block
            }
            val value = match.value.ifEmpty {
                rows.getOrNull(row.index + 1)?.takeIf { it.label == null }?.text.orEmpty()
            }
            cleanName(value)?.let { return Field(it, confidence) }
        }
        // A Drop / Customer heading followed by an unlabelled name, before the address.
        for (row in rows) {
            val heading = row.label ?: continue
            if (heading.section != Section.Customer || heading.value.isNotEmpty()) continue
            rows.drop(row.index + 1).take(3).takeWhile { it.label == null }
                .firstOrNull { addressScore(it.text) == 0 && !it.text.any(Char::isDigit) }
                ?.let { cleanName(it.text) }
                ?.let { return Field(it, Confidence.Medium) }
        }
        return null
    }

    private val NAME_WORD = Regex("""^(\p{L}[\p{L}'\-]*\.?|[ऀ-ॿ]+)$""")

    /** A person's name: 1–4 words of letters, not an app's button or heading. Trailing "★4.8", "Call" dropped. */
    private fun cleanName(raw: String): String? {
        val words = raw.replace('।', ' ').split(Regex("""\s+""")).filter { it.isNotBlank() }.toMutableList()
        while (words.isNotEmpty() && (words.last().none { it.isLetter() } || norm(words.last()) in UI_WORDS)) words.removeAt(words.lastIndex)
        if (words.isEmpty() || words.size > 4) return null
        if (words.any { !NAME_WORD.matches(it) || norm(it) in UI_WORDS }) return null
        if (words.none { w -> w.count { it.isLetter() || it in 'ऀ'..'ॿ' } >= 2 }) return null
        return words.joinToString(" ").takeIf { it.length <= 40 }
    }

    // ---- Drop address.

    private val PINCODE = Regex("""(?<!\d)[1-8]\d{2}\s?\d{3}(?!\d)""")
    private val HOUSE_NUMBER = Regex("""(?i)(?<![\p{L}\d])(?:[A-Z]{1,2}-?\d{1,4}|\d{1,4}/\d{1,4}|#\s?\d{1,4}|no\.?\s?\d{1,4})(?![\d/])""")
    private val ADDRESS_WORDS = words(
        "flat", "house", "hno", "plot", "floor", "block", "sector", "pocket", "phase", "gali", "lane", "street", "road",
        "rd", "marg", "nagar", "vihar", "enclave", "colony", "apartment", "apartments", "apts", "tower", "society",
        "near", "opp", "opposite", "behind", "market", "extension", "extn", "park", "mohalla", "village", "chowk",
        "metro", "station", "school", "mandir", "temple", "masjid", "gurudwara", "delhi", "noida", "ghaziabad",
        "gurgaon", "gurugram", "faridabad", "bagh", "kunj", "khand", "puri", "ganj", "गली", "नगर", "मकान",
        "सेक्टर", "दिल्ली", "पास", "ब्लॉक", "फ्लैट",
    )

    private fun addressScore(text: String): Int {
        val words = text.split(Regex("""[\s,]+""")).map(::norm).filter { it.isNotEmpty() }
        return words.count { it in ADDRESS_WORDS } +
            (if (PINCODE.containsMatchIn(text)) 2 else 0) +
            (if (HOUSE_NUMBER.containsMatchIn(text)) 1 else 0)
    }

    private fun findAddress(rows: List<Row>): Field? {
        // 1. Labelled: "Drop address", "Delivery address", "पता"… with the lines that continue it.
        for (row in rows) {
            val match = row.label ?: continue
            val named = match.kind == Kind.Address || (match.kind == Kind.Name && match.section == Section.Customer)
            if (!named) continue
            val confidence = when {
                match.section == Section.Customer || row.section == Section.Customer -> Confidence.High
                row.section == null -> Confidence.Medium
                else -> continue // the pickup address
            }
            val parts = mutableListOf<String>()
            if (match.value.isNotEmpty()) {
                if (match.kind == Kind.Name && addressScore(match.value) == 0) continue // "Deliver to: Rahul"
                parts += match.value
            }
            var next = row.index + 1
            // Under a bare "Drop" heading the customer's name often comes first.
            if (parts.isEmpty() && rows.getOrNull(next)?.let { it.label == null && addressScore(it.text) == 0 && cleanName(it.text) != null } == true) next++
            parts += continuation(rows, next, first = parts.isEmpty())
            if (parts.isNotEmpty()) return Field(joinAddress(parts), confidence)
        }
        // 2. Unlabelled lines that read like an address, in the customer's section.
        val candidates = rows.filter { it.label == null && it.section != Section.Store && it.section != Section.Other && it.section != Section.Support }
        for (row in candidates) {
            if (addressScore(row.text) < 1 || hasNumber(row)) continue
            val parts = continuation(rows, row.index, first = true)
            val score = parts.sumOf(::addressScore)
            if (row.section == Section.Customer && score >= 2) return Field(joinAddress(parts), Confidence.Medium)
            if (row.section == null && score >= 3) return Field(joinAddress(parts), Confidence.Low)
        }
        return null
    }

    /** Up to four address lines from [from]: stops at a label, a phone number, a button or a line that isn't an address. */
    private fun continuation(rows: List<Row>, from: Int, first: Boolean): List<String> {
        val parts = mutableListOf<String>()
        for (row in rows.drop(from).take(4)) {
            if (row.label != null || hasNumber(row)) break
            val cells = row.cells.filterNot { norm(it.substringBefore(' ')) in UI_WORDS && addressScore(it) == 0 }
            if (cells.isEmpty()) break
            val text = cells.joinToString(" ")
            val continues = parts.lastOrNull()?.trimEnd()?.endsWith(',') == true
            if (addressScore(text) == 0 && !continues && !(first && parts.isEmpty() && text.contains(','))) break
            parts += text
        }
        return parts
    }

    private fun hasNumber(row: Row) = findNumbers(row).isNotEmpty()

    private fun joinAddress(parts: List<String>) =
        parts.joinToString(", ") { it.trim().trimEnd(',') }.replace(Regex("""\s+"""), " ").replace(" ,", ",")

    private val CITY_WORDS = words(
        "delhi", "new", "east", "west", "north", "south", "ncr", "india", "noida", "ghaziabad", "gurgaon", "gurugram",
        "faridabad", "up", "uttar", "pradesh", "haryana", "दिल्ली", "नई",
    )
    private val LOCALITY_WORDS = words(
        "nagar", "vihar", "enclave", "colony", "extension", "extn", "park", "bagh", "kunj", "khand", "puri", "ganj",
        "garden", "mandi", "नगर", "विहार",
    )
    private val LOCALITY_SUFFIXES = listOf("pur", "ganj", "abad", "पुर").map(::norm)
    private val LEADING_WORDS = words("near", "opp", "opposite", "behind", "beside", "next")

    /** "Flat 12, Near Laxmi Nagar Metro Station, Delhi 110092" → "Laxmi Nagar". */
    internal fun areaOf(address: String): String? {
        val segments = address.split(',')
            .map { PINCODE.replace(it, " ").trim().trimEnd('.') }
            .filter { segment -> segment.split(Regex("""\s+""")).map(::norm).any { it.isNotEmpty() && it !in CITY_WORDS } }
        for (segment in segments.asReversed()) {
            val words = segment.split(Regex("""\s+""")).filter { it.isNotBlank() }
            val last = words.indexOfLast { w -> norm(w).let { it in LOCALITY_WORDS || LOCALITY_SUFFIXES.any(it::endsWith) } }
            if (last < 0) continue
            val area = words.subList(0, last + 1).dropWhile { norm(it) in LEADING_WORDS }
            if (area.isNotEmpty() && area.none { it.any(Char::isDigit) }) return area.joinToString(" ")
        }
        return segments.lastOrNull()
            ?.takeIf { s -> s.none(Char::isDigit) && norm(s.substringBefore(' ')) !in ADDRESS_WORDS }
    }

    // ---- Order ID.

    private val ORDER_ID = Regex("""^#?([A-Za-z0-9][A-Za-z0-9\-/]{3,24})$""")

    private fun findOrderId(rows: List<Row>): Field? {
        for (row in rows) {
            val match = row.label?.takeIf { it.kind == Kind.OrderId } ?: continue
            val token = match.value.split(Regex("""\s+""")).firstOrNull().orEmpty().trimEnd(',', '.')
            val id = ORDER_ID.matchEntire(token)?.groupValues?.get(1) ?: continue
            if (id.count(Char::isDigit) < 2) continue
            val explicit = match.label.words.size >= 2 || token.startsWith("#")
            return Field(id, if (explicit) Confidence.High else Confidence.Medium)
        }
        return null
    }
}
