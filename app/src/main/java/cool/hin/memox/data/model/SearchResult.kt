package cool.hin.memox.data.model

import android.content.ContextWrapper
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.asLiveData
import cool.hin.memox.data.dao.BaseNoteDao
import cool.hin.memox.utils.Event
import cool.hin.memox.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class SearchResult(
    private val context: ContextWrapper,
    private val scope: CoroutineScope,
    var baseNoteDao: BaseNoteDao,
    private val transform: (List<BaseNote>) -> List<Item>,
) {
    private data class SearchParams(
        val keyword: String,
        val folder: Folder,
        val label: String?,
        val from: Long?,
        val to: Long?,
        val requestId: Long,
    )

    private val tracker = SearchRequestTracker()
    private val searchParams = MutableStateFlow<SearchParams?>(null)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: LiveData<Boolean> =
        _isLoading.asLiveData(scope.coroutineContext + Dispatchers.Main)

    /** Synchronous snapshot so consumers can tell a transient loading list from a real result. */
    val isLoadingNow: Boolean get() = _isLoading.value

    private val errorEvents = MutableLiveData<Event<SearchFailure>?>()

    /** Errors are delivered through a LiveData so the UI can observe them with its own lifecycle. */
    val errors: LiveData<Event<SearchFailure>?> = errorEvents

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val queryFlow: Flow<List<Item>> =
        searchParams
            .debounce(DEBOUNCE_MS)
            .flatMapLatest { params ->
                if (params == null) {
                    flowOf(emptyList())
                } else {
                    val requestId = params.requestId
                    baseNoteDao
                        .getBaseNotesByKeyword(params.keyword, params.folder, params.label)
                        .map { notes ->
                            val filtered =
                                notes.filter { note ->
                                    cool.hin.memox.presentation.viewmodel.matchesDateRange(
                                        note.timestamp,
                                        params.from,
                                        params.to,
                                    )
                                }
                            if (tracker.isActive(requestId)) {
                                logSafely(SearchLog.resultMessage(requestId, filtered.size))
                            }
                            filtered
                        }
                        .map { transform(it) }
                        // A superseded request may still be mid-flight during the debounce
                        // window. Only the newest generation may publish results or loading.
                        .filter { tracker.isActive(requestId) }
                        .onEach { if (tracker.isActive(requestId)) _isLoading.value = false }
                        .catch { throwable ->
                            if (tracker.isActive(requestId)) {
                                _isLoading.value = false
                                logSafely(SearchLog.errorMessage(requestId), throwable)
                            }
                            // Tag the failure with its generation: a newer fetch can advance the
                            // tracker while logging runs, so the UI boundary rejects stale
                            // failures instead of showing them on the current page.
                            errorEvents.postValue(Event(SearchFailure(requestId, throwable)))
                            if (tracker.isActive(requestId)) emit(emptyList())
                        }
                        .onCompletion { if (tracker.isActive(requestId)) _isLoading.value = false }
                }
            }
            .flowOn(Dispatchers.IO)

    val results: LiveData<List<Item>> =
        queryFlow
            // With no active observers, stop the upstream Room query immediately. When the
            // screen comes back, resubscribing restarts the still-current query.
            .asLiveData(scope.coroutineContext + Dispatchers.Main, timeoutInMs = 0)

    fun fetch(
        keyword: String,
        folder: Folder,
        label: String?,
        from: Long? = null,
        to: Long? = null,
    ) {
        val requestId = tracker.newRequest()
        // Drop any error left over from a previous request so a freshly opened page does not
        // surface a stale failure.
        errorEvents.postValue(null)
        // Publish the new request before any diagnostic work: logging can never block a search,
        // and the stale result/loading path is gated by the generation captured above.
        _isLoading.value = true
        searchParams.value = SearchParams(keyword, folder, label, from, to, requestId)
        // Logging touches external storage; run it off the main thread and isolate failures.
        scope.launch(Dispatchers.IO) { logSafely(SearchLog.fetchMessage(requestId)) }
    }

    fun observe(
        owner: LifecycleOwner,
        observer: Observer<List<Item>>,
    ) {
        results.observe(owner, observer)
    }

    val value: List<Item>?
        get() = results.value

    /** Whether [requestId] is still the newest generation; used to reject stale error events. */
    fun isCurrentRequest(requestId: Long): Boolean = tracker.isActive(requestId)

    private fun logSafely(message: String, throwable: Throwable? = null) {
        try {
            context.log(TAG, message, throwable = throwable)
        } catch (_: Throwable) {
            // Diagnostics must never break search (external storage may be unavailable).
        }
    }

    companion object {
        private const val TAG = "SearchResult"
        private const val DEBOUNCE_MS = 100L
    }
}

/**
 * Monotonic request generation. [newRequest] runs on the UI thread; [isActive] is read from the
 * IO dispatcher, so the active id is volatile and generation updates are serialized.
 */
class SearchRequestTracker {
    private var nextId = 0L

    @Volatile
    private var activeId: Long? = null

    @Synchronized
    fun newRequest(): Long {
        val id = nextId++
        activeId = id
        return id
    }

    fun isActive(requestId: Long): Boolean = activeId == requestId

    fun activeRequestId(): Long? = activeId
}

/** A query failure tagged with the generation that produced it. */
data class SearchFailure(val requestId: Long, val error: Throwable)

/** Diagnostic messages deliberately carry only non-sensitive metadata. */
object SearchLog {
    fun fetchMessage(requestId: Long) = "fetch requestId=$requestId"

    fun resultMessage(requestId: Long, count: Int) = "result requestId=$requestId count=$count"

    fun errorMessage(requestId: Long) = "error requestId=$requestId"
}

val SearchResult?.isEmpty: Boolean
    get() = this?.value?.isEmpty() == true
