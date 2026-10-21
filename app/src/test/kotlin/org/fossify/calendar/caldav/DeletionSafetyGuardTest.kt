package org.fossify.calendar.caldav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionSafetyGuardTest {

    private val guard = DeletionSafetyGuard()

    @Test
    fun `allows deleting within the default ratio`() {
        assertTrue(
            guard.deletionsAllowed(
                localItemCount = 10,
                staleItemCount = 5,
                remoteItemCount = 5,
            ),
        )
    }

    @Test
    fun `blocks deletions above the ratio`() {
        assertFalse(
            guard.deletionsAllowed(
                localItemCount = 10,
                staleItemCount = 6,
                remoteItemCount = 4,
            ),
        )
    }

    @Test
    fun `never deletes when remote is empty`() {
        assertFalse(
            guard.deletionsAllowed(
                localItemCount = 10,
                staleItemCount = 10,
                remoteItemCount = 0,
            ),
        )
    }

    @Test
    fun `no stale items is always allowed`() {
        assertTrue(
            guard.deletionsAllowed(
                localItemCount = 10,
                staleItemCount = 0,
                remoteItemCount = 10,
            ),
        )
    }

    @Test
    fun `blocks full wipe even with single local item`() {
        assertFalse(
            guard.deletionsAllowed(
                localItemCount = 1,
                staleItemCount = 1,
                remoteItemCount = 0,
            ),
        )
    }
}
