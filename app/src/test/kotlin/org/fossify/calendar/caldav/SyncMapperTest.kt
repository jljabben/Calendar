package org.fossify.calendar.caldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMapperTest {

    @Test
    fun `maps VEVENT resource with all-day values`() {
        val resource = DavResource(
            url = "https://dav.example.com/cal/event-1.ics",
            etag = "\"etag-1\"",
            ical = "BEGIN:VCALENDAR\r\n" +
                "BEGIN:VEVENT\r\n" +
                "UID:event-1\r\n" +
                "SUMMARY:Standup\r\n" +
                "DTSTART;VALUE=DATE:20260102\r\n" +
                "DTEND;VALUE=DATE:20260103\r\n" +
                "DESCRIPTION:Daily sync\r\n" +
                "LOCATION:Room 4\r\n" +
                "RRULE:FREQ=DAILY\r\n" +
                "END:VEVENT\r\n" +
                "END:VCALENDAR",
        )
        val item = SyncMapper.toSyncedItem(resource)!!
        assertEquals("event-1", item.uid)
        assertFalse(item.isTask)
        assertTrue(item.allDay)
        assertEquals("Standup", item.title)
        assertEquals("Daily sync", item.description)
        assertEquals("Room 4", item.location)
        assertEquals("FREQ=DAILY", item.recurrenceRule)
        assertEquals("\"etag-1\"", item.etag)
    }

    @Test
    fun `maps VTODO resource with completion state`() {
        val resource = DavResource(
            url = "https://dav.example.com/cal/task-1.ics",
            etag = "\"etag-2\"",
            ical = "BEGIN:VCALENDAR\r\n" +
                "BEGIN:VTODO\r\n" +
                "UID:task-1\r\n" +
                "SUMMARY:Write tests\r\n" +
                "DUE:20260105T170000Z\r\n" +
                "STATUS:COMPLETED\r\n" +
                "PERCENT-COMPLETE:100\r\n" +
                "END:VTODO\r\n" +
                "END:VCALENDAR",
        )
        val item = SyncMapper.toSyncedItem(resource)!!
        assertTrue(item.isTask)
        assertTrue(item.completed)
        assertEquals(100, item.percentComplete)
        assertTrue(item.endTimestamp != null)
    }

    @Test
    fun `skips resources without UID`() {
        val resource = DavResource(
            url = "https://dav.example.com/cal/broken.ics",
            etag = null,
            ical = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nSUMMARY:no uid\r\nEND:VEVENT\r\nEND:VCALENDAR",
        )
        assertNull(SyncMapper.toSyncedItem(resource))
    }
}
