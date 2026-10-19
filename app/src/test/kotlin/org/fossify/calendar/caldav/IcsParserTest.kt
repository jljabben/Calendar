package org.fossify.calendar.caldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsParserTest {

    @Test
    fun `parses simple VEVENT with basic properties`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:abc-123
            DTSTART:20260101T090000Z
            DTEND:20260101T100000Z
            SUMMARY:Team meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")
        val event = IcsParser.findComponent(ics, "VEVENT")!!
        assertEquals("abc-123", event.property("UID"))
        assertEquals("Team meeting", event.property("SUMMARY"))
        assertEquals(1767258000L, IcsParser.parseUtcTimestamp(event.property("DTSTART")!!))
    }

    @Test
    fun `unfolds continuation lines`() {
        val ics = "BEGIN:VEVENT\r\nSUMMARY:First part\r\n  continued part\r\nEND:VEVENT"
        val unfolded = IcsParser.unfold(ics)
        assertTrue(unfolded.any { it == "SUMMARY:First part continued part" })
    }

    @Test
    fun `parses VTODO with completion status`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VTODO
            UID:task-1
            SUMMARY:Buy milk
            STATUS:COMPLETED
            PERCENT-COMPLETE:100
            END:VTODO
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")
        val task = IcsParser.findComponent(ics, "VTODO")!!
        assertEquals("task-1", task.property("UID"))
        assertEquals("COMPLETED", task.property("STATUS"))
        assertEquals("100", task.property("PERCENT-COMPLETE"))
    }

    @Test
    fun `handles escaped characters in summary`() {
        val ics = "BEGIN:VEVENT\r\nSUMMARY:Dinner with Joe\\, and friends\r\nEND:VEVENT"
        val event = IcsParser.findComponent(ics, "VEVENT")!!
        assertEquals("Dinner with Joe, and friends", event.property("SUMMARY"))
    }

    @Test
    fun `parses all-day date value`() {
        assertEquals(
            1767225600L,
            IcsParser.parseDate("20260101"),
        )
    }

    @Test
    fun `returns null for invalid timestamp`() {
        assertNull(IcsParser.parseUtcTimestamp("not-a-date"))
        assertNull(IcsParser.parseUtcTimestamp("20260101"))
    }

    @Test
    fun `nested components do not leak into parent properties`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:with-alarm
            BEGIN:VALARM
            ACTION:DISPLAY
            END:VALARM
            SUMMARY:Has alarm
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")
        val event = IcsParser.findComponent(ics, "VEVENT")!!
        assertEquals("with-alarm", event.property("UID"))
        assertEquals("Has alarm", event.property("SUMMARY"))
        assertFalse(event.properties.containsKey("ACTION"))
    }
}
