package org.fossify.calendar.caldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DavCalendarReaderTest {

    private val reportResponse = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
          <d:response>
            <d:href>/user/calendars/personal/event-1.ics</d:href>
            <d:propstat>
              <d:prop>
                <d:getetag>"etag-1"</d:getetag>
                <cal:calendar-data>BEGIN:VCALENDAR
BEGIN:VEVENT
UID:event-1
SUMMARY:Standup
END:VEVENT
END:VCALENDAR</cal:calendar-data>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/user/calendars/personal/task-1.ics</d:href>
            <d:propstat>
              <d:prop>
                <d:getetag>"etag-2"</d:getetag>
                <cal:calendar-data>BEGIN:VCALENDAR
BEGIN:VTODO
UID:task-1
SUMMARY:Write tests
END:VTODO
END:VCALENDAR</cal:calendar-data>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun `parses resources with etag and ical data from REPORT response`() {
        val reader = DavCalendarReader(FakeClient(reportResponse))
        val resources = reader.listEvents("https://dav.example.com/user/calendars/personal/")
        assertEquals(2, resources.size)
        val event = resources.first { IcsParser.findComponent(it.ical, "VEVENT") != null }
        assertEquals("\"etag-1\"", event.etag)
        assertEquals(
            "https://dav.example.com/user/calendars/personal/event-1.ics",
            event.url,
        )
        assertTrue(IcsParser.findComponent(event.ical, "VEVENT")!!.property("SUMMARY") == "Standup")
    }

    private class FakeClient(private val responseBody: String) : DavHttpClient {
        override fun request(
            method: String,
            url: String,
            depth: Int?,
            headers: Map<String, String>,
            body: String?,
        ): DavResponse = DavResponse(207, emptyMap(), responseBody)
    }
}
