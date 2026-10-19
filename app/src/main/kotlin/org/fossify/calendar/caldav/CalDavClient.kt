package org.fossify.calendar.caldav

import at.bitfire.dav4jvm.property.caldav.CalendarColor
import at.bitfire.dav4jvm.property.caldav.CalendarData
import at.bitfire.dav4jvm.property.caldav.CalendarHomeSet
import at.bitfire.dav4jvm.property.caldav.SupportedCalendarComponentSet
import at.bitfire.dav4jvm.property.caldav.CalDAV as CalDAVProperty
import at.bitfire.dav4jvm.property.webdav.CurrentUserPrincipal
import at.bitfire.dav4jvm.property.webdav.DisplayName
import at.bitfire.dav4jvm.property.webdav.GetETag
import at.bitfire.dav4jvm.property.webdav.ResourceType
import at.bitfire.dav4jvm.ktor.DavCalendar
import at.bitfire.dav4jvm.ktor.DavCollection
import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.ktor.responses
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
 * dav4jvm registers its default property factories itself.
 */
class CalDavClient(private val account: DavAccount) {

    private fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
        followRedirects = false
        expectSuccess = false
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
        val responses = collection.propfind(
            depth = 1,
            DisplayName.NAME,
            ResourceType.NAME,
            CalendarColor.NAME,
            SupportedCalendarComponentSet.NAME,
        ).responses().toList()
        return responses.mapNotNull { response ->
            val isCalendar = response.properties
                .filterIsInstance<ResourceType>()
                .firstOrNull()
                ?.types
                ?.contains(CalDAVProperty.Calendar)
                ?: false
            if (!isCalendar || !response.isSuccess()) {
                return@mapNotNull null
            }
            val displayName = response.properties
                .filterIsInstance<DisplayName>()
                .firstOrNull()?.displayName
                ?: response.hrefName()
            val color = response.properties
                .filterIsInstance<CalendarColor>()
                .firstOrNull()?.color
            val componentSet = response.properties
                .filterIsInstance<SupportedCalendarComponentSet>()
                .firstOrNull()
            RemoteCalendar(
                url = response.href,
                displayName = displayName,
                colorArgb = color,
                supportsEvents = componentSet?.supportsEvents ?: true,
                supportsTasks = componentSet?.supportsTasks ?: false,
            )
        }
    }

    suspend fun listEvents(calendar: RemoteCalendar): List<RemoteResource> =
        queryComponents(calendar, "VEVENT")

    suspend fun listTasks(calendar: RemoteCalendar): List<RemoteResource> =
        queryComponents(calendar, "VTODO")

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
        ).responses().collect { response ->
            if (!response.isSuccess()) {
                return@collect
            }
            val etag = response.properties
                .filterIsInstance<GetETag>()
                .firstOrNull()?.etag
            val ical = response.properties
                .filterIsInstance<CalendarData>()
                .firstOrNull()?.iCalendar
            if (!ical.isNullOrBlank()) {
                resources.add(RemoteResource(response.href, etag, ical))
            }
        }
        return resources
    }

    private suspend fun findPrincipalUrl(client: HttpClient, baseUrl: Url): Url {
        val resource = DavResource(client, baseUrl)
        val principalHref = resource.propfind(depth = 0, CurrentUserPrincipal.NAME)
            .responses()
            .firstOrNull()
            ?.properties
            ?.filterIsInstance<CurrentUserPrincipal>()
            ?.firstOrNull()
            ?.href
            ?: return baseUrl
        return Url(principalHref)
    }

    private suspend fun findCalendarHomeSet(client: HttpClient, principalUrl: Url): Url {
        val collection = DavCollection(client, principalUrl)
        val homeSetHref = collection.propfind(depth = 0, CalendarHomeSet.NAME)
            .responses()
            .firstOrNull()
            ?.properties
            ?.filterIsInstance<CalendarHomeSet>()
            ?.firstOrNull()
            ?.hrefs
            ?.firstOrNull()
            ?: return principalUrl
        return Url(homeSetHref)
    }
}
