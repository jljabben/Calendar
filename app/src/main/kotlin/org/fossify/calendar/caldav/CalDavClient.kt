package org.fossify.calendar.caldav

import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.PropertyRegistry
import at.bitfire.dav4jvm.property.caldav.CalendarColor
import at.bitfire.dav4jvm.property.caldav.CalendarData
import at.bitfire.dav4jvm.property.caldav.CalendarHomeSet
import at.bitfire.dav4jvm.property.caldav.SupportedCalendarComponentSet
import at.bitfire.dav4jvm.property.webdav.DisplayName
import at.bitfire.dav4jvm.property.webdav.GetETag
import at.bitfire.dav4jvm.property.webdav.ResourceType
import at.bitfire.dav4jvm.property.webdav.CurrentUserPrincipal
import at.bitfire.dav4jvm.ktor.DavCalendar
import at.bitfire.dav4jvm.ktor.DavCollection
import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.ktor.MultiStatusItem
import at.bitfire.dav4jvm.ktor.exception.HttpException
import at.bitfire.dav4jvm.ktor.responses
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.http.Url
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

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
class DavClientFactory {

    fun createHttpClient(account: DavAccount): HttpClient {
        val client = HttpClient(OkHttp) {
            followRedirects = false
            expectSuccess = false
            install(Auth) {
                basic {
                    credentials { BasicAuthCredentials(account.username, account.password) }
                    sendWithoutRequest { true }
                }
            }
        }
        return client
    }

    companion object {
        init {
            PropertyRegistry.register(CalendarColor.Factory)
            PropertyRegistry.register(CalendarData.Factory)
            PropertyRegistry.register(CalendarHomeSet.Factory)
            PropertyRegistry.register(SupportedCalendarComponentSet.Factory)
        }
    }
}

class CalDavClient(private val account: DavAccount) {

    private val factory = DavClientFactory()

    suspend fun discoverHomeSet(httpClient: HttpClient? = null): Url {
        val client = httpClient ?: factory.createHttpClient(account)
        val baseUrl = Url(account.serverUrl)
        val principalUrl = findPrincipalUrl(client, baseUrl)
        return findCalendarHomeSet(client, principalUrl)
    }

    suspend fun listCalendars(homeSet: Url? = null): List<RemoteCalendar> {
        val client = factory.createHttpClient(account)
        val homeSetUrl = homeSet ?: discoverHomeSet(client)
        val collection = DavCollection(client, homeSetUrl)
        val responses = collection.propfind(
            depth = 1,
            DisplayName.NAME,
            ResourceType.NAME,
            CalendarColor.NAME,
            SupportedCalendarComponentSet.NAME,
        ).responses().toList()
        return responses.mapNotNull { response ->
            val isCalendar = response.properties.any { property ->
                property is ResourceType && property.xml.toString().contains("calendar")
            }
            if (!isCalendar || !response.isSuccess()) {
                return@mapNotNull null
            }
            val displayName = response.properties
                .filterIsInstance<DisplayName>()
                .firstOrNull()?.displayName
                ?: response.hrefName()
            val color = response.properties
                .filterIsInstance<CalendarColor>()
                .firstOrNull()?.color?.let { ColorParser.toArgb(it) }
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

    suspend fun listEvents(calendar: RemoteCalendar): List<RemoteResource> {
        val client = factory.createHttpClient(account)
        return queryComponents(client, calendar, "VEVENT")
    }

    suspend fun listTasks(calendar: RemoteCalendar): List<RemoteResource> {
        val client = factory.createHttpClient(account)
        return queryComponents(client, calendar, "VTODO")
    }

    private suspend fun queryComponents(
        client: HttpClient,
        calendar: RemoteCalendar,
        component: String,
    ): List<RemoteResource> {
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
            ?.filterIsInstance<at.bitfire.dav4jvm.property.webdav.CurrentUserPrincipal>()
            ?.firstOrNull()
            ?.href
            ?: baseUrl
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
            ?: principalUrl
        return Url(homeSetHref)
    }
}

object ColorParser {
    fun toArgb(color: String): Int? {
        val hex = color.removePrefix("#")
        val rgb = hex.take(6)
        if (rgb.length != 6 || rgb.any { Character.digit(it, 16) == -1 }) {
            return null
        }
        return rgb.toInt(16) or 0xFF000000.toInt()
    }
}
