package org.fossify.calendar.caldav

import android.content.Context
import at.bitfire.dav4jvm.ktor.exception.DavException
import at.bitfire.dav4jvm.ktor.exception.HttpException
import kotlinx.coroutines.runBlocking
import org.fossify.calendar.extensions.calendarsDB
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.models.CalendarEntity
import java.io.IOException

/**
 * Entry point for syncing directly connected CalDAV accounts.
 * Reconciles the selected remote calendars with the local calendar
 * entities and pulls events and tasks into the Room database.
 *
 * Every trigger calls [syncAllSelectedSafely] which never throws: any
 * failure is caught, logged to the sync log file and reported through
 * the result, so a broken server response can never crash the app.
 */
class DirectCalDavSyncer(private val context: Context) {

    private val helper = DirectCalDavHelper(context)
    private val config = context.config

    data class DirectSyncResult(
        val calendars: Int,
        val inserted: Int,
        val updated: Int,
        val deleted: Int,
        val skippedDeletions: Int,
        val errors: Int,
    )

    /**
     * Whether the user selected any direct calendar in the sync selection.
     */
    fun hasSelectedDirectCalendars(): Boolean {
        if (helper.account() == null) {
            return false
        }
        val selectedIds = config.getSyncedCalendarIdsAsList()
        return selectedIds.any { it <= DirectCalDavHelper.DIRECT_CALENDAR_ID_THRESHOLD }
    }

    /**
     * Crash-safe variant for all sync triggers. Catches every throwable,
     * logs the full stack trace to the sync log and returns a result.
     */
    fun syncAllSelectedSafely(): DirectSyncResult = try {
        syncAllSelected()
    } catch (t: Throwable) {
        SyncLogger.logError(context, "Direct CalDAV sync failed", t)
        DirectSyncResult(0, 0, 0, 0, 0, 1)
    }

    /**
     * Removes stored direct-sync rows that the views cannot render
     * (missing or zero timestamps, negative duration).
     */
    fun sanitizeStoredItems() {
        val directEntities = context.calendarsDB.getCalendars()
            .filter { it.caldavCalendarId <= DirectCalDavHelper.DIRECT_CALENDAR_ID_THRESHOLD }
        for (entity in directEntities) {
            val events = context.eventsDB.getEventsAndTasksBySourcePrefix(
                DirectCalDavHelper.SOURCE_PREFIX + "-" + entity.caldavEmail,
            )
            val invalid = events.filter { it.startTS <= 0L || it.endTS < it.startTS }
            if (invalid.isNotEmpty()) {
                context.eventsDB.deleteEvents(invalid.mapNotNull { it.id })
            }
        }
    }

    /**
     * Syncs every selected direct calendar. Must be called off the main
     * thread; network calls are blocking within the caller's context.
     */
    fun syncAllSelected(): DirectSyncResult {
        val account = helper.account()
            ?: return DirectSyncResult(0, 0, 0, 0, 0, 0)
        sanitizeStoredItems()
        val remoteCalendars = try {
            runBlocking { helper.fetchRemoteCalendars() }
        } catch (e: IOException) {
            logSyncError("Could not fetch remote calendars", e)
            return DirectSyncResult(0, 0, 0, 0, 0, 1)
        } catch (e: DavException) {
            logSyncError("Could not fetch remote calendars", e)
            return DirectSyncResult(0, 0, 0, 0, 0, 1)
        } catch (e: HttpException) {
            logSyncError("Could not fetch remote calendars", e)
            return DirectSyncResult(0, 0, 0, 0, 0, 1)
        }

        val selectedIds = config.getSyncedCalendarIdsAsList()
        val selected = remoteCalendars.filter {
            selectedIds.contains(helper.directCalendarTag(it))
        }
        reconcileCalendarEntities(selected)

        var inserted = 0
        var updated = 0
        var deleted = 0
        var skipped = 0
        var errors = 0
        for (calendar in selected) {
            val entity = context.calendarsDB.getCalendarWithCalDAVCalendarId(
                helper.directCalendarTag(calendar),
            ) ?: continue
            try {
                val report = runBlocking { helper.syncCalendar(calendar, entity.id!!) }
                inserted += report.inserted
                updated += report.updated
                deleted += report.deleted
                skipped += report.skippedDeletions
            } catch (t: Throwable) {
                logSyncError(
                    "Syncing calendar ${calendar.displayName} (${calendar.url}) failed",
                    t,
                )
                errors++
            }
        }
        return DirectSyncResult(selected.size, inserted, updated, deleted, skipped, errors)
    }

    /**
     * Removes a deselected direct calendar together with its stored
     * events and tasks. This is an explicit user action (deselect), so
     * it is not subject to the deletion ratio guard.
     */
    private fun removeDirectCalendar(entity: CalendarEntity) {
        val entityId = entity.id ?: return
        val eventIds = context.eventsDB.getEventAndTasksIdsByCalendar(entityId)
        context.eventsDB.deleteEvents(eventIds)
        val deletedIds = setOf(entityId.toString())
        config.removeDisplayCalendars(deletedIds)
        context.calendarsDB.deleteCalendars(listOf(entity))
    }

    /**
     * Creates local calendar entities for newly selected direct calendars
     * and removes entities of deselected ones (including their events).
     */
    private fun reconcileCalendarEntities(selected: List<RemoteCalendar>) {
        val selectedTags = selected.map { helper.directCalendarTag(it) }.toSet()
        val existingDirect = context.calendarsDB.getCalendars()
            .filter { it.caldavCalendarId <= DirectCalDavHelper.DIRECT_CALENDAR_ID_THRESHOLD }

        existingDirect.filter { !selectedTags.contains(it.caldavCalendarId) }
            .forEach { entity ->
                removeDirectCalendar(entity)
            }

        for (calendar in selected) {
            val entity = helper.toCalendarEntity(calendar)
            val existing = context.calendarsDB.getCalendarWithCalDAVCalendarId(
                helper.directCalendarTag(calendar),
            )
            if (existing == null) {
                val newId = context.calendarsDB.insertOrUpdate(entity)
                config.addDisplayCalendar(newId.toString())
            } else if (existing.title != entity.title || existing.color != entity.color) {
                val existingId = existing.id
                if (existingId != null) {
                    context.calendarsDB.insertOrUpdate(entity.copy(id = existingId))
                }
            }
        }
    }

    private fun logSyncError(message: String, throwable: Throwable) {
        SyncLogger.logError(context, message, throwable)
    }
}
