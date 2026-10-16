package app.guardianmode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneNumbersTest {
    @Test fun `formats of the same US line match`() {
        assertTrue(PhoneNumbers.sameLine("+1 (555) 123-4567", "5551234567"))
        assertTrue(PhoneNumbers.sameLine("15551234567", "555.123.4567"))
    }

    @Test fun `different lines and short codes do not match loosely`() {
        assertFalse(PhoneNumbers.sameLine("5551234567", "5551234568"))
        assertFalse(PhoneNumbers.sameLine("", ""))
        assertFalse(PhoneNumbers.sameLine("12", "12"))
        assertTrue(PhoneNumbers.sameLine("911", "911"))
    }

    @Test fun `mask keeps only last four digits`() {
        assertEquals("•••-•••-4567", PhoneNumbers.mask("+1 555 123 4567"))
        assertEquals("Hidden number", PhoneNumbers.mask(null))
        assertEquals("•••", PhoneNumbers.mask("123"))
    }

    @Test fun `pretty prints US numbers`() {
        assertEquals("(555) 123-4567", PhoneNumbers.pretty("+15551234567"))
        assertEquals("+447700900123", PhoneNumbers.pretty("+44 7700 900123"))
    }
}
