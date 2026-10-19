package org.fossify.calendar.caldav

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URL
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class DavCalendar(
    val url: String,
    val displayName: String,
    val colorRgb: Int?,
    val supportsEvents: Boolean,
    val supportsTasks: Boolean,
)

data class DavPrincipal(
    val principalUrl: String,
    val calendarHomeSet: String?,
)

class DavDiscovery(private val client: DavHttpClient) {

    fun discover(baseUrl: String): DavPrincipal {
        val principalUrl = findPrincipalUrl(baseUrl) ?: baseUrl
        val homeSet = findCalendarHomeSet(principalUrl)
        return DavPrincipal(principalUrl, homeSet)
    }

    private fun findPrincipalUrl(baseUrl: String): String? {
        val body = propfind(
            url = baseUrl,
            props = """<d:current-user-principal/>""",
        )
        val document = parseXml(body)
        val href = firstElementByTagNameNS(document, DAV_NS, "href") ?: return null
        return resolveUrl(baseUrl, href.textContent)
    }

    private fun findCalendarHomeSet(principalUrl: String): String? {
        val body = propfind(
            url = principalUrl,
            props = """<cal:calendar-home-set xmlns:cal="urn:ietf:params:xml:ns:caldav"/>""",
        )
        val document = parseXml(body)
        val href = firstElementByTagNameNS(document, CALDAV_NS, "calendar-home-set")
            ?.getElementsByTagNameNS(DAV_NS, "href")?.item(0) as? Element
            ?: return null
        return resolveUrl(principalUrl, href.textContent)
    }

    fun listCalendars(homeSetUrl: String): List<DavCalendar> {
        val body = propfind(
            url = homeSetUrl,
            depth = 1,
            props = """
                <d:resourcetype/>
                <d:displayname/>
                <x1:calendar-color xmlns:x1="http://apple.com/ns/ical/"/>
                <cal:supported-calendar-component-set xmlns:cal="urn:ietf:params:xml:ns:caldav"/>
            """.trimIndent(),
        )
        val document = parseXml(body)
        val responses = document.getElementsByTagNameNS(DAV_NS, "response")
        val calendars = mutableListOf<DavCalendar>()
        for (i in 0 until responses.length) {
            val response = responses.item(i) as Element
            val calendar = parseCalendarResponse(homeSetUrl, response) ?: continue
            calendars.add(calendar)
        }
        return calendars
    }

    private fun parseCalendarResponse(homeSetUrl: String, response: Element): DavCalendar? {
        val hrefElement = response.getElementsByTagNameNS(DAV_NS, "href").item(0) as? Element
            ?: return null
        val resourceType = response.getElementsByTagNameNS(DAV_NS, "resourcetype").item(0)
            as? Element ?: return null
        val isCalendar = resourceType.getElementsByTagNameNS(CALDAV_NS, "calendar").length > 0
        if (!isCalendar) {
            return null
        }
        val href = resolveUrl(homeSetUrl, hrefElement.textContent)
        val displayName =
            (response.getElementsByTagNameNS(DAV_NS, "displayname").item(0) as? Element)
                ?.textContent ?: href
        val color = (response.getElementsByTagNameNS(APPLE_NS, "calendar-color").item(0)
            as? Element)?.textContent?.let { parseColor(it) }
        val componentSet = response
            .getElementsByTagNameNS(CALDAV_NS, "supported-calendar-component-set").item(0)
            as? Element
        var supportsEvents = true
        var supportsTasks = false
        if (componentSet != null) {
            supportsEvents = false
            val components = componentSet.getElementsByTagNameNS(CALDAV_NS, "comp")
            for (i in 0 until components.length) {
                val component = components.item(i) as Element
                when (component.getAttribute("name").uppercase()) {
                    "VEVENT" -> supportsEvents = true
                    "VTODO" -> supportsTasks = true
                }
            }
        }
        return DavCalendar(href, displayName, color, supportsEvents, supportsTasks)
    }

    private fun propfind(url: String, props: String, depth: Int = 0): String {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
                <d:prop>$props</d:prop>
            </d:propfind>
        """.trimIndent()
        val response = client.request("PROPFIND", url, depth = depth, body = body)
        if (response.status !in 200..299) {
            throw DavException("PROPFIND $url failed with ${response.status}")
        }
        return response.body
    }

    companion object {
        const val DAV_NS = "DAV:"
        const val CALDAV_NS = "urn:ietf:params:xml:ns:caldav"
        const val APPLE_NS = "http://apple.com/ns/ical/"

        fun parseColor(value: String): Int? {
            val hex = value.removePrefix("#").take(6)
            if (hex.length != 6 || hex.any { Character.digit(it, 16) == -1 }) {
                return null
            }
            return hex.toInt(16) or 0xFF000000.toInt()
        }

        fun resolveUrl(baseUrl: String, href: String): String {
            if (href.startsWith("http://") || href.startsWith("https://")) {
                return href
            }
            val base = URL(baseUrl)
            return if (href.startsWith("/")) {
                "${base.protocol}://${base.host}${effectivePort(base)}$href"
            } else {
                "${base.protocol}://${base.host}${effectivePort(base)}${base.path.let { path -> path.substringBeforeLast('/') }}/$href"
            }
        }

        private fun effectivePort(url: URL): String =
            if (url.port == -1) "" else ":${url.port}"

        fun parseXml(xml: String): org.w3c.dom.Document {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val builder = factory.newDocumentBuilder()
            return builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }

        private fun firstElementByTagNameNS(
            document: org.w3c.dom.Document,
            namespace: String,
            localName: String,
        ): Element? =
            (document.getElementsByTagNameNS(namespace, localName).item(0) as? Element)
                ?.let { element ->
                    if (element.textContent.isNullOrBlank()) null else element
                }
    }
}

class DavException(message: String) : Exception(message)
