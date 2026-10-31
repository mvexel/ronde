package net.mvexel.intervalswatch.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import net.mvexel.intervalswatch.wear.ui.Rb
import net.mvexel.intervalswatch.wear.ui.RbType

/** The controls page, one swipe left of the Step page: Pause or Resume, Lap or Skip, and hold-to-end. */
@Composable
internal fun ControlsPage(s: Session) {
    val ctx = LocalContext.current
    val lapIsPlan = s.step?.endsOnLap == true
    Box(Modifier.fillMaxSize().background(Rb.ground), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                if (s.paused) {
                    RoundButton(onClick = { ExerciseService.resume(ctx) }, caption = "resume") { Text("▶") }
                } else {
                    RoundButton(onClick = { ExerciseService.pause(ctx) }, secondary = lapIsPlan, caption = "pause") { Text("❚❚") }
                }
                if (s.next != null && !s.paused) {
                    RoundButton(onClick = { ExerciseService.lap(ctx) }, secondary = !lapIsPlan, caption = if (lapIsPlan) "lap" else "next step") {
                        Text(if (lapIsPlan) "Lap" else "Skip", fontSize = 14.sp, maxLines = 1)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HoldToEnd { ExerciseService.end(ctx) }
                Text("hold to end", style = RbType.label, color = Rb.ink2, maxLines = 1)
            }
        }
    }
}

/** The one button the Step page keeps: Lap on a lap-ended step, where swipe-then-tap is too slow under effort. */
@Composable
internal fun LapButton() {
    val ctx = LocalContext.current
    RoundButton(onClick = { ExerciseService.lap(ctx) }, caption = "lap") { Text("Lap", fontSize = 14.sp, maxLines = 1) }
}
