package org.fossify.calendar.caldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DavDiscoveryTest {

    private val propfindResponse = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav"
                xmlns:x1="http://apple.com/ns/ical/">
          <d:response>
            <d:href>/user/calendars/</d:href>
            <d:propstat>
              <d:prop>
                <d:resourcetype>
                  <d:collection/>
                </d:resourcetype>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/user/calendars/personal/</d:href>
            <d:propstat>
              <d:prop>
                <d:resourcetype>
                  <d:collection/>
                  <cal:calendar/>
                </d:resourcetype>
                <d:displayname>Personal</d:displayname>
                <x1:calendar-color>#3788D8FF</x1:calendar-color>
                <cal:supported-calendar-component-set>
                  <cal:comp name="VEVENT"/>
                </cal:supported-calendar-component-set>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/user/calendars/tasks/</d:href>
            <d:propstat>
              <d:prop>
                <d:resourcetype>
                  <d:collection/>
                  <cal:calendar/>
                </d:resourcetype>
                <d:displayname>Tasks</d:displayname>
                <cal:supported-calendar-component-set>
                  <cal:comp name="VTODO"/>
                </cal:supported-calendar-component-set>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun `parses calendars from multistatus response`() {
        val fakeClient = FakeDavClient(propfindResponse)
        val discovery = DavDiscovery(fakeClient)
        val calendars = discovery.listCalendars("https://dav.example.com/user/calendars/")
        assertEquals(2, calendars.size)
        val personal = calendars.first { it.displayName == "Personal" }
        assertTrue(personal.supportsEvents)
        assertTrue(!personal.supportsTasks)
        val tasks = calendars.first { it.displayName == "Tasks" }
        assertTrue(tasks.supportsTasks)
        assertTrue(!tasks.supportsEvents)
    }

    @Test
    fun `parses apple calendar color with alpha`() {
        val color = DavDiscovery.parseColor("#3788D8FF")
        assertEquals(0xFF3788D8.toInt(), color)
    }

    @Test
    fun `resolves absolute and relative hrefs against base url`() {
        val base = "https://dav.example.com/user/calendars/"
        assertEquals(
            "https://dav.example.com/user/calendars/personal/",
            DavDiscovery.resolveUrl(base, "/user/calendars/personal/"),
        )
        assertEquals(
            "https://other.example.com/cal/",
            DavDiscovery.resolveUrl(base, "https://other.example.com/cal/"),
        )
    }

    @Test
    fun `rejects invalid color values`() {
        assertEquals(null, DavDiscovery.parseColor("#zzzzzz"))
        assertEquals(null, DavDiscovery.parseColor(""))
    }

    private class FakeDavClient(private val responseBody: String) : DavHttpClient {
        override fun request(
            method: String,
            url: String,
            depth: Int?,
            headers: Map<String, String>,
            body: String?,
        ): DavResponse = DavResponse(207, mapOf("Content-Type" to listOf("application/xml")), responseBody)
    }
}
