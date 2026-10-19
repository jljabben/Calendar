package org.fossify.calendar.caldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class IcsSyncMapperTest {

    private fun resource(uid: String, etag: String, body: String): RemoteResource =
        RemoteResource(
            url = URL("https://dav.example.com/cal/$uid.ics"),
            etag = etag,
            ical = "BEGIN:VCALENDAR\r\n" +
                "VERSION:2.0\r\n" +
                "PRODID:-//Test//EN\r\n" +
                body.trimIndent().replace("\n", "\r\n") +
                "\r\nEND:VCALENDAR",
        )

    @Test
    fun `maps VEVENT with utc timestamps`() {
        val r = resource(
            "event-1", "\"e1\"",
            """
            BEGIN:VEVENT
            UID:event-1
            SUMMARY:Standup
            DESCRIPTION:Daily sync
            LOCATION:Room 4
            DTSTART:20260101T090000Z
            DTEND:20260101T100000Z
            RRULE:FREQ=DAILY
            END:VEVENT
            """,
        )
        val item = IcsSyncMapper.toSyncedItem(r)!!
        assertEquals("event-1", item.uid)
        assertTrue(!item.isTask)
        assertTrue(!item.allDay)
        assertEquals("Standup", item.title)
        assertEquals("Daily sync", item.description)
        assertEquals("Room 4", item.location)
        assertEquals("FREQ=DAILY", item.recurrenceRule)
        assertEquals(1767258000L, item.startTimestamp)
        assertEquals(1767261600L, item.endTimestamp)
    }

    @Test
    fun `maps all-day VEVENT via VALUE=DATE`() {
        val r = resource(
            "event-2", "\"e2\"",
            """
            BEGIN:VEVENT
            UID:event-2
            SUMMARY:Conference
            DTSTART;VALUE=DATE:20260102
            DTEND;VALUE=DATE:20260103
            END:VEVENT
            """,
        )
        val item = IcsSyncMapper.toSyncedItem(r)!!
        assertTrue(item.allDay)
        assertEquals(1767225600L, item.startTimestamp)
    }

    @Test
    fun `maps VTODO with completion state and due date`() {
        val r = resource(
            "task-1", "\"t1\"",
            """
            BEGIN:VTODO
            UID:task-1
            SUMMARY:Write tests
            DUE:20260105T170000Z
            STATUS:COMPLETED
            PERCENT-COMPLETE:100
            END:VTODO
            """,
        )
        val item = IcsSyncMapper.toSyncedItem(r)!!
        assertTrue(item.isTask)
        assertTrue(item.completed)
        assertEquals(100, item.percentComplete)
        assertTrue(item.endTimestamp != null)
        assertEquals("Write tests", item.title)
    }

    @Test
    fun `maps open VTODO as not completed`() {
        val r = resource(
            "task-2", "\"t2\"",
            """
            BEGIN:VTODO
            UID:task-2
            SUMMARY:Open task
            STATUS:NEEDS-ACTION
            PERCENT-COMPLETE:20
            END:VTODO
            """,
        )
        val item = IcsSyncMapper.toSyncedItem(r)!!
        assertTrue(item.isTask)
        assertTrue(!item.completed)
        assertEquals(20, item.percentComplete)
    }

    @Test
    fun `skips resources without UID`() {
        val r = resource(
            "broken", "\"b\"",
            """
            BEGIN:VEVENT
            SUMMARY:no uid
            END:VEVENT
            """,
        )
        assertNull(IcsSyncMapper.toSyncedItem(r))
    }

    @Test
    fun `returns null for unparseable ical`() {
        val r = RemoteResource(URL("https://dav.example.com/cal/x.ics"), "\"x\"", "not ical at all")
        assertNull(IcsSyncMapper.toSyncedItem(r))
    }

    @Test
    fun `parses color values with and without alpha`() {
        assertEquals(0xFF3788D8.toInt(), ColorParser.toArgb("#3788D8FF"))
        assertEquals(0xFF3788D8.toInt(), ColorParser.toArgb("#3788D8"))
        assertNull(ColorParser.toArgb("#zzzzzz"))
        assertNull(ColorParser.toArgb(""))
    }
}
