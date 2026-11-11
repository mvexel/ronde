package net.mvexel.intervalswatch.mobile.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.mvexel.intervalswatch.mobile.data.activity.ActivityRepository
import net.mvexel.intervalswatch.mobile.data.db.ActivityEntity
import net.mvexel.intervalswatch.mobile.data.db.ActivityStreams

data class HistoryUiState(
    val activities: List<ActivityEntity> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
)

/** The History tab: completed activities from Room, newest first. */
class HistoryViewModel(private val repo: ActivityRepository) : ViewModel() {
    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<HistoryUiState> = combine(repo.activities(), refreshing) { list, r -> HistoryUiState(list, loading = false, refreshing = r) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HistoryUiState())

    init {
        refresh()
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                runCatching { repo.refresh() }
            } finally {
                refreshing.value = false
            }
        }
    }
}

data class ActivityDetailUiState(
    val activity: ActivityEntity? = null,
    val streams: ActivityStreams? = null,
    val loaded: Boolean = false,
)

/** One activity by intervals.icu id, with its streams when stored. */
class ActivityDetailViewModel(id: String, repo: ActivityRepository) : ViewModel() {
    val state: StateFlow<ActivityDetailUiState> = combine(repo.activity(id), repo.streams(id)) { a, s -> ActivityDetailUiState(a, s, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ActivityDetailUiState())
}
