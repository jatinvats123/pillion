package app.pillion.order

/**
 * How a customer's number shows on screen: "98113 •••••", enough to recognise it, not enough to
 * read it off a mounted phone. SMS and calls still use the full number. +91 / 91 / 0 prefixes are
 * dropped first so every mobile shows the same way.
 */
fun maskPhone(number: String): String {
    var digits = number.filter(Char::isDigit)
    if (digits.length == 12 && digits.startsWith("91")) digits = digits.drop(2)
    if (digits.length == 11 && digits.startsWith("0")) digits = digits.drop(1)
    if (digits.length <= VISIBLE) return "•".repeat(digits.length)
    return digits.take(VISIBLE) + " " + "•".repeat(digits.length - VISIBLE)
}

/** What TalkBack says instead of the dots. */
fun maskedPhoneDigits(number: String): String = maskPhone(number).substringBefore(' ')

private const val VISIBLE = 5
