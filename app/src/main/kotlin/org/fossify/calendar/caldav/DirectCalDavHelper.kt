package org.fossify.calendar.caldav

import android.content.Context
import io.ktor.http.Url
import org.fossify.calendar.extensions.calendarsDB
import org.fossify.calendar.extensions.config
import org.fossify.calendar.helpers.FLAG_ALL_DAY
import org.fossify.calendar.helpers.FLAG_TASK_COMPLETED
import org.fossify.calendar.helpers.TYPE_TASK
import org.fossify.calendar.helpers.TYPE_EVENT
import org.fossify.calendar.models.CalendarEntity
import java.security.MessageDigest

/**
 * Bridges the direct CalDAV client (dav4jvm) into the app's Room storage.
 * Direct-sync calendars are stored as CalendarEntity rows whose
 * caldavCalendarId is a negative synthetic id derived from the remote
 * collection URL, so they can never collide with provider ids from
 * DAVx5-style accounts.
 */
class DirectCalDavHelper(private val context: Context) {

    private val config = context.config
    private val safetyGuard = DeletionSafetyGuard()

    fun account(): DavAccount? {
        val url = config.caldavServerUrl
        if (url.isEmpty() || config.caldavUsername.isEmpty()) {
            return null
        }
        return DavAccount(url, config.caldavUsername, config.caldavPassword)
    }

    suspend fun fetchRemoteCalendars(): List<RemoteCalendar> {
        val account = account() ?: return emptyList()
        return CalDavClient(account).listCalendars()
    }

    fun isDirectCalendar(entity: CalendarEntity): Boolean =
        entity.caldavCalendarId <= DIRECT_CALENDAR_ID_THRESHOLD

    fun directCalendarTag(calendar: RemoteCalendar): Int =
        syntheticCalendarId(calendar.url)

    fun calendarLabel(calendar: RemoteCalendar): String {
        val components = buildList {
            if (calendar.supportsEvents) {
                add(context.getString(org.fossify.commons.R.string.events))
            }
            if (calendar.supportsTasks) {
                add(context.getString(org.fossify.commons.R.string.tasks))
            }
        }.joinToString(separator = "/")
        return if (components.isEmpty()) {
            calendar.displayName
        } else {
            "${calendar.displayName} ($components)"
        }
    }

    fun toCalendarEntity(calendar: RemoteCalendar): CalendarEntity {
        val existing = context.calendarsDB.getCalendarWithCalDAVCalendarId(
            syntheticCalendarId(calendar.url),
        )
        return CalendarEntity(
            id = existing?.id,
            title = calendar.displayName,
            color = calendar.colorArgb ?: DEFAULT_CALENDAR_COLOR,
            caldavCalendarId = syntheticCalendarId(calendar.url),
            caldavDisplayName = calendar.displayName,
            caldavEmail = calendar.url.host,
        )
    }

    suspend fun syncCalendar(
        calendar: RemoteCalendar,
        localCalendarId: Long,
    ): SyncReport {
        val account = account() ?: throw IllegalStateException("No direct account configured")
        val client = CalDavClient(account)
        val remoteResources = buildList {
            if (calendar.supportsEvents) {
                addAll(client.listEvents(calendar))
            }
            if (calendar.supportsTasks) {
                addAll(client.listTasks(calendar))
            }
        }
        val engine = CalDavSyncEngine(
            repository = RoomSyncRepository(context, localCalendarId, calendar.url),
            safetyGuard = safetyGuard,
        )
        return engine.applyRemoteState(
            calendarUrl = calendar.url.toString(),
            remoteResources = remoteResources,
        )
    }

    companion object {
        const val DIRECT_CALENDAR_ID_THRESHOLD = -1000
        const val SOURCE_PREFIX = "caldav-direct"
        const val DEFAULT_CALENDAR_COLOR = -14145496

        fun syntheticCalendarId(url: Url): Int {
            val digest = MessageDigest.getInstance("MD5")
                .digest(url.toString().toByteArray())
            val positive = ((digest[0].toInt() and 0xFF) shl 24) or
                ((digest[1].toInt() and 0xFF) shl 16) or
                ((digest[2].toInt() and 0xFF) shl 8) or
                (digest[3].toInt() and 0xFF)
            return -(positive % 1_000_000) + DIRECT_CALENDAR_ID_THRESHOLD
        }

        fun sourceTagFor(url: Url): String =
            "$SOURCE_PREFIX-${url.host}${url.encodedPath.removeSuffix("/").replace('/', '-')}"
    }
}
