package org.fossify.calendar.caldav

object DavAccountUrl {

    fun normalize(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return trimmed
        }
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("dav://", ignoreCase = true) ->
                "http://" + trimmed.removePrefix("dav://").removePrefix("DAV://")
            trimmed.startsWith("caldav://", ignoreCase = true) ->
                "http://" + trimmed.removePrefix("caldav://").removePrefix("CALDAV://")
            else -> "https://$trimmed"
        }
        return withScheme.trimEnd('/')
    }
}
