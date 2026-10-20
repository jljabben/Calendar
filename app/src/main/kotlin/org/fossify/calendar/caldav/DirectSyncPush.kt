package org.fossify.calendar.caldav

import android.content.Context
import io.ktor.http.Url
import org.fossify.calendar.extensions.calendarsDB
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.models.Event

/**
 * Bridges local event changes into the direct CalDAV push layer.
 * Attached to insert/update/delete in EventsHelper for events that
 * live in a direct-synced calendar (negative synthetic calendar ids).
 */
@Suppress("TooGenericExceptionCaught")
class DirectSyncPush(private val context: Context) {

    private val config = context.config

    fun isDirectEvent(event: Event): Boolean {
        if (!event.source.startsWith(DirectCalDavHelper.SOURCE_PREFIX)) {
            return false
        }
        return true
    }

    fun pushInsert(event: Event) {
        if (!isDirectEvent(event)) {
            return
        }
        val calendarEntity = context.calendarsDB.getCalendarWithId(event.calendarId) ?: return
        val collectionUrl = collectionUrlFor(calendarEntity) ?: return
        val account = DavAccount(
            config.caldavServerUrl,
            config.caldavUsername,
            config.caldavPassword,
        )
        val ical = DirectEventWriter.writeEvent(event)
        val pusher = DirectCalDavPusher(account)
        val result = try {
            pusher.putEvent(collectionUrl, resourceUrlFor(event), event.etag.ifEmpty { null }, ical)
        } catch (t: Throwable) {
            SyncLogger.logError(context, "Pushing new event ${event.importId} failed", t)
            return
        }
        if (result.conflict) {
            SyncLogger.logError(
                context,
                "Push of ${event.importId} conflicted, reload the calendar",
                null,
            )
            return
        }
        event.etag = result.etag.orEmpty()
        context.eventsDB.insertOrUpdate(event)
    }

    fun pushUpdate(event: Event) {
        if (!isDirectEvent(event)) {
            return
        }
        pushInsert(event)
    }

    fun pushDelete(event: Event) {
        if (!isDirectEvent(event)) {
            return
        }
        val url = resourceUrlFor(event) ?: return
        val account = DavAccount(
            config.caldavServerUrl,
            config.caldavUsername,
            config.caldavPassword,
        )
        val pusher = DirectCalDavPusher(account)
        try {
            val result = pusher.deleteEvent(url, event.etag.ifEmpty { null })
            if (result.conflict) {
                SyncLogger.logError(
                    context,
                    "Deleting ${event.importId} conflicted, it was already changed remotely",
                    null,
                )
            }
        } catch (t: Throwable) {
            SyncLogger.logError(context, "Deleting ${event.importId} failed", t)
        }
    }

    private fun collectionUrlFor(entity: org.fossify.calendar.models.CalendarEntity): Url? =
        entity.collectionUrl.takeIf { it.isNotEmpty() }?.let { Url(it) }

    private fun resourceUrlFor(event: Event): Url? =
        event.resourceUrl?.let { Url(it) }
}
