package org.fossify.calendar.caldav

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class DavResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.firstOrNull()
}

interface DavHttpClient {
    @Throws(IOException::class)
    fun request(
        method: String,
        url: String,
        depth: Int? = null,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): DavResponse
}

class UrlConnectionDavHttpClient(
    private val username: String,
    private val password: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : DavHttpClient {
    override fun request(
        method: String,
        url: String,
        depth: Int?,
        headers: Map<String, String>,
        body: String?,
    ): DavResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true
        depth?.let { connection.setRequestProperty("Depth", it.toString()) }
        connection.setRequestProperty("Authorization", basicAuthHeader(username, password))
        connection.setRequestProperty("User-Agent", "FossifyCalendar")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        body?.let { payload ->
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/xml;charset=UTF-8")
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val responseHeaders = mutableMapOf<String, MutableList<String>>()
        for ((key, values) in connection.headerFields.orEmpty()) {
            if (key == null || values == null) continue
            responseHeaders.getOrPut(key.lowercase()) { mutableListOf() }.addAll(values)
        }
        val stream = if (status in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return DavResponse(status, responseHeaders, responseBody)
    }

    companion object {
        fun basicAuthHeader(username: String, password: String): String =
            "Basic " + java.util.Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
    }
}
