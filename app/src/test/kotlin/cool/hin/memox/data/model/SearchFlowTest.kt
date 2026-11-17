package cool.hin.memox.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the request-generation gate and the sanitized diagnostic messages.
 * No Android / Room / coroutine runtime is required.
 */
class SearchFlowTest {

    @Test
    fun firstRequestIsActiveImmediately() {
        val tracker = SearchRequestTracker()
        val id = tracker.newRequest()
        assertTrue(tracker.isActive(id))
        assertEquals(id, tracker.activeRequestId())
    }

    @Test
    fun newRequestSupersedesPreviousGeneration() {
        val tracker = SearchRequestTracker()
        val first = tracker.newRequest()
        val second = tracker.newRequest()

        // A stale in-flight query carrying [first] must be ignored after the newer fetch.
        assertFalse(tracker.isActive(first))
        assertTrue(tracker.isActive(second))
    }

    @Test
    fun onlyLatestOfManyRequestsIsActive() {
        val tracker = SearchRequestTracker()
        val ids = (0 until 10).map { tracker.newRequest() }

        assertEquals(ids.last(), tracker.activeRequestId())
        ids.dropLast(1).forEach { assertFalse(tracker.isActive(it)) }
        assertTrue(tracker.isActive(ids.last()))
    }

    @Test
    fun requestIdsAreStrictlyIncreasing() {
        val tracker = SearchRequestTracker()
        val ids = (0 until 5).map { tracker.newRequest() }
        assertEquals(ids, ids.sorted())
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun logMessagesContainOnlyNonSensitiveMetadata() {
        // Even if a caller passes sensitive-looking text around a search, the emitted
        // diagnostic strings are built from ids/counts only and cannot leak it.
        val secret = "my-secret-keyword-and-label"
        val fetch = SearchLog.fetchMessage(7)
        val result = SearchLog.resultMessage(7, 12)
        val error = SearchLog.errorMessage(7)

        assertEquals("fetch requestId=7", fetch)
        assertEquals("result requestId=7 count=12", result)
        assertEquals("error requestId=7", error)

        listOf(fetch, result, error).forEach { message ->
            assertFalse(message.contains(secret))
            assertFalse(message.contains("keyword"))
            assertFalse(message.contains("label"))
        }
    }

    @Test
    fun errorAndResultMessagesShareRequestIdForCorrelation() {
        val requestId = 99L
        val result = SearchLog.resultMessage(requestId, 3)
        val error = SearchLog.errorMessage(requestId)
        assertTrue(result.contains("requestId=$requestId"))
        assertTrue(error.contains("requestId=$requestId"))
    }
}
