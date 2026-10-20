package org.fossify.calendar.caldav

import android.content.Context
import io.ktor.http.Url
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.helpers.FLAG_ALL_DAY
import org.fossify.calendar.helpers.FLAG_TASK_COMPLETED
import org.fossify.calendar.helpers.TYPE_EVENT
import org.fossify.calendar.helpers.TYPE_TASK
import org.fossify.calendar.models.Event

/**
 * SyncRepository implementation on top of the app's Room events table.
 * Remote UIDs are stored in importId, the collection tag in source and
 * the remote ETag in the etag column.
 */
class RoomSyncRepository(
    private val context: Context,
    private val calendarId: Long,
    private val calendarUrl: Url,
) : SyncRepository {

    private val sourceTag = DirectCalDavHelper.sourceTagFor(calendarUrl)

    override fun getItemsForCalendar(calendarUrl: String): List<SyncRepository.StoredItem> =
        context.eventsDB.getEventsAndTasksBySource(sourceTag).map { event ->
            SyncRepository.StoredItem(
                id = event.id ?: -1L,
                uid = event.importId,
                etag = event.etag.ifEmpty { null },
            )
        }

    override fun upsertItem(item: SyncRepository.StoredItem) {
        val mapped = item.mapped ?: return
        val existing = context.eventsDB.getEventOrTaskWithImportId(item.uid, sourceTag)
        val event = existing ?: Event(
            id = null,
            startTS = 0L,
            endTS = 0L,
            title = mapped.title,
            importId = item.uid,
            source = sourceTag,
            calendarId = calendarId,
        )
        event.applyMapped(mapped, item.etag, calendarId)
        context.eventsDB.insertOrUpdate(event)
    }

    override fun deleteItem(itemId: Long) {
        context.eventsDB.deleteEvents(listOf(itemId))
    }

    private fun Event.applyMapped(mapped: SyncedItem, etag: String?, calendarId: Long) {
        title = mapped.title
        description = mapped.description
        startTS = mapped.startTS
        endTS = mapped.endTimestamp ?: startTS
        location = mapped.location
        type = if (mapped.isTask) TYPE_TASK else TYPE_EVENT
        flags = mappedFlags(mapped)
        this.calendarId = calendarId
        lastUpdated = System.currentTimeMillis() / 1000L
        this.etag = etag.orEmpty()
    }

    private fun mappedFlags(mapped: SyncedItem): Int {
        var flags = 0
        if (mapped.allDay) {
            flags = flags or FLAG_ALL_DAY
        }
        if (mapped.isTask && mapped.completed) {
            flags = flags or FLAG_TASK_COMPLETED
        }
        return flags
    }
}
