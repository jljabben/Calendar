package org.fossify.calendar.caldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.ktor.http.Url

class CalDavSyncEngineTest {

    private class InMemoryRepository : SyncRepository {
        val items = mutableListOf<SyncRepository.StoredItem>()
        private var nextId = 1L

        override fun getItemsForCalendar(calendarUrl: String): List<SyncRepository.StoredItem> =
            items.toList()

        override fun upsertItem(item: SyncRepository.StoredItem) {
            if (item.id == -1L) {
                items.add(SyncRepository.StoredItem(nextId++, item.uid, item.etag))
            } else {
                val index = items.indexOfFirst { it.id == item.id }
                if (index >= 0) {
                    items[index] = item
                } else {
                    items.add(item)
                }
            }
        }

        override fun deleteItem(itemId: Long) {
            items.removeIf { it.id == itemId }
        }
    }

    private fun resource(uid: String, etag: String): RemoteResource = RemoteResource(
        url = Url("https://dav.example.com/cal/$uid.ics"),
        etag = etag,
        ical = "BEGIN:VCALENDAR\r\n" +
            "VERSION:2.0\r\n" +
            "PRODID:-//Test//EN\r\n" +
            "BEGIN:VEVENT\r\n" +
            "UID:$uid\r\n" +
            "SUMMARY:Item $uid\r\n" +
            "DTSTART:20260301T090000Z\r\n" +
            "DTEND:20260301T100000Z\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR",
    )

    @Test
    fun `inserts new remote items`() {
        val repository = InMemoryRepository()
        val report = CalDavSyncEngine(repository)
            .applyRemoteState("cal-url", listOf(resource("a", "\"1\"")))
        assertEquals(1, report.inserted)
        assertEquals(0, report.updated)
        assertEquals(0, report.deleted)
        assertEquals(1, repository.items.size)
    }

    @Test
    fun `updates items whose etag changed`() {
        val repository = InMemoryRepository()
        val engine = CalDavSyncEngine(repository)
        engine.applyRemoteState("cal-url", listOf(resource("a", "\"1\"")))
        val report = engine.applyRemoteState("cal-url", listOf(resource("a", "\"2\"")))
        assertEquals(0, report.inserted)
        assertEquals(1, report.updated)
        assertEquals(0, report.deleted)
    }

    @Test
    fun `does not touch items with unchanged etags`() {
        val repository = InMemoryRepository()
        val engine = CalDavSyncEngine(repository)
        engine.applyRemoteState("cal-url", listOf(resource("a", "\"1\"")))
        val report = engine.applyRemoteState("cal-url", listOf(resource("a", "\"1\"")))
        assertEquals(0, report.inserted)
        assertEquals(0, report.updated)
        assertEquals(0, report.deleted)
        assertEquals(1, repository.items.size)
    }

    @Test
    fun `deletes a remotely removed item when the rest of the calendar is intact`() {
        val repository = InMemoryRepository()
        val engine = CalDavSyncEngine(repository)
        engine.applyRemoteState("cal-url", listOf(resource("a", "\"1\""), resource("b", "\"1\"")))
        val report = engine.applyRemoteState("cal-url", listOf(resource("b", "\"1\"")))
        assertEquals(0, report.skippedDeletions)
        assertEquals(1, report.deleted)
        assertEquals(1, repository.items.size)
    }

    @Test
    fun `never deletes everything when the remote report comes back empty`() {
        val repository = InMemoryRepository()
        val engine = CalDavSyncEngine(repository)
        engine.applyRemoteState("cal-url", listOf(resource("a", "\"1\""), resource("b", "\"1\"")))
        val report = engine.applyRemoteState("cal-url", emptyList())
        assertEquals(0, report.deleted)
        assertEquals(2, report.skippedDeletions)
        assertEquals(2, repository.items.size)
    }

    @Test
    fun `skips mass deletions above the safety ratio`() {
        val repository = InMemoryRepository()
        val engine = CalDavSyncEngine(repository)
        engine.applyRemoteState(
            "cal-url",
            (1..10).map { resource("item-$it", "\"1\"") },
        )
        // remote returns only 1 of 10 items => 90% deletion => must be skipped
        val report = engine.applyRemoteState("cal-url", listOf(resource("item-1", "\"1\"")))
        assertEquals(0, report.deleted)
        assertEquals(9, report.skippedDeletions)
        assertEquals(10, repository.items.size)
    }

    @Test
    fun `allows deletion when remote state is plausible`() {
        val repository = InMemoryRepository()
        val engine = CalDavSyncEngine(repository)
        engine.applyRemoteState("cal-url", listOf(resource("a", "\"1\""), resource("b", "\"1\"")))
        // 1 of 2 deleted = 50% => allowed at default limit
        val report = engine.applyRemoteState("cal-url", listOf(resource("a", "\"1\"")))
        assertEquals(1, report.deleted)
        assertEquals(0, report.skippedDeletions)
    }

    @Test
    fun `ignores remote resources that cannot be parsed`() {
        val repository = InMemoryRepository()
        val broken = RemoteResource(Url("https://dav.example.com/cal/x.ics"), "\"x\"", "garbage")
        val report = CalDavSyncEngine(repository).applyRemoteState("cal-url", listOf(broken))
        assertEquals(0, report.inserted)
        assertTrue(repository.items.isEmpty())
    }
}
