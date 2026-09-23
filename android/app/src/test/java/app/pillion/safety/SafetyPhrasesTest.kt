package app.pillion.safety

import org.junit.Assert.assertEquals
import org.junit.Test

class SafetyPhrasesTest {

    private fun assertReply(expected: Reply, vararg transcripts: String) = transcripts.forEach {
        assertEquals("\"$it\"", expected, SafetyPhrases.classify(it))
    }

    @Test
    fun `rider says they are fine`() = assertReply(
        Reply.Cancel,
        "Main theek hoon", "main thik hu yaar", "haan haan, theek hoon", "Sab theek hai, cancel karo",
        "मैं ठीक हूँ", "मैं ठीक हूं।", "हाँ, सब ठीक है", "कोई बात नहीं", "कैंसल करो", "कुछ नहीं हुआ",
        "I'm fine", "I am okay, cancel it", "I'm OK.", "false alarm", "Cancel", "No problem, all good",
        "नहीं नहीं मैं ठीक हूँ",
    )

    @Test
    fun `rider asks for help`() = assertReply(
        Reply.Help,
        "help", "Help me please", "madad karo", "bachao", "ambulance bulao", "SOS",
        "मदद", "बचाओ", "मदद करो", "एम्बुलेंस", "हॉस्पिटल ले चलो", "चोट लगी है",
        "Emergency!", "call 112",
    )

    @Test
    fun `negated OK means help, negated help means OK`() {
        assertReply(Reply.Help, "main theek nahi hoon", "मैं ठीक नहीं हूँ", "I'm not okay", "I am not fine", "cancel mat karo", "don't cancel", "कैंसल मत करो")
        assertReply(Reply.Cancel, "I don't need help", "madad nahi chahiye", "मदद नहीं चाहिए", "SOS mat bhejo", "मत भेजो")
    }

    @Test
    fun `anything unclear keeps the countdown running`() = assertReply(
        Reply.Unclear,
        "", "haan", "theek hai", "ठीक है", "okay", "hello?", "kya hua", "क्या", "uh", "what", "stop", "आह",
    )

    @Test
    fun `Pillion's own countdown lines can't cancel or send the alert`() {
        listOf(SafetyLine.ARE_YOU_OK, SafetyLine.ARE_YOU_OK_AGAIN, SafetyLine.MANUAL_COUNTDOWN).forEach { line ->
            RiderLanguage.entries.forEach { language ->
                assertEquals(line.forLanguage(language), Reply.Unclear, SafetyPhrases.classify(line.forLanguage(language)))
            }
        }
    }

    @Test
    fun `language of the rider`() {
        assertEquals(RiderLanguage.Hindi, SafetyPhrases.languageOf("आज कितना कमाया?"))
        assertEquals(RiderLanguage.Hindi, SafetyPhrases.languageOf("aaj kitna kamaya bhai"))
        assertEquals(RiderLanguage.English, SafetyPhrases.languageOf("How far is the next drop?"))
        assertEquals(RiderLanguage.Unknown, SafetyPhrases.languageOf("...  "))
    }

    @Test
    fun `normalize folds spelling variants`() {
        assertEquals(SafetyPhrases.normalize("ठीक हूं"), SafetyPhrases.normalize("ठीक हूँ।"))
        assertEquals("im fine", SafetyPhrases.normalize("I’m fine!!"))
    }
}
