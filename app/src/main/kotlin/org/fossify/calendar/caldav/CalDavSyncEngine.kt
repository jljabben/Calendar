package org.fossify.calendar.caldav

private const val NEW_ITEM_ID = -1L

/**
 * Storage abstraction for the sync engine, so the engine stays pure Kotlin
 * and unit-testable without Android.
 */
interface SyncRepository {
    fun getItemsForCalendar(calendarUrl: String): List<StoredItem>

    fun upsertItem(item: StoredItem)

    fun deleteItem(itemId: Long)

    data class StoredItem(
        val id: Long,
        val uid: String,
        val etag: String?,
        val mapped: SyncedItem? = null,
    )
}

data class SyncReport(
    val calendarUrl: String,
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
    val skippedDeletions: Int,
)

/**
 * Pull-sync engine for one calendar collection.
 *
 * Safety rules to never wipe data on flaky server responses:
 * - Remote errors abort the sync; local state is untouched.
 * - Empty or unparseable remote results never trigger deletions.
 * - If the remote report is valid but would delete an implausible share
 *   of known local items, deletions are skipped for this run and
 *   reported via [SyncReport.skippedDeletions] ([DeletionSafetyGuard]).
 */
class CalDavSyncEngine(
    private val repository: SyncRepository,
    private val safetyGuard: DeletionSafetyGuard = DeletionSafetyGuard(),
) {
    /**
     * Applies a fetched remote state to the local repository.
     */
    fun applyRemoteState(calendarUrl: String, remoteResources: List<RemoteResource>): SyncReport {
        val remoteByUid = remoteResources.mapNotNull { resource ->
            IcsSyncMapper.toSyncedItem(resource)?.let { it.uid to resource }
        }.toMap()

        val localItems = repository.getItemsForCalendar(calendarUrl)
        val upsertCounts = upsertChangedItems(localItems, remoteByUid)

        val staleLocalIds = localItems.filter { !remoteByUid.containsKey(it.uid) }
        val deletionCounts = applyDeletions(localItems, remoteByUid, staleLocalIds)

        return SyncReport(
            calendarUrl = calendarUrl,
            inserted = upsertCounts.first,
            updated = upsertCounts.second,
            deleted = deletionCounts.first,
            skippedDeletions = deletionCounts.second,
        )
    }

    private fun upsertChangedItems(
        localItems: List<SyncRepository.StoredItem>,
        remoteByUid: Map<String, RemoteResource>,
    ): Pair<Int, Int> {
        val localByUid = localItems.associateBy { it.uid }
        var inserted = 0
        var updated = 0
        for ((uid, resource) in remoteByUid) {
            val local = localByUid[uid]
            val mapped = IcsSyncMapper.toSyncedItem(resource) ?: continue
            if (local == null) {
                repository.upsertItem(
                    SyncRepository.StoredItem(NEW_ITEM_ID, uid, mapped.etag, mapped),
                )
                inserted++
            } else if (local.etag != mapped.etag) {
                repository.upsertItem(
                    SyncRepository.StoredItem(local.id, uid, mapped.etag, mapped),
                )
                updated++
            }
        }
        return inserted to updated
    }

    private fun applyDeletions(
        localItems: List<SyncRepository.StoredItem>,
        remoteByUid: Map<String, RemoteResource>,
        staleLocalIds: List<SyncRepository.StoredItem>,
    ): Pair<Int, Int> {
        if (staleLocalIds.isEmpty()) {
            return 0 to 0
        }
        val deletionsAllowed = safetyGuard.deletionsAllowed(
            localItemCount = localItems.size,
            staleItemCount = staleLocalIds.size,
            remoteItemCount = remoteByUid.size,
        )
        return if (deletionsAllowed) {
            staleLocalIds.forEach { repository.deleteItem(it.id) }
            staleLocalIds.size to 0
        } else {
            0 to staleLocalIds.size
        }
    }
}
