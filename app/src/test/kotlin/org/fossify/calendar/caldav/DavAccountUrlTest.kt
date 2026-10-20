package org.fossify.calendar.caldav

import org.junit.Assert.assertEquals
import org.junit.Test

class DavAccountUrlTest {

    @Test
    fun `keeps https urls`() {
        assertEquals(
            "https://cloud.example.com",
            DavAccountUrl.normalize("https://cloud.example.com"),
        )
    }

    @Test
    fun `keeps http urls`() {
        assertEquals(
            "http://cloud.example.com/remote.php/dav",
            DavAccountUrl.normalize("http://cloud.example.com/remote.php/dav/"),
        )
    }

    @Test
    fun `adds https to bare hostnames`() {
        assertEquals(
            "https://cloud.example.com",
            DavAccountUrl.normalize("cloud.example.com"),
        )
    }

    @Test
    fun `converts caldav scheme to http`() {
        assertEquals(
            "http://cloud.example.com",
            DavAccountUrl.normalize("caldav://cloud.example.com"),
        )
    }

    @Test
    fun `trims whitespace`() {
        assertEquals(
            "https://cloud.example.com",
            DavAccountUrl.normalize("  https://cloud.example.com  "),
        )
    }
}
