package app.pillion.order

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneMaskTest {

    @Test
    fun mobileShowsFirstFiveDigits() = assertEquals("98113 •••••", maskPhone("9811345010"))

    @Test
    fun countryCodeAndSpacesAreDropped() {
        assertEquals("98113 •••••", maskPhone("+91 98113 45010"))
        assertEquals("98113 •••••", maskPhone("91-9811345010"))
        assertEquals("98113 •••••", maskPhone("09811345010"))
    }

    @Test
    fun tollFreeKeepsItsLength() = assertEquals("18001 ••••••", maskPhone("1800 123 4567"))

    @Test
    fun shortOrEmptyShowsNoDigits() {
        assertEquals("•••", maskPhone("112"))
        assertEquals("", maskPhone(""))
    }

    @Test
    fun spokenPartIsTheVisibleDigits() = assertEquals("98113", maskedPhoneDigits("+919811345010"))
}
