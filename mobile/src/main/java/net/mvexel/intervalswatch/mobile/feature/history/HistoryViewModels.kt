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
import net.mvexel.intervalswatch.shared.Verdict
import net.mvexel.intervalswatch.shared.WorkoutStep

/**
 * What a Ronde row needs to draw its result ring: the planned steps and, once analysed, one verdict per step
 * (`PlanAnalysis.steps.map { it.verdict }`). [verdicts] null draws the plain plan ring.
 */
data class RunResult(val steps: List<WorkoutStep>, val verdicts: List<Verdict?>? = null)

/** One History row. [result] is null until the plan-vs-actual result for this activity is known. */
data class HistoryRow(val activity: ActivityEntity, val result: RunResult? = null)

data class HistoryUiState(
    val rows: List<HistoryRow> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
) {
    val activities: List<ActivityEntity> get() = rows.map { it.activity }
}

/** The History tab: completed activities from Room, newest first. */
class HistoryViewModel(private val repo: ActivityRepository) : ViewModel() {
    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<HistoryUiState> = combine(repo.activities(), refreshing) { list, r ->
        // TODO(lead): feed RunResult from the cached PlanVsActual analysis (paired event steps + verdicts) for
        //  Ronde activities. Until then Ronde rows show an empty ring and no "n of m rounds in range" line.
        HistoryUiState(list.map { HistoryRow(it) }, loading = false, refreshing = r)
    }
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
