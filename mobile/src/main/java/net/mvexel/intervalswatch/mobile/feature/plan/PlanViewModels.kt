package net.mvexel.intervalswatch.mobile.feature.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.mvexel.intervalswatch.mobile.data.db.PlannedWorkoutPrefsDao
import net.mvexel.intervalswatch.mobile.data.db.setHidden
import net.mvexel.intervalswatch.mobile.data.db.setPinned
import net.mvexel.intervalswatch.mobile.data.plan.PlanItem
import net.mvexel.intervalswatch.mobile.data.plan.arrange
import net.mvexel.intervalswatch.mobile.data.session.Session

data class PlanUiState(
    val visible: List<PlanItem> = emptyList(),
    val hidden: List<PlanItem> = emptyList(),
    val busy: Boolean = false,
    /** Connected watch names; empty = no watch paired (Ronde still works). */
    val watches: List<String> = emptyList(),
    val lastSynced: String? = null,
)

/** Pin and hide, shared by the list and the detail screen. Hiding or unhiding re-sends the list to the watch. */
internal class PlanPrefsActions(private val prefs: PlannedWorkoutPrefsDao, private val session: Session) {
    suspend fun setPinned(id: Long, pinned: Boolean) = prefs.setPinned(id, pinned)

    suspend fun setHidden(id: Long, hidden: Boolean) {
        prefs.setHidden(id, hidden)
        session.pushToWatch()
    }
}

/** The Plan tab: upcoming workouts from the last sync with the user's pin/hide choices applied. */
class PlanViewModel(private val session: Session, prefs: PlannedWorkoutPrefsDao) : ViewModel() {
    private val actions = PlanPrefsActions(prefs, session)

    val state: StateFlow<PlanUiState> = combine(session.state, prefs.observeAll()) { st, p ->
        val a = arrange(st.workouts, p)
        PlanUiState(a.visible, a.hidden, st.busy, st.watches, st.lastSynced)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlanUiState())

    fun togglePinned(item: PlanItem) {
        viewModelScope.launch { actions.setPinned(item.workout.id, !item.pinned) }
    }

    fun toggleHidden(item: PlanItem) {
        viewModelScope.launch { actions.setHidden(item.workout.id, !item.hidden) }
    }

    fun sync() = session.sync()
}

data class PlannedWorkoutDetailUiState(val item: PlanItem? = null, val loaded: Boolean = false)

/** One planned workout by intervals.icu event id, from the last sync. */
class PlannedWorkoutDetailViewModel(private val eventId: Long, session: Session, prefs: PlannedWorkoutPrefsDao) : ViewModel() {
    private val actions = PlanPrefsActions(prefs, session)

    val state: StateFlow<PlannedWorkoutDetailUiState> = combine(session.state, prefs.observeAll()) { st, p ->
        val a = arrange(st.workouts, p)
        PlannedWorkoutDetailUiState((a.visible + a.hidden).firstOrNull { it.workout.id == eventId }, loaded = st.workoutsLoaded)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlannedWorkoutDetailUiState())

    fun togglePinned() {
        val item = state.value.item ?: return
        viewModelScope.launch { actions.setPinned(eventId, !item.pinned) }
    }

    fun toggleHidden() {
        val item = state.value.item ?: return
        viewModelScope.launch { actions.setHidden(eventId, !item.hidden) }
    }
}
