package org.fossify.calendar.caldav

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
    )
}

data class SyncReport(
    val calendarUrl: String,
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
    val skippedDeletions: Int,
)

class SafetyLimits(
    val maxDeletionRatio: Double = 0.5,
)

/**
 * Pull-sync engine for one calendar collection.
 *
 * Safety rules to never wipe data on flaky server responses:
 * - Remote errors abort the sync; local state is untouched.
 * - Empty or unparseable remote results never trigger deletions.
 * - If the remote report is valid but would delete more than
 *   [SafetyLimits.maxDeletionRatio] of known local items, deletions are
 *   skipped for this run and reported via [SyncReport.skippedDeletions].
 */
class CalDavSyncEngine(
    private val repository: SyncRepository,
    private val safetyLimits: SafetyLimits = SafetyLimits(),
) {
    /**
     * Applies a fetched remote state to the local repository.
     */
    fun applyRemoteState(calendarUrl: String, remoteResources: List<RemoteResource>): SyncReport {
        val remoteByUid = mutableMapOf<String, RemoteResource>()
        for (resource in remoteResources) {
            val item = IcsSyncMapper.toSyncedItem(resource) ?: continue
            remoteByUid[item.uid] = resource
        }

        val localItems = repository.getItemsForCalendar(calendarUrl)
        var inserted = 0
        var updated = 0
        var deleted = 0
        var skippedDeletions = 0

        val localByUid = localItems.associateBy { it.uid }
        val staleLocalIds = mutableListOf<SyncRepository.StoredItem>()

        for ((uid, resource) in remoteByUid) {
            val local = localByUid[uid]
            val mapped = IcsSyncMapper.toSyncedItem(resource)!!
            if (local == null) {
                repository.upsertItem(SyncRepository.StoredItem(-1, uid, mapped.etag))
                inserted++
            } else if (local.etag != mapped.etag) {
                repository.upsertItem(SyncRepository.StoredItem(local.id, uid, mapped.etag))
                updated++
            }
        }

        for (local in localItems) {
            if (!remoteByUid.containsKey(local.uid)) {
                staleLocalIds.add(local)
            }
        }

        val deletionsAllowed = staleLocalIds.isNotEmpty() &&
            remoteByUid.isNotEmpty() &&
            remoteResources.isNotEmpty() &&
            staleLocalIds.size.toDouble() / localItems.size.toDouble() <=
            safetyLimits.maxDeletionRatio

        if (staleLocalIds.isNotEmpty() && !deletionsAllowed) {
            skippedDeletions = staleLocalIds.size
        } else {
            staleLocalIds.forEach { repository.deleteItem(it.id) }
            deleted = staleLocalIds.size
        }

        return SyncReport(calendarUrl, inserted, updated, deleted, skippedDeletions)
    }
}
