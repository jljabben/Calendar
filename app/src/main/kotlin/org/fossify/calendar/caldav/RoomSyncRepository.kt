package org.fossify.calendar.caldav

import android.content.Context
import io.ktor.http.Url
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.helpers.FLAG_ALL_DAY
import org.fossify.calendar.helpers.FLAG_TASK_COMPLETED
import org.fossify.calendar.helpers.Parser
import org.fossify.calendar.helpers.TYPE_EVENT
import org.fossify.calendar.helpers.TYPE_TASK
import org.fossify.calendar.models.Event

/**
 * SyncRepository implementation on top of the app's Room events table.
 * Remote UIDs are stored in importId, the collection tag in source and
 * the remote ETag in the etag column.
 *
 * Items are validated before they are written: the existing view code
 * assumes positive timestamps and sane repetition values, so importing
 * e.g. tasks without DTSTART or malformed RRULEs would break event
 * expansion at render time.
 */
class RoomSyncRepository(
    private val context: Context,
    private val calendarId: Long,
    private val calendarUrl: Url,
) : SyncRepository {

    private val sourceTag = DirectCalDavHelper.sourceTagFor(calendarUrl)
    private val parser = Parser()

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
        if (!isImportable(mapped)) {
            return
        }
        val existing = context.eventsDB.getEventOrTaskWithImportId(item.uid, sourceTag)
        val event = existing ?: Event(
            id = null,
            startTS = mapped.startTimestamp ?: return,
            endTS = mapped.endTimestamp ?: mapped.startTimestamp ?: return,
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

    /**
     * Only import items the app can safely render: they need a start
     * timestamp and a non-negative duration. Repetition is imported via
     * the app's RRULE parser; unknown or unparseable rules are ignored
     * (the item is still imported as a one-time entry).
     */
    private fun isImportable(mapped: SyncedItem): Boolean {
        val start = mapped.startTimestamp ?: return false
        val end = mapped.endTimestamp ?: start
        return start > 0 && end >= start
    }

    private fun Event.applyMapped(mapped: SyncedItem, etag: String?, calendarId: Long) {
        title = mapped.title
        description = mapped.description
        startTS = mapped.startTimestamp ?: startTS
        endTS = mapped.endTimestamp ?: startTS
        location = mapped.location
        type = if (mapped.isTask) TYPE_TASK else TYPE_EVENT
        flags = mappedFlags(mapped)
        this.calendarId = calendarId
        lastUpdated = System.currentTimeMillis() / 1000L
        this.etag = etag.orEmpty()
        applyRepetition(mapped)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun Event.applyRepetition(mapped: SyncedItem) {
        val rule = mapped.recurrenceRule
        if (rule.isNullOrBlank()) {
            repeatInterval = 0
            repeatRule = 0
            repeatLimit = 0L
            return
        }
        try {
            val repetition = parser.parseRepeatInterval(rule, startTS)
            repeatInterval = repetition.repeatInterval
            repeatRule = repetition.repeatRule
            repeatLimit = repetition.repeatLimit
        } catch (t: Throwable) {
            // unparsable rules (e.g. INTERVAL=2.0) must never crash the sync;
            // import the item as one-time entry instead
            repeatInterval = 0
            repeatRule = 0
            repeatLimit = 0L
        }
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
