package app.pillion.order

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneMaskTest {

    @Test
    fun mobileShowsFirstFiveDigits() = assertEquals("98113 •••••", maskPhone("9811300000"))

    @Test
    fun countryCodeAndSpacesAreDropped() {
        assertEquals("98113 •••••", maskPhone("+91 98113 00000"))
        assertEquals("98113 •••••", maskPhone("91-9811300000"))
        assertEquals("98113 •••••", maskPhone("09811300000"))
    }

    @Test
    fun tollFreeKeepsItsLength() = assertEquals("18001 ••••••", maskPhone("1800 123 4567"))

    @Test
    fun shortOrEmptyShowsNoDigits() {
        assertEquals("•••", maskPhone("112"))
        assertEquals("", maskPhone(""))
    }

    @Test
    fun spokenPartIsTheVisibleDigits() = assertEquals("98113", maskedPhoneDigits("+919811300000"))
}
