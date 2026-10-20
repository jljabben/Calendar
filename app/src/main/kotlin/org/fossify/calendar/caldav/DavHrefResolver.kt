package org.fossify.calendar.caldav

import io.ktor.http.URLBuilder
import io.ktor.http.Url

/**
 * Resolves href values from WebDAV properties against the URL they were
 * requested from. Servers commonly return relative references like
 * "/caldav/user/" which must be resolved per RFC 6764 section 6.
 */
object DavHrefResolver {

    fun resolve(baseUrl: Url, href: String): Url {
        val trimmed = href.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return Url(trimmed)
        }
        val builder = URLBuilder(baseUrl)
        if (trimmed.startsWith("/")) {
            builder.encodedPath = trimmed
        } else {
            val basePath = baseUrl.encodedPath.substringBeforeLast('/', "")
            builder.encodedPath = "$basePath/$trimmed"
        }
        return builder.build()
    }
}
