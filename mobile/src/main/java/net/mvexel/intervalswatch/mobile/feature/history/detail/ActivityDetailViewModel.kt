package net.mvexel.intervalswatch.mobile.feature.history.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mvexel.intervalswatch.mobile.data.activity.ActivityDetail
import net.mvexel.intervalswatch.mobile.data.activity.ActivityDetailLoader
import net.mvexel.intervalswatch.mobile.data.activity.DetailLoad

/**
 * [content] is what to draw (fresh, or cached when the refresh failed). [error] is set when the last load failed;
 * with [content] the screen shows it as a banner over the cached page, without it as the whole page.
 */
data class ActivityDetailUiState(
    val loading: Boolean = true,
    val content: DetailContent? = null,
    val error: String? = null,
)

/** One activity's detail page. Plan-vs-actual and the chart series are computed on [compute], not on main. */
class ActivityDetailViewModel(
    private val loader: ActivityDetailLoader,
    private val id: String,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mutable = MutableStateFlow(ActivityDetailUiState())
    val state: StateFlow<ActivityDetailUiState> = mutable.asStateFlow()

    private var job: Job? = null
    /** The last detail turned into content, so a cached-then-fresh pair with equal data is analysed once. */
    private var built: Pair<ActivityDetail, DetailContent>? = null

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        job?.cancel()
        mutable.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            loader.load(id)
                .catch { e -> emit(DetailLoad.Failed(e.message ?: "Could not load this activity", null)) }
                .collectLatest { l ->
                    when (l) {
                        DetailLoad.Loading -> mutable.update { it.copy(loading = true) }
                        is DetailLoad.Ready -> mutable.value = ActivityDetailUiState(loading = false, content = contentFor(l.detail))
                        is DetailLoad.Failed -> {
                            val cached = l.cached?.let { contentFor(it) } ?: mutable.value.content
                            mutable.value = ActivityDetailUiState(loading = false, content = cached, error = l.message)
                        }
                    }
                }
        }
    }

    private suspend fun contentFor(d: ActivityDetail): DetailContent {
        built?.let { (detail, content) -> if (detail == d) return content }
        return withContext(compute) { DetailModel.build(d) }.also { built = d to it }
    }
}
