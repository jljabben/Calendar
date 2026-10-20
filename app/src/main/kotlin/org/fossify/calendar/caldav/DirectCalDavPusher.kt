package org.fossify.calendar.caldav

import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.ktor.exception.PreconditionFailedException
import at.bitfire.dav4jvm.property.webdav.GetETag
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Pushes local changes of a direct-synced calendar to the CalDAV server,
 * the way DAVx5 does it:
 * - new resources: PUT to a fresh .ics URL inside the collection
 * - changed resources: PUT with If-Match:<etag> so concurrent remote
 *   changes win safely (HTTP 412 otherwise)
 * - removed resources: DELETE with If-Match:<etag>
 */
@Suppress("TooGenericExceptionCaught")
class DirectCalDavPusher(private val account: DavAccount) {

    class PushResult(
        val resourceUrl: Url?,
        val etag: String?,
        val conflict: Boolean,
    )

    fun putEvent(
        calendarUrl: Url,
        resourceUrl: Url?,
        currentEtag: String?,
        ical: String,
    ): PushResult = runBlocking {
        val client = CalDavClient(account).createPublicHttpClient()
        val target = resourceUrl ?: defaultResourceUrl(calendarUrl, ical)
        val headers = buildHeaders(currentEtag)
        var newEtag: String? = null
        var conflict = false
        try {
            DavResource(client, target).put(
                body = TextContent(ical, ContentType.parse(MIME_ICS)),
                additionalHeaders = headers,
            ) { response ->
                if (response.status.isSuccess()) {
                    newEtag = GetETag.fromHttpResponse(response)?.eTag
                }
            }
        } catch (e: PreconditionFailedException) {
            conflict = true
        }
        PushResult(target, newEtag, conflict)
    }

    fun deleteEvent(resourceUrl: Url, currentEtag: String?): PushResult = runBlocking {
        val client = CalDavClient(account).createPublicHttpClient()
        var conflict = false
        try {
            DavResource(client, resourceUrl).delete(
                additionalHeaders = buildHeaders(currentEtag),
            ) { }
        } catch (e: PreconditionFailedException) {
            conflict = true
        }
        PushResult(resourceUrl, null, conflict)
    }

    private fun buildHeaders(etag: String?): Headers? =
        etag?.let { headersOf("If-Match", it) }

    private fun defaultResourceUrl(calendarUrl: Url, ical: String): Url {
        val uid = Regex("UID:(\\S+)")
            .find(ical)?.groupValues?.getOrNull(1)
            ?: UUID.randomUUID().toString()
        val base = calendarUrl.toString().trimEnd('/')
        return Url("$base/$uid.ics")
    }

    companion object {
        const val MIME_ICS = "text/calendar"
    }
}
