package org.fossify.calendar.caldav

import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.property.caldav.CalendarColor
import at.bitfire.dav4jvm.property.caldav.CalendarData
import at.bitfire.dav4jvm.property.caldav.CalendarHomeSet
import at.bitfire.dav4jvm.property.caldav.SupportedCalendarComponentSet
import at.bitfire.dav4jvm.property.caldav.CalDAV as CalDAVProperty
import at.bitfire.dav4jvm.property.webdav.CurrentUserPrincipal
import at.bitfire.dav4jvm.property.webdav.DisplayName
import at.bitfire.dav4jvm.property.webdav.GetETag
import at.bitfire.dav4jvm.property.webdav.ResourceType
import at.bitfire.dav4jvm.DavCollection
import at.bitfire.dav4jvm.DavCalendar
import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.Response
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.http.Url

data class DavAccount(
    val serverUrl: String,
    val username: String,
    val password: String,
)

data class RemoteCalendar(
    val url: Url,
    val displayName: String,
    val colorArgb: Int?,
    val supportsEvents: Boolean,
    val supportsTasks: Boolean,
)

data class RemoteResource(
    val url: Url,
    val etag: String?,
    val ical: String,
)

/**
 * CalDAV access layer built on dav4jvm (bitfire SMC, the DAVx5 authors).
 * Handles discovery, calendar listing and calendar-query REPORTs.
 */
class CalDavClient(private val account: DavAccount) {

    private fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
        followRedirects = false
        install(Auth) {
            basic {
                credentials { BasicAuthCredentials(account.username, account.password) }
                sendWithoutRequest { true }
            }
        }
    }

    suspend fun discoverHomeSet(): Url {
        val client = createHttpClient()
        val baseUrl = Url(account.serverUrl)
        val principalUrl = findPrincipalUrl(client, baseUrl)
        return findCalendarHomeSet(client, principalUrl)
    }

    suspend fun listCalendars(): List<RemoteCalendar> {
        val client = createHttpClient()
        val homeSetUrl = discoverHomeSet()
        val collection = DavCollection(client, homeSetUrl)
        val calendars = mutableListOf<RemoteCalendar>()
        collection.propfind(
            depth = 1,
            DisplayName.NAME,
            ResourceType.NAME,
            CalendarColor.NAME,
            SupportedCalendarComponentSet.NAME,
        ) { response, _ ->
            val calendar = toRemoteCalendar(response)
            if (calendar != null) {
                calendars.add(calendar)
            }
        }
        return calendars
    }

    suspend fun listEvents(calendar: RemoteCalendar): List<RemoteResource> =
        queryComponents(calendar, COMPONENT_EVENT)

    suspend fun listTasks(calendar: RemoteCalendar): List<RemoteResource> =
        queryComponents(calendar, COMPONENT_TASK)

    private suspend fun queryComponents(
        calendar: RemoteCalendar,
        component: String,
    ): List<RemoteResource> {
        val client = createHttpClient()
        val davCalendar = DavCalendar(client, calendar.url)
        val resources = mutableListOf<RemoteResource>()
        davCalendar.calendarQuery(
            component = component,
            start = null,
            end = null,
            props = setOf(GetETag.NAME, CalendarData.NAME),
        ) { response, _ ->
            val resource = toRemoteResource(response)
            if (resource != null) {
                resources.add(resource)
            }
        }
        return resources
    }

    private fun toRemoteCalendar(response: Response): RemoteCalendar? {
        val resourceType = response.get(ResourceType::class.java) ?: return null
        if (!resourceType.types.contains(CalDAVProperty.Calendar)) {
            return null
        }
        if (!response.isSuccess()) {
            return null
        }
        val displayName = response.get(DisplayName::class.java)?.displayName
            ?: response.hrefName()
        val color = response.get(CalendarColor::class.java)?.color
        val componentSet = response.get(SupportedCalendarComponentSet::class.java)
        return RemoteCalendar(
            url = response.href,
            displayName = displayName,
            colorArgb = color,
            supportsEvents = componentSet?.supportsEvents ?: true,
            supportsTasks = componentSet?.supportsTasks ?: false,
        )
    }

    private fun toRemoteResource(response: Response): RemoteResource? {
        if (!response.isSuccess()) {
            return null
        }
        val etag = response.get(GetETag::class.java)?.eTag
        val ical = response.get(CalendarData::class.java)?.iCalendar
        if (ical.isNullOrBlank()) {
            return null
        }
        return RemoteResource(response.href, etag, ical)
    }

    private suspend fun findPrincipalUrl(client: HttpClient, baseUrl: Url): Url {
        val resource = DavResource(client, baseUrl)
        var principalHref: String? = null
        resource.propfind(depth = 0, CurrentUserPrincipal.NAME) { response, _ ->
            if (principalHref == null) {
                principalHref = response.get(CurrentUserPrincipal::class.java)?.href
            }
        }
        return principalHref?.let { Url(it) } ?: baseUrl
    }

    private suspend fun findCalendarHomeSet(client: HttpClient, principalUrl: Url): Url {
        val collection = DavCollection(client, principalUrl)
        var homeSetHref: String? = null
        collection.propfind(depth = 0, CalendarHomeSet.NAME) { response, _ ->
            if (homeSetHref == null) {
                homeSetHref = response.get(CalendarHomeSet::class.java)?.hrefs?.firstOrNull()
            }
        }
        return homeSetHref?.let { Url(it) } ?: principalUrl
    }

    companion object {
        const val COMPONENT_EVENT = "VEVENT"
        const val COMPONENT_TASK = "VTODO"
    }
}
