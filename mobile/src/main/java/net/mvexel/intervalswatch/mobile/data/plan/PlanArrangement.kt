package net.mvexel.intervalswatch.mobile.data.plan

import net.mvexel.intervalswatch.mobile.data.db.PlannedWorkoutPrefsEntity
import net.mvexel.intervalswatch.shared.Workout

/** One row of the Plan tab: a fetched workout and the user's prefs for it. */
data class PlanItem(val workout: Workout, val pinned: Boolean = false, val hidden: Boolean = false)

data class PlanArrangement(val visible: List<PlanItem>, val hidden: List<PlanItem>)

/**
 * Applies [prefs] to the fetched [workouts]: pinned first (by sortOrder), then the rest in fetch order (by date);
 * hidden workouts go to [PlanArrangement.hidden] in fetch order. Prefs for workouts not in the list are ignored.
 */
fun arrange(workouts: List<Workout>, prefs: List<PlannedWorkoutPrefsEntity>): PlanArrangement {
    val byId = prefs.associateBy { it.eventId }
    val items = workouts.map { w -> byId[w.id].let { p -> PlanItem(w, pinned = p?.pinned == true, hidden = p?.hidden == true) } }
    val (hidden, shown) = items.partition { it.hidden }
    val pinned = shown.filter { it.pinned }.sortedBy { byId[it.workout.id]?.sortOrder ?: 0 } // stable: ties keep date order
    return PlanArrangement(pinned + shown.filterNot { it.pinned }, hidden)
}

/** What goes to the watch: everything the user has not hidden, in fetch order. */
fun visibleForWatch(workouts: List<Workout>, hiddenIds: Collection<Long>): List<Workout> {
    if (hiddenIds.isEmpty()) return workouts
    val hidden = hiddenIds.toSet()
    return workouts.filterNot { it.id in hidden }
}
