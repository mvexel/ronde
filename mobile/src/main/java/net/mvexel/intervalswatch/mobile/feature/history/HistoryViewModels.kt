package net.mvexel.intervalswatch.mobile.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.mvexel.intervalswatch.mobile.data.activity.ActivityDetail
import net.mvexel.intervalswatch.mobile.data.activity.ActivityRepository
import net.mvexel.intervalswatch.mobile.data.activity.buildDetail
import net.mvexel.intervalswatch.mobile.data.db.ActivityDetailDao
import net.mvexel.intervalswatch.mobile.data.db.ActivityEntity
import net.mvexel.intervalswatch.mobile.data.db.ActivityStreamsDao
import net.mvexel.intervalswatch.mobile.data.db.RondeDatabase
import net.mvexel.intervalswatch.mobile.feature.history.detail.DetailModel
import net.mvexel.intervalswatch.mobile.ui.missedReps
import net.mvexel.intervalswatch.mobile.ui.roundsInRange
import net.mvexel.intervalswatch.shared.StepStatus
import net.mvexel.intervalswatch.shared.Verdict
import net.mvexel.intervalswatch.shared.WorkoutStep

/**
 * What a Ronde row needs to draw its result ring: the planned steps and, once analysed, one verdict per step
 * (`PlanAnalysis.steps.map { it.verdict }`) and whether the run reached it. [verdicts] null draws the plain plan ring.
 */
data class RunResult(val steps: List<WorkoutStep>, val verdicts: List<Verdict?>? = null, val reached: List<Boolean>? = null) {
    val missed: List<Boolean> get() = missedReps(steps, verdicts, reached)
    val rounds: Pair<Int, Int>? get() = roundsInRange(steps, verdicts, reached)
}

/** One History row. [result] is null until the plan-vs-actual result for this activity is known. */
data class HistoryRow(val activity: ActivityEntity, val result: RunResult? = null)

data class HistoryUiState(
    val rows: List<HistoryRow> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
) {
    val activities: List<ActivityEntity> get() = rows.map { it.activity }
}

/** The result ring of one activity from what the phone already holds; never touches the network. */
fun interface RunResultSource {
    suspend fun resultFor(a: ActivityEntity): RunResult?
}

/**
 * [RunResultSource] over the Activity Detail cache (activity_details + activity_streams, written by the detail
 * loader when the activity was opened, or from the watch's FIT). Read-only. An activity whose detail was never
 * loaded has no result, so its row keeps the plain ring.
 */
class CachedRunResults(private val details: ActivityDetailDao, private val streams: ActivityStreamsDao) : RunResultSource {
    constructor(db: RondeDatabase) : this(db.details(), db.streams())

    override suspend fun resultFor(a: ActivityEntity): RunResult? {
        if (!a.isRonde || !a.detailsAvailable) return null
        val row = details.get(a.id)?.takeIf { it.plannedSteps != null } ?: return null
        return runResultOf(buildDetail(a, row, streams.get(a.id)))
    }
}

/** The ring of [d]: verdicts when it has samples to compare, else the plain plan ring; null without a plan. */
fun runResultOf(d: ActivityDetail): RunResult? {
    val steps = d.plannedSteps?.takeIf { it.isNotEmpty() } ?: return null
    val a = DetailModel.analyze(d) ?: return RunResult(steps)
    return RunResult(a.steps.map { it.step }, a.steps.map { it.verdict }, a.steps.map { it.status != StepStatus.NOT_REACHED })
}

/**
 * The History tab: completed activities from Room, newest first. Ronde rows get their result ring from [results]
 * (cached detail only), computed on [compute] after the plain rows are shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val repo: ActivityRepository,
    private val results: RunResultSource = RunResultSource { null },
    compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val refreshing = MutableStateFlow(false)

    /** Analysed results by activity id and `analyzed`, so an unchanged activity is analysed once. Misses are retried. */
    private val memo = HashMap<Pair<String, String?>, RunResult>()

    /** Bumped when History is shown again, to pick up detail cached meanwhile (e.g. an activity just opened). */
    private val recheck = MutableStateFlow(0)

    private val rows = combine(repo.activities(), recheck) { list, _ -> list }.transformLatest { list ->
        emit(list.map { HistoryRow(it, synchronized(memo) { memo[it.id to it.analyzed] }) })
        val filled = list.map { a -> HistoryRow(a, if (a.isRonde) resultOf(a) else null) }
        emit(filled)
    }.flowOn(compute)

    val state: StateFlow<HistoryUiState> = combine(rows, refreshing) { r, busy -> HistoryUiState(r, loading = false, refreshing = busy) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HistoryUiState())

    init {
        load(force = false)
    }

    /** Looks again for cached results of rows that have none yet; no network. */
    fun recheckResults() = recheck.update { it + 1 }

    /** Pull-to-refresh: always asks intervals.icu, past the repository's throttle. */
    fun refresh() = load(force = true)

    private fun load(force: Boolean) {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                runCatching { repo.refresh(force = force) }.onFailure { if (it is CancellationException) throw it }
            } finally {
                refreshing.value = false
            }
        }
    }

    private suspend fun resultOf(a: ActivityEntity): RunResult? {
        val key = a.id to a.analyzed
        synchronized(memo) { memo[key] }?.let { return it }
        val r = try {
            results.resultFor(a)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (r?.verdicts != null) synchronized(memo) { memo[key] = r }
        return r
    }
}
