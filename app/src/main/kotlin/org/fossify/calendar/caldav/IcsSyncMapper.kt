package org.fossify.calendar.caldav

import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VToDo

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

object IcsSyncMapper {

    private fun <T : Property> Component.prop(name: String): T? =
        getProperty<T>(name).orElse(null)

    fun toSyncedItem(resource: RemoteResource): SyncedItem? {
        val calendar = try {
            CalendarParserPool.parse(resource.ical)
        } catch (_: Exception) {
            return null
        }
        val event = calendar.getComponents().filterIsInstance<VEvent>().firstOrNull()
        val todo = calendar.getComponents().filterIsInstance<VToDo>().firstOrNull()
        val component = (event ?: todo) ?: return null
        val uid = component.prop<Property>(Property.UID)?.value ?: return null
        val isTask = component is VToDo
        val start = component.prop<Property>(Property.DTSTART)?.value
        val end = if (isTask) {
            component.prop<Property>(Property.DUE)?.value
        } else {
            component.prop<Property>(Property.DTEND)?.value
        }
        val status = component.prop<Property>(Property.STATUS)?.value
        val percentComplete = component.prop<Property>(Property.PERCENT_COMPLETE)
            ?.value?.toIntOrNull()
        val allDay = start?.let { Timestamps.isDateValue(it) } ?: false
        return SyncedItem(
            uid = uid,
            resourceUrl = resource.url.toString(),
            etag = resource.etag,
            isTask = isTask,
            title = component.prop<Property>(Property.SUMMARY)?.value.orEmpty(),
            description = component.prop<Property>(Property.DESCRIPTION)?.value.orEmpty(),
            startTimestamp = start?.let { Timestamps.parse(it) },
            endTimestamp = end?.let { Timestamps.parse(it) },
            allDay = allDay,
            location = component.prop<Property>(Property.LOCATION)?.value.orEmpty(),
            completed = isTask && (status == "COMPLETED" || percentComplete == 100),
            percentComplete = percentComplete,
            recurrenceRule = component.prop<Property>(Property.RRULE)?.value,
            lastModified = component.prop<Property>(Property.LAST_MODIFIED)?.value
                ?.let { Timestamps.parse(it) },
        )
    }
}

object Timestamps {

    fun isDateValue(value: String): Boolean = Regex("^\\d{8}$").matches(value.trim())

    fun parse(value: String): Long? {
        val trimmed = value.trim()
        return try {
            when {
                isDateValue(trimmed) -> {
                    val date = java.time.LocalDate.of(
                        trimmed.substring(0, 4).toInt(),
                        trimmed.substring(4, 6).toInt(),
                        trimmed.substring(6, 8).toInt(),
                    )
                    date.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()
                }
                Regex("^\\d{8}T\\d{6}Z?$").matches(trimmed) -> {
                    val cleaned = trimmed.removeSuffix("Z")
                    val dateTime = java.time.LocalDateTime.of(
                        cleaned.substring(0, 4).toInt(),
                        cleaned.substring(4, 6).toInt(),
                        cleaned.substring(6, 8).toInt(),
                        cleaned.substring(9, 11).toInt(),
                        cleaned.substring(11, 13).toInt(),
                        cleaned.substring(13, 15).toInt(),
                    )
                    dateTime.toInstant(java.time.ZoneOffset.UTC).epochSecond
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
