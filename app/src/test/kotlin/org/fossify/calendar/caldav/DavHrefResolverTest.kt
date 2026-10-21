package org.fossify.calendar.caldav

import io.ktor.http.Url
import org.junit.Assert.assertEquals
import org.junit.Test

class DavHrefResolverTest {

    private val base = Url("https://caldav.soverin.net/")

    @Test
    fun `resolves absolute path against base host`() {
        val resolved = DavHrefResolver.resolve(base, "/caldav/user@example.com/")
        assertEquals(
            "https://caldav.soverin.net/caldav/user@example.com/",
            resolved.toString(),
        )
    }

    @Test
    fun `keeps absolute urls untouched`() {
        val resolved = DavHrefResolver.resolve(
            base,
            "https://other.example.com/principal/",
        )
        assertEquals("https://other.example.com/principal/", resolved.toString())
    }

    @Test
    fun `resolves relative path against base path`() {
        val baseWithPath = Url("https://caldav.soverin.net/caldav/")
        val resolved = DavHrefResolver.resolve(baseWithPath, "user@example.com/")
        assertEquals(
            "https://caldav.soverin.net/caldav/user@example.com/",
            resolved.toString(),
        )
    }

    @Test
    fun `keeps scheme and port from base url`() {
        val baseWithPort = Url("http://192.168.1.10:5232/")
        val resolved = DavHrefResolver.resolve(baseWithPort, "/user/calendar/")
        assertEquals(
            "http://192.168.1.10:5232/user/calendar/",
            resolved.toString(),
        )
    }
}
