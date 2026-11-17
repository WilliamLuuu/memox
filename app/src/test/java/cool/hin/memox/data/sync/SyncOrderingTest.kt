package cool.hin.memox.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncOrderingTest {

    @Test
    fun higherVersionWinsRegardlessOfClock() {
        // Local has a newer version but an older wall clock: version must decide.
        assertTrue(SyncOrdering.isLocalNewer(5, 100, 4, 10_000))
        assertFalse(SyncOrdering.isRemoteNewer(5, 100, 4, 10_000))
    }

    @Test
    fun lowerVersionNeverWinsEvenWithNewerTimestamp() {
        assertFalse(SyncOrdering.isLocalNewer(4, 10_000, 5, 100))
        assertTrue(SyncOrdering.isRemoteNewer(4, 10_000, 5, 100))
    }

    @Test
    fun equalVersionFallsBackToModifiedTime() {
        assertTrue(SyncOrdering.isLocalNewer(5, 200, 5, 100))
        assertTrue(SyncOrdering.isRemoteNewer(5, 100, 5, 200))
    }

    @Test
    fun identicalRecordsAreNotNewerEitherWay() {
        assertFalse(SyncOrdering.isLocalNewer(5, 100, 5, 100))
        assertFalse(SyncOrdering.isRemoteNewer(5, 100, 5, 100))
    }
}
