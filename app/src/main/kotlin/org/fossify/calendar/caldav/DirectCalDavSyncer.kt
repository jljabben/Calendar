package org.fossify.calendar.caldav

import android.content.Context
import android.util.Log
import at.bitfire.dav4jvm.ktor.exception.DavException
import at.bitfire.dav4jvm.ktor.exception.HttpException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.fossify.calendar.extensions.calendarsDB
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.extensions.config
import org.fossify.calendar.models.CalendarEntity

/**
 * Entry point for syncing directly connected CalDAV accounts.
 * Reconciles the selected remote calendars with the local calendar
 * entities and pulls events and tasks into the Room database.
 */
class DirectCalDavSyncer(private val context: Context) {

    private companion object {
        const val TAG = "DirectCalDavSyncer"
    }

    private val helper = DirectCalDavHelper(context)
    private val config = context.config

    private fun logSyncError(e: Exception) {
        Log.w(TAG, "Direct CalDAV sync failed: ${e.message}")
    }

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
        if (selectedIds.none { it <= DirectCalDavHelper.DIRECT_CALENDAR_ID_THRESHOLD }) {
            return false
        }
        return true
    }

    /**
     * Syncs every selected direct calendar. Must be called off the main
     * thread; network calls are blocking within the caller's context.
     */
    fun syncAllSelected(): DirectSyncResult {
        val account = helper.account()
            ?: return DirectSyncResult(0, 0, 0, 0, 0, 0)
        val remoteCalendars = try {
            runBlocking { helper.fetchRemoteCalendars() }
        } catch (e: IOException) {
            logSyncError(e)
            return DirectSyncResult(0, 0, 0, 0, 0, 1)
        } catch (e: DavException) {
            logSyncError(e)
            return DirectSyncResult(0, 0, 0, 0, 0, 1)
        } catch (e: HttpException) {
            logSyncError(e)
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
            } catch (e: IOException) {
                logSyncError(e)
                errors++
            } catch (e: DavException) {
                logSyncError(e)
                errors++
            } catch (e: HttpException) {
                logSyncError(e)
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
                context.calendarsDB.insertOrUpdate(entity.copy(id = existing.id))
            }
        }
    }
}
