package org.fossify.calendar.caldav

import org.w3c.dom.Element

data class DavResource(
    val url: String,
    val etag: String?,
    val ical: String,
)

class DavCalendarReader(private val client: DavHttpClient) {

    fun listEvents(calendarUrl: String, since: String? = null): List<DavResource> =
        report(calendarUrl, "VEVENT", since)

    fun listTasks(calendarUrl: String, since: String? = null): List<DavResource> =
        report(calendarUrl, "VTODO", since)

    private fun report(calendarUrl: String, component: String, since: String?): List<DavResource> {
        val timeRangeFilter = since?.let {
            """<cal:time-range start="$it"/>"""
        }.orEmpty()
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <cal:calendar-query xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
                <d:prop>
                    <d:getetag/>
                </d:prop>
                <cal:filter>
                    <cal:comp-filter name="VCALENDAR">
                        <cal:comp-filter name="$component">
                            $timeRangeFilter
                        </cal:comp-filter>
                    </cal:comp-filter>
                </cal:filter>
            </cal:calendar-query>
        """.trimIndent()
        val response = client.request("REPORT", calendarUrl, depth = 1, body = body)
        if (response.status !in 200..299) {
            throw DavException("REPORT $calendarUrl failed with ${response.status}")
        }
        val document = DavDiscovery.parseXml(response.body)
        val responses = document.getElementsByTagNameNS(DavDiscovery.DAV_NS, "response")
        val resources = mutableListOf<DavResource>()
        for (i in 0 until responses.length) {
            val element = responses.item(i) as Element
            val href = (element.getElementsByTagNameNS(DavDiscovery.DAV_NS, "href")
                .item(0) as? Element)?.textContent ?: continue
            val etag = (element.getElementsByTagNameNS(DavDiscovery.DAV_NS, "getetag")
                .item(0) as? Element)?.textContent
            val calendarData = (element.getElementsByTagNameNS(DavDiscovery.CALDAV_NS, "calendar-data")
                .item(0) as? Element)?.textContent
            if (calendarData.isNullOrBlank()) {
                continue
            }
            resources.add(
                DavResource(
                    url = DavDiscovery.resolveUrl(calendarUrl, href),
                    etag = etag,
                    ical = calendarData,
                ),
            )
        }
        return resources
    }

    fun getEventEtags(calendarUrl: String): Map<String, String> {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <cal:calendar-query xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
                <d:prop>
                    <d:getetag/>
                </d:prop>
                <cal:filter>
                    <cal:comp-filter name="VCALENDAR">
                        <cal:comp-filter name="VEVENT"/>
                    </cal:comp-filter>
                </cal:filter>
            </cal:calendar-query>
        """.trimIndent()
        val response = client.request("REPORT", calendarUrl, depth = 1, body = body)
        if (response.status !in 200..299) {
            throw DavException("REPORT $calendarUrl failed with ${response.status}")
        }
        val document = DavDiscovery.parseXml(response.body)
        val responses = document.getElementsByTagNameNS(DavDiscovery.DAV_NS, "response")
        val etags = mutableMapOf<String, String>()
        for (i in 0 until responses.length) {
            val element = responses.item(i) as Element
            val href = (element.getElementsByTagNameNS(DavDiscovery.DAV_NS, "href")
                .item(0) as? Element)?.textContent ?: continue
            val etag = (element.getElementsByTagNameNS(DavDiscovery.DAV_NS, "getetag")
                .item(0) as? Element)?.textContent ?: continue
            etags[DavDiscovery.resolveUrl(calendarUrl, href)] = etag
        }
        return etags
    }
}
