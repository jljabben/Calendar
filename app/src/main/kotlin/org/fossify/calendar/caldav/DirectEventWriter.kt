package org.fossify.calendar.caldav

import net.fortuna.ical4j.data.CalendarOutputter
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VToDo
import net.fortuna.ical4j.model.property.CalScale
import net.fortuna.ical4j.model.property.Description
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.Due
import net.fortuna.ical4j.model.property.Location
import net.fortuna.ical4j.model.property.PercentComplete
import net.fortuna.ical4j.model.property.ProdId
import net.fortuna.ical4j.model.property.Status
import net.fortuna.ical4j.model.property.Summary
import net.fortuna.ical4j.model.property.Uid
import net.fortuna.ical4j.model.property.Version
import org.fossify.calendar.models.Event
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Generates the iCalendar representation of a locally stored event or
 * task for uploading to the CalDAV server. Mirrors how ical4android
 * (DAVx5) builds calendars for upload: PRODID/VERSION/CALSCALE header,
 * one VEVENT or VTODO component, serialized with CalendarOutputter.
 */
object DirectEventWriter {

    private const val PROD_ID = "-//Fossify//Calendar//EN"
    private const val PERCENT_COMPLETE_DONE = 100
    private val UTC_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    fun writeEvent(event: Event): String {
        val uid = event.importId.ifEmpty { UUID.randomUUID().toString() }
        val ical = Calendar()
        ical.properties.add(ProdId(PROD_ID))
        ical.properties.add(Version())
        ical.properties.add(CalScale.GREGORIAN)
        if (event.isTask()) {
            ical.components.add(buildVToDo(event, uid))
        } else {
            ical.components.add(buildVEvent(event, uid))
        }
        val out = ByteArrayOutputStream()
        CalendarOutputter(false).output(ical, out)
        return out.toString("UTF-8")
    }

    private fun buildVEvent(event: Event, uid: String): VEvent {
        val vEvent = VEvent()
        vEvent.properties.add(Uid(uid))
        vEvent.properties.add(DtStart(utcDateTime(event.startTS)))
        vEvent.properties.add(DtEnd(utcDateTime(event.endTS)))
        addCommonProperties(vEvent.properties, event)
        return vEvent
    }

    private fun buildVToDo(event: Event, uid: String): VToDo {
        val vToDo = VToDo()
        vToDo.properties.add(Uid(uid))
        vToDo.properties.add(DtStart(utcDateTime(event.startTS)))
        vToDo.properties.add(Due(utcDateTime(event.endTS)))
        addCommonProperties(vToDo.properties, event)
        if (event.isTaskCompleted()) {
            vToDo.properties.add(Status.VTODO_COMPLETED)
            vToDo.properties.add(PercentComplete(PERCENT_COMPLETE_DONE))
        }
        return vToDo
    }

    private fun addCommonProperties(properties: MutableList<Property>, event: Event) {
        event.title.takeIf { it.isNotEmpty() }?.let { properties.add(Summary(it)) }
        event.description.takeIf { it.isNotEmpty() }?.let { properties.add(Description(it)) }
        event.location.takeIf { it.isNotEmpty() }?.let { properties.add(Location(it)) }
    }

    private fun utcDateTime(timestamp: Long): String {
        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC)
        return dateTime.format(UTC_FORMAT)
    }
}
