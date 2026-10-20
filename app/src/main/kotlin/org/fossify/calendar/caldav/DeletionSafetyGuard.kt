package org.fossify.calendar.caldav

/**
 * Central deletion guard shared by the sync engine and every caller that
 * removes locally stored items after a remote report. Its purpose is to
 * never wipe a calendar because of a flaky, truncated or misunderstood
 * server response:
 *
 * - Empty remote results never delete anything.
 * - Deletions are only applied if the remote state looks plausible, i.e.
 *   the share of stale local items does not exceed [maxDeletionRatio].
 */
class DeletionSafetyGuard(
    private val maxDeletionRatio: Double = DEFAULT_MAX_DELETION_RATIO,
) {
    fun deletionsAllowed(
        localItemCount: Int,
        staleItemCount: Int,
        remoteItemCount: Int,
    ): Boolean {
        if (staleItemCount == 0 || localItemCount == 0) {
            return true
        }
        if (remoteItemCount == 0) {
            return false
        }
        val ratio = staleItemCount.toDouble() / localItemCount.toDouble()
        return ratio <= maxDeletionRatio
    }

    companion object {
        const val DEFAULT_MAX_DELETION_RATIO = 0.5
    }
}
