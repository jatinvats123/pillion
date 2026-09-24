package app.pillion.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// OCR text samples of delivery-app order screens. Names, numbers and brands are invented.
class OrderParserTest {

    private fun assertField(value: String, confidence: Confidence, field: Field?) {
        assertEquals(value, field?.value)
        assertEquals(confidence, field?.confidence)
    }

    @Test
    fun cleanLabelledScreen() {
        val order = OrderParser.parse(
            """
            Order ID: NK-40271
            Customer: Rahul Verma
            Phone: +91 98110 22334
            Drop address: Flat 302, Tower B, Shri Ram Apartments,
            Near Laxmi Nagar Metro Station,
            Vikas Marg, Delhi 110092
            Call   Navigate
            """.trimIndent()
        )
        assertField("Rahul Verma", Confidence.High, order.customerName)
        assertField("+919811022334", Confidence.High, order.customerPhone)
        assertField(
            "Flat 302, Tower B, Shri Ram Apartments, Near Laxmi Nagar Metro Station, Vikas Marg, Delhi 110092",
            Confidence.High,
            order.dropAddress,
        )
        assertEquals("Laxmi Nagar", order.dropArea)
        assertField("NK-40271", Confidence.High, order.orderId)
        assertNull(order.maskedPhone)
        assertFalse(order.callButtonOnly)
    }

    @Test
    fun pickupAndDropSectionsPickTheCustomersNumber() {
        val order = OrderParser.parse(
            """
            PICKUP
            Sharma Bhojnalaya
            Shop 7, Main Market, Shakarpur
            011-4567 8901
            DROP
            Priya Singh
            B-14, Pandav Nagar, Delhi 110092
            +91 99100 45678
            Call customer
            """.trimIndent()
        )
        assertField("Priya Singh", Confidence.Medium, order.customerName)
        assertField("+919910045678", Confidence.High, order.customerPhone)
        assertField("B-14, Pandav Nagar, Delhi 110092", Confidence.High, order.dropAddress)
        assertEquals("Pandav Nagar", order.dropArea)
        assertEquals(listOf("01145678901", "+919910045678"), order.phoneNumbers.map { it.dial })
        assertEquals(listOf(NumberRole.Store, NumberRole.Customer), order.phoneNumbers.map { it.role })
    }

    @Test
    fun restaurantNumberOnTheSameRowIsNotTheCustomers() {
        val order = OrderParser.parse(
            """
            Restaurant: Gupta Sweets
            Restaurant phone: 98990 12345
            Customer: Kavita Rao
            Mobile: 91234 56780
            Address: 45, Preet Vihar, Delhi 110092
            """.trimIndent()
        )
        assertField("+919123456780", Confidence.High, order.customerPhone)
        assertEquals(NumberRole.Store, order.phoneNumbers.first { it.dial == "+919899012345" }.role)
        assertField("Kavita Rao", Confidence.High, order.customerName)
    }

    @Test
    fun maskedNumberIsNeverGuessed() {
        val order = OrderParser.parse(
            """
            Order #58213904
            Deliver to
            Amit Kumar
            Mobile: 98XXX XX123
            Address: H.No 45, Gali No 3, Mandawali, Delhi 110092
            """.trimIndent()
        )
        assertNull(order.customerPhone)
        assertEquals("98XXX XX123", order.maskedPhone)
        assertTrue(order.phoneNumbers.isEmpty())
        assertField("Amit Kumar", Confidence.High, order.customerName)
        assertField("H.No 45, Gali No 3, Mandawali, Delhi 110092", Confidence.High, order.dropAddress)
        assertEquals("Mandawali", order.dropArea)
        assertField("58213904", Confidence.High, order.orderId)
        assertFalse(order.callButtonOnly)
    }

    @Test
    fun maskedNumberFormats() {
        listOf("+91 ******3210", "98•••••210", "XXXXXX3210", "(+91) 98XX-XXX-123").forEach { masked ->
            val order = OrderParser.parse("Customer: Amit Kumar\nPhone: $masked")
            assertNull(masked, order.customerPhone)
            assertEquals(masked, masked.trim('(', ')'), order.maskedPhone?.trim('(', ')'))
        }
    }

    @Test
    fun hinglishLabels() {
        val order = OrderParser.parse(
            """
            Grahak ka naam: Suresh Yadav
            Mobile no: 7011223344
            Pata: 23/4, Geeta Colony, Delhi 110031
            """.trimIndent()
        )
        assertField("Suresh Yadav", Confidence.High, order.customerName)
        assertField("+917011223344", Confidence.High, order.customerPhone)
        assertField("23/4, Geeta Colony, Delhi 110031", Confidence.High, order.dropAddress)
        assertEquals("Geeta Colony", order.dropArea)
    }

    @Test
    fun devanagariLabelsAndName() {
        val order = OrderParser.parse(
            """
            ग्राहक: सुनीता देवी
            फ़ोन: 8800 112 233
            पता: मकान 12, गली 4, शकरपुर, दिल्ली 110092
            """.trimIndent()
        )
        assertField("सुनीता देवी", Confidence.High, order.customerName)
        assertField("+918800112233", Confidence.High, order.customerPhone)
        assertField("मकान 12, गली 4, शकरपुर, दिल्ली 110092", Confidence.High, order.dropAddress)
        assertEquals("शकरपुर", order.dropArea)
    }

    @Test
    fun devanagariNameUnderEnglishLabel() {
        val order = OrderParser.parse("Customer: राहुल वर्मा\nPhone: 9811022334\nAddress: 12, Laxmi Nagar, Delhi 110092")
        assertField("राहुल वर्मा", Confidence.High, order.customerName)
    }

    @Test
    fun noisyOcrLabelsStillMatch() {
        val order = OrderParser.parse(
            """
            0rder lD: NK-7781
            Custorner Narne : Rohit Sharma
            Ph0ne: 98110 22334
            Dr0p Addres5: C-22, Laxmi Nagar, Delhi 110092
            """.trimIndent()
        )
        assertField("NK-7781", Confidence.High, order.orderId)
        assertField("Rohit Sharma", Confidence.High, order.customerName)
        assertField("+919811022334", Confidence.High, order.customerPhone)
        assertField("C-22, Laxmi Nagar, Delhi 110092", Confidence.High, order.dropAddress)
    }

    @Test
    fun letterForDigitMisreadsAreFixedButFlagged() {
        val order = OrderParser.parse("Customer: Rohit Sharma\nPhone: 98l10 O2234")
        assertField("+919811002234", Confidence.Medium, order.customerPhone)
        assertTrue(order.phoneNumbers.single().ocrFixed)
    }

    @Test
    fun indianNumberFormats() {
        mapOf(
            "+91 98110 22334" to "+919811022334",
            "+91-9811022334" to "+919811022334",
            "(+91) 98110 22334" to "+919811022334",
            "91 98110 22334" to "+919811022334",
            "09811022334" to "+919811022334",
            "098110 22334" to "+919811022334",
            "98110-22334" to "+919811022334",
            "9811 022 334" to "+919811022334",
            "+91 98 11 02 23 34" to "+919811022334",
            "011 4567 8901" to "01145678901",
            "+91 11 4567 8901" to "01145678901",
        ).forEach { (written, dial) ->
            val order = OrderParser.parse("Customer: Rahul Verma\nPhone: $written")
            assertEquals(written, listOf(dial), order.phoneNumbers.map { it.dial })
        }
    }

    @Test
    fun notPhoneNumbers() {
        val order = OrderParser.parse(
            """
            Order ID: 9876543210
            OTP: 4521
            Distance 6.9 km · 10:45 AM
            Bill total ₹ 348
            Delhi 110092
            12345 67890
            98110 2233
            24-09-2026
            """.trimIndent()
        )
        assertTrue(order.phoneNumbers.map { it.dial }.toString(), order.phoneNumbers.isEmpty())
        assertField("9876543210", Confidence.High, order.orderId)
    }

    @Test
    fun amountNextToNumberDoesNotJoinIt() {
        val order = OrderParser.parse("Customer: Rahul Verma\nPay 48 9811022334")
        assertEquals(listOf("+919811022334"), order.phoneNumbers.map { it.dial })
    }

    @Test
    fun twoUnlabelledNumbersLetTheRiderChoose() {
        val order = OrderParser.parse(
            """
            Anjali Mehta
            98100 11223
            99990 88776
            """.trimIndent()
        )
        assertNull(order.customerPhone)
        assertEquals(listOf("+919810011223", "+919999088776"), order.phoneNumbers.map { it.dial })
        assertTrue(order.phoneNumbers.all { it.role == NumberRole.Unknown })
    }

    @Test
    fun twoCustomerNumbersAreAmbiguous() {
        val order = OrderParser.parse("Customer: Anjali Mehta\nPhone: 98100 11223\nAlternate: 99990 88776")
        assertNull(order.customerPhone)
        assertEquals(2, order.phoneNumbers.size)
    }

    @Test
    fun singleUnlabelledNumberIsOnlyMedium() {
        val order = OrderParser.parse(
            """
            Anjali Mehta
            98100 11223
            12, Preet Vihar, Delhi 110092
            """.trimIndent()
        )
        assertField("+919810011223", Confidence.Medium, order.customerPhone)
        // Nothing marks these as the customer's name or the drop: not pre-filled.
        assertNull(order.customerName)
        assertField("12, Preet Vihar, Delhi 110092", Confidence.Low, order.dropAddress)
    }

    @Test
    fun missingAddressStaysEmpty() {
        val order = OrderParser.parse(
            """
            Customer: Neha Gupta
            Phone: 9312 345 678
            Status: Picked up
            """.trimIndent()
        )
        assertNull(order.dropAddress)
        assertNull(order.dropArea)
        assertField("Neha Gupta", Confidence.High, order.customerName)
    }

    @Test
    fun callButtonWithoutNumber() {
        val order = OrderParser.parse(
            """
            Customer details
            Vikas Jain
            Call
            Chat
            Delivery address: Plot 9, Krishna Nagar, Delhi 110051
            """.trimIndent()
        )
        assertField("Vikas Jain", Confidence.High, order.customerName)
        assertNull(order.customerPhone)
        assertNull(order.maskedPhone)
        assertTrue(order.callButtonOnly)
        assertField("Plot 9, Krishna Nagar, Delhi 110051", Confidence.High, order.dropAddress)
        assertEquals("Krishna Nagar", order.dropArea)
    }

    @Test
    fun supportNumberIsNotTheCustomers() {
        val order = OrderParser.parse(
            """
            Customer: Rahul Verma
            Customer care: 1800 123 4567
            Address: 7, Ganesh Nagar, Delhi 110092
            """.trimIndent()
        )
        assertNull(order.customerPhone)
        assertEquals(NumberRole.Support, order.phoneNumbers.single().role)
        // The one-line support label doesn't swallow the address after it.
        assertField("7, Ganesh Nagar, Delhi 110092", Confidence.High, order.dropAddress)
    }

    @Test
    fun ridersOwnNumberIsNotTheCustomers() {
        val order = OrderParser.parse(
            """
            Delivery partner: Ramesh (you) 9876501234
            Customer: Kavita Rao
            Phone: 9123456780
            """.trimIndent()
        )
        assertField("+919123456780", Confidence.High, order.customerPhone)
        assertEquals(NumberRole.Other, order.phoneNumbers.first().role)
    }

    @Test
    fun twoColumnLayoutFromBoxes() {
        // Labels on the left, values on the right, handed over out of order (as OCR blocks can be).
        val lines = listOf(
            OcrLine("Delhi 110092", 600, 572, 820, 612),
            OcrLine("Rahul Verma", 600, 402, 900, 442),
            OcrLine("Customer", 40, 400, 260, 440),
            OcrLine("+91 98110 22334", 600, 462, 980, 502),
            OcrLine("Mobile", 40, 460, 200, 500),
            OcrLine("Address", 40, 520, 220, 560),
            OcrLine("Flat 12, Laxmi Nagar,", 600, 522, 1000, 562),
        )
        val order = OrderParser.parse(lines)
        assertField("Rahul Verma", Confidence.High, order.customerName)
        assertField("+919811022334", Confidence.High, order.customerPhone)
        assertField("Flat 12, Laxmi Nagar, Delhi 110092", Confidence.High, order.dropAddress)
    }

    @Test
    fun nameWithRatingAndCallButtonOnItsRow() {
        val lines = listOf(
            OcrLine("Customer", 40, 400, 260, 440),
            OcrLine("Rahul Verma ★4.8", 300, 402, 700, 442),
            OcrLine("Call", 900, 400, 1000, 440),
        )
        assertField("Rahul Verma", Confidence.High, OrderParser.parse(lines).customerName)
    }

    @Test
    fun clutteredScreen() {
        val order = OrderParser.parse(
            """
            10:42 ▼ 4G 76%
            ← Order #TH-99120
            Help
            Preparing · Ready in 5 mins
            Earn ₹52 on this order
            PICKUP FROM
            Kulhad Corner
            Shop 3, Vikas Marg, Shakarpur, Delhi
            Restaurant: 011 2244 6688
            Items (3)
            Masala Chai x2  ₹60
            Samosa x4  ₹80
            DELIVER TO
            Mohit Arora
            Flat 5B, Sunshine Heights,
            Mayur Vihar Phase 1, Delhi 110091
            Landmark: Opp. Community Hall
            +91 88260 77441
            Call   Chat   Navigate
            Slide to mark picked up
            """.trimIndent()
        )
        assertField("Mohit Arora", Confidence.High, order.customerName)
        assertField("+918826077441", Confidence.High, order.customerPhone)
        assertField("Flat 5B, Sunshine Heights, Mayur Vihar Phase 1, Delhi 110091", Confidence.High, order.dropAddress)
        assertEquals("Mayur Vihar", order.dropArea)
        assertField("TH-99120", Confidence.High, order.orderId)
        assertEquals(NumberRole.Store, order.phoneNumbers.first { it.dial == "01122446688" }.role)
    }

    @Test
    fun promoTextIsNotALabel() {
        val order = OrderParser.parse("Customers love fast delivery\nCustomer Rahul Verma")
        assertNull(order.customerName)
    }

    @Test
    fun notAnOrderScreen() {
        val order = OrderParser.parse("Good morning\nYour weekly summary\n42 trips this week")
        assertTrue(order.isEmpty)
    }

    @Test
    fun areaFromAddress() {
        assertEquals("Laxmi Nagar", OrderParser.areaOf("Near Laxmi Nagar Metro Station, Delhi"))
        assertEquals("Patparganj", OrderParser.areaOf("Pocket C, Patparganj, East Delhi 110092"))
        assertEquals("IP Extension", OrderParser.areaOf("12, IP Extension, Delhi 110092"))
        assertNull(OrderParser.areaOf("Flat 12, Delhi 110092"))
    }
}
