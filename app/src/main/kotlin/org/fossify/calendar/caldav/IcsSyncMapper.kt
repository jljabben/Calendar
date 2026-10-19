package org.fossify.calendar.caldav

import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.CalendarComponent
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
        val components: List<CalendarComponent> = calendar.getComponents()
        val event = components.filterIsInstance<VEvent>().firstOrNull()
        val todo = components.filterIsInstance<VToDo>().firstOrNull()
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

    private const val DATE_PATTERN = "^\\d{8}$"
    private const val DATE_TIME_PATTERN = "^\\d{8}T\\d{6}Z?$"
    private const val YEAR_START = 0
    private const val YEAR_END = 4
    private const val MONTH_START = 4
    private const val MONTH_END = 6
    private const val DAY_START = 6
    private const val DAY_END = 8
    private const val HOUR_START = 9
    private const val HOUR_END = 11
    private const val MINUTE_START = 11
    private const val MINUTE_END = 13
    private const val SECOND_START = 13
    private const val SECOND_END = 15

    fun isDateValue(value: String): Boolean = Regex(DATE_PATTERN).matches(value.trim())

    fun parse(value: String): Long? {
        val trimmed = value.trim()
        return try {
            when {
                isDateValue(trimmed) -> parseDate(trimmed)
                Regex(DATE_TIME_PATTERN).matches(trimmed) -> parseDateTime(trimmed)
                else -> null
            }
        } catch (_: java.time.DateTimeException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun parseDate(trimmed: String): Long {
        val date = java.time.LocalDate.of(
            trimmed.substring(YEAR_START, YEAR_END).toInt(),
            trimmed.substring(MONTH_START, MONTH_END).toInt(),
            trimmed.substring(DAY_START, DAY_END).toInt(),
        )
        return date.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()
    }

    private fun parseDateTime(trimmed: String): Long {
        val cleaned = trimmed.removeSuffix("Z")
        val dateTime = java.time.LocalDateTime.of(
            cleaned.substring(YEAR_START, YEAR_END).toInt(),
            cleaned.substring(MONTH_START, MONTH_END).toInt(),
            cleaned.substring(DAY_START, DAY_END).toInt(),
            cleaned.substring(HOUR_START, HOUR_END).toInt(),
            cleaned.substring(MINUTE_START, MINUTE_END).toInt(),
            cleaned.substring(SECOND_START, SECOND_END).toInt(),
        )
        return dateTime.toInstant(java.time.ZoneOffset.UTC).epochSecond
    }
}
