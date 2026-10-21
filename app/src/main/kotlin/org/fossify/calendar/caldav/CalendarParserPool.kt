package org.fossify.calendar.caldav

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Calendar
import java.io.StringReader

object CalendarParserPool {

    fun parse(ical: String): Calendar =
        CalendarBuilder().build(StringReader(ical))
}
