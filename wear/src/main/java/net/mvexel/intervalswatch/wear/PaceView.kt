package net.mvexel.intervalswatch.wear

import android.content.Context
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import net.mvexel.intervalswatch.shared.Sport

/** Which pace the hero value shows: live ("now") or the step average ("avg"). Alerts always use live. */
internal enum class PaceView(val label: String) { NOW("now"), AVG("avg") }

/** What to show where: the hero and the other value, given the chosen view. Average falls back to live until it exists. */
internal class PaceChoice(val hero: Double?, val heroView: PaceView, val other: Double?, val otherView: PaceView)

internal fun paceChoice(live: Double?, avg: Double?, view: PaceView): PaceChoice =
    if (view == PaceView.AVG && avg != null) PaceChoice(avg, PaceView.AVG, live, PaceView.NOW)
    else PaceChoice(live, PaceView.NOW, avg, PaceView.AVG)

/** The one place the toggle gesture is defined; swap the body for long-press, crown, etc. */
internal fun Modifier.paceToggleGesture(onToggle: () -> Unit): Modifier =
    pointerInput(onToggle) { detectTapGestures(onTap = { onToggle() }) }

/** Remembered choice per sport, so it survives workouts. */
internal class PaceViewStore(context: Context) {
    private val prefs = context.getSharedPreferences("pace_view", Context.MODE_PRIVATE)
    fun get(sport: Sport): PaceView = if (prefs.getBoolean(sport.name, false)) PaceView.AVG else PaceView.NOW
    fun set(sport: Sport, v: PaceView) { prefs.edit().putBoolean(sport.name, v == PaceView.AVG).apply() }
}

/** The stored view for [sport] plus a toggle that flips and persists it. */
@Composable
internal fun rememberPaceView(ctx: Context, sport: Sport): Pair<PaceView, () -> Unit> {
    val store = remember { PaceViewStore(ctx) }
    var view by remember(sport) { mutableStateOf(store.get(sport)) }
    return view to {
        view = if (view == PaceView.AVG) PaceView.NOW else PaceView.AVG
        store.set(sport, view)
    }
}
