package org.fossify.calendar.caldav

/**
 * Minimal, line-based iCalendar parser tuned for CalDAV sync.
 *
 * Extracts the properties needed to map VEVENT and VTODO components onto the
 * local events table. Handles line unfolding (RFC 5545 3.1) and basic
 * escaped-text unescaping.
 */
data class IcsComponent(
    val name: String,
    val properties: MutableMap<String, MutableList<String>> = mutableMapOf(),
    val propertyParams: MutableMap<String, String> = mutableMapOf(),
) {
    fun property(name: String): String? = properties[name]?.firstOrNull()

    fun allProperties(name: String): List<String> = properties[name].orEmpty()

    fun params(name: String): String = propertyParams[name].orEmpty()
}

object IcsParser {

    fun unfold(rawIcal: String): List<String> {
        val lines = rawIcal.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val unfolded = mutableListOf<String>()
        for (line in lines) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && unfolded.isNotEmpty()) {
                unfolded[unfolded.size - 1] += line.substring(1)
            } else {
                unfolded.add(line)
            }
        }
        return unfolded
    }

    fun parse(rawIcal: String): List<IcsComponent> {
        val components = mutableListOf<IcsComponent>()
        val stack = mutableListOf<IcsComponent>()
        for (line in unfold(rawIcal)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            when {
                trimmed.startsWith("BEGIN:") -> {
                    val component = IcsComponent(trimmed.removePrefix("BEGIN:").uppercase())
                    stack.add(component)
                }
                trimmed.startsWith("END:") -> {
                    val name = trimmed.removePrefix("END:").uppercase()
                    if (stack.isEmpty() || stack.last().name != name) continue
                    val component = stack.removeAt(stack.size - 1)
                    components.add(component)
                }
                else -> {
                    if (stack.isEmpty()) continue
                    val colon = trimmed.indexOf(':')
                    if (colon <= 0) continue
                    val nameAndParams = trimmed.substring(0, colon)
                    val value = trimmed.substring(colon + 1)
                    val name = nameAndParams.substringBefore(';').uppercase()
                    val params = nameAndParams.substringAfter(';', "")
                    val component = stack.last()
                    component.properties
                        .getOrPut(name) { mutableListOf() }
                        .add(unescape(value))
                    if (params.isNotEmpty() && !component.propertyParams.containsKey(name)) {
                        component.propertyParams[name] = params
                    }
                }
            }
        }
        return components
    }

    fun findComponent(rawIcal: String, name: String): IcsComponent? =
        parse(rawIcal).firstOrNull { it.name == name }

    fun parseUtcTimestamp(value: String): Long? {
        val trimmed = value.trim().removeSuffix("Z")
        val match = Regex("^(\\d{8})T(\\d{6})$").matchEntire(trimmed) ?: return null
        val (date, time) = match.destructured
        val year = date.substring(0, 4).toInt()
        val month = date.substring(4, 6).toInt()
        val day = date.substring(6, 8).toInt()
        val hour = time.substring(0, 2).toInt()
        val minute = time.substring(2, 4).toInt()
        val second = time.substring(4, 6).toInt()
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, second)
        return calendar.timeInMillis / 1000
    }

    fun parseDate(value: String): Long? {
        val match = Regex("^(\\d{4})(\\d{2})(\\d{2})$").matchEntire(value.trim()) ?: return null
        val (year, month, day) = match.destructured
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(year.toInt(), month.toInt() - 1, day.toInt(), 0, 0, 0)
        return calendar.timeInMillis / 1000
    }

    private fun unescape(value: String): String = value
        .replace("\\n", "\n")
        .replace("\\N", "\n")
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")
}
