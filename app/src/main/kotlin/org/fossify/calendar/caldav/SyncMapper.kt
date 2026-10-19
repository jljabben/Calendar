package org.fossify.calendar.caldav

/**
 * Plain representation of a synced item, independent of the Room event model.
 * The app layer maps this onto Event/TYPE_TASK entities.
 */
data class SyncedItem(
    val uid: String,
    val resourceUrl: String,
    val etag: String?,
    val isTask: Boolean,
    val title: String,
    val description: String,
    val startTimestamp: Long?,
    val endTimestamp: Long?,
    val allDay: Boolean,
    val location: String,
    val completed: Boolean,
    val percentComplete: Int?,
    val recurrenceRule: String?,
    val lastModified: Long?,
)

object SyncMapper {

    fun toSyncedItem(resource: DavResource): SyncedItem? {
        val component = IcsParser.findComponent(resource.ical, "VEVENT")
            ?: IcsParser.findComponent(resource.ical, "VTODO")
            ?: return null
        val uid = component.property("UID") ?: return null
        val isTask = component.name == "VTODO"
        val start = component.property("DTSTART")
        val due = component.property("DUE")
        val completed = component.property("DTEND")
        val status = component.property("STATUS")
        val allDay = start != null && component
            .params("DTSTART")
            .contains("VALUE=DATE", ignoreCase = true)
        val percentComplete = component.property("PERCENT-COMPLETE")?.toIntOrNull()
        return SyncedItem(
            uid = uid,
            resourceUrl = resource.url,
            etag = resource.etag,
            isTask = isTask,
            title = component.property("SUMMARY").orEmpty(),
            description = component.property("DESCRIPTION").orEmpty(),
            startTimestamp = start?.let { IcsParser.parseUtcTimestamp(it) ?: IcsParser.parseDate(it) },
            endTimestamp = if (isTask) {
                due?.let { IcsParser.parseUtcTimestamp(it) ?: IcsParser.parseDate(it) }
            } else {
                completed?.let { IcsParser.parseUtcTimestamp(it) ?: IcsParser.parseDate(it) }
            },
            allDay = allDay,
            location = component.property("LOCATION").orEmpty(),
            completed = isTask && (status == "COMPLETED" || percentComplete == 100),
            percentComplete = percentComplete,
            recurrenceRule = component.property("RRULE"),
            lastModified = component.property("LAST-MODIFIED")?.let { IcsParser.parseUtcTimestamp(it) },
        )
    }
}
