package net.mvexel.intervalswatch.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import net.mvexel.intervalswatch.mobile.app.MainTabsScaffold
import net.mvexel.intervalswatch.mobile.app.Tab
import net.mvexel.intervalswatch.mobile.data.db.ActivityEntity
import net.mvexel.intervalswatch.mobile.data.plan.PlanItem
import net.mvexel.intervalswatch.mobile.feature.history.ActivityDetailScreen
import net.mvexel.intervalswatch.mobile.feature.history.ActivityDetailUiState
import net.mvexel.intervalswatch.mobile.feature.history.HistoryScreen
import net.mvexel.intervalswatch.mobile.feature.history.HistoryUiState
import net.mvexel.intervalswatch.mobile.feature.login.LoginScreen
import net.mvexel.intervalswatch.mobile.feature.login.LoginUiState
import net.mvexel.intervalswatch.mobile.feature.plan.PlanScreen
import net.mvexel.intervalswatch.mobile.feature.plan.PlanUiState
import net.mvexel.intervalswatch.mobile.feature.plan.PlannedWorkoutDetailScreen
import net.mvexel.intervalswatch.mobile.feature.plan.PlannedWorkoutDetailUiState
import net.mvexel.intervalswatch.mobile.feature.settings.SettingsScreen
import net.mvexel.intervalswatch.mobile.feature.settings.SettingsUiState
import net.mvexel.intervalswatch.mobile.feature.watch.WatchScreen
import net.mvexel.intervalswatch.mobile.feature.watch.WatchScreensScreen
import net.mvexel.intervalswatch.mobile.feature.watch.WatchUiState
import net.mvexel.intervalswatch.mobile.feature.history.HistoryFilter
import net.mvexel.intervalswatch.mobile.feature.history.HistoryRow
import net.mvexel.intervalswatch.mobile.feature.history.RunResult
import net.mvexel.intervalswatch.mobile.ui.EmptyState
import net.mvexel.intervalswatch.mobile.ui.ErrorState
import net.mvexel.intervalswatch.mobile.ui.ResultRing
import net.mvexel.intervalswatch.mobile.ui.RingDisc
import net.mvexel.intervalswatch.mobile.ui.RondeBadge
import net.mvexel.intervalswatch.mobile.ui.RondeFilterChip
import net.mvexel.intervalswatch.mobile.ui.RondeTheme
import net.mvexel.intervalswatch.mobile.ui.RondeType
import net.mvexel.intervalswatch.mobile.ui.RunbumTheme
import net.mvexel.intervalswatch.mobile.ui.Slab
import net.mvexel.intervalswatch.shared.Samples
import net.mvexel.intervalswatch.shared.ScreenSettings
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.Verdict
import net.mvexel.intervalswatch.shared.WorkoutStep
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

/** Run `./gradlew :mobile:recordPaparazziDebug`; PNGs land in mobile/src/test/snapshots/images. */
class PhoneScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    private fun tab(t: Tab, dark: Boolean = false, content: @Composable () -> Unit) = paparazzi.snapshot {
        RondeTheme(dark = dark) { MainTabsScaffold(selected = t, onSelect = {}, content = content) }
    }

    private val today = LocalDate.of(2026, 11, 3)

    private val plan = PlanUiState(
        visible = Samples.workouts.mapIndexed { i, w -> PlanItem(w, pinned = i == 1) }.let { listOf(it[1], it[0]) + it.drop(2) },
        watches = listOf("Pixel Watch 3"), lastSynced = "14:02",
    )

    /** Verdicts parallel to the run's steps: work reps alternate in range, fast, in range, slow... */
    private fun verdicts(steps: List<WorkoutStep>, missed: Set<Int>): List<Verdict?> {
        var rep = 0
        return steps.map { s ->
            if (s.kind != StepKind.ACTIVE) null
            else (if (rep++ in missed) (if (rep % 2 == 0) Verdict.FAST else Verdict.SLOW) else Verdict.IN)
        }
    }

    private val historyRows = listOf(
        HistoryRow(
            ActivityEntity("i3", "Threshold 3x10", "Run", "2026-11-03T07:12:00", 8_700.0, 2_700, 2_760, avgSpeed = 3.22, avgHr = 146, isRonde = true, fetchedAt = 0),
            RunResult(Samples.run.steps, verdicts(Samples.run.steps, setOf(1))),
        ),
        HistoryRow(
            ActivityEntity("i2", "Commute", "Ride", "2026-11-02T07:55:00", 14_200.0, 2_050, 2_200, avgSpeed = 6.9, avgHr = 121, fetchedAt = 0),
        ),
        HistoryRow(
            ActivityEntity("i1", "Easy run", "Run", "2026-10-29T18:30:00", 7_320.0, 2_406, 2_450, avgSpeed = 3.04, avgHr = 139, isRonde = true, fetchedAt = 0),
            RunResult(Samples.run.steps, null),
        ),
        HistoryRow(
            ActivityEntity("i0", "Morning ride", "VirtualRide", "2026-10-27T09:15:00", 42_100.0, 5_320, 5_400, avgSpeed = 7.9, avgHr = 132, avgPower = 186, fetchedAt = 0),
        ),
        HistoryRow(
            ActivityEntity("i-1", "Long walk", "Walk", "2026-10-26T10:00:00", 6_100.0, 4_200, 4_300, avgSpeed = 1.45, fetchedAt = 0),
        ),
    )

    @Test fun planLight() = tab(Tab.PLAN) { PlanScreen(plan, today = today) }

    @Test fun planDark() = tab(Tab.PLAN, dark = true) { PlanScreen(plan, today = today) }

    @Test fun planNoWatch() = tab(Tab.PLAN) { PlanScreen(plan.copy(watches = emptyList()), today = LocalDate.of(2026, 11, 1)) }

    @Test fun historyLight() = tab(Tab.HISTORY) { HistoryScreen(HistoryUiState(historyRows, loading = false), today = today) }

    @Test fun historyDark() = tab(Tab.HISTORY, dark = true) { HistoryScreen(HistoryUiState(historyRows, loading = false), today = today) }

    @Test fun historyRondeOnly() = tab(Tab.HISTORY) {
        HistoryScreen(HistoryUiState(historyRows, loading = false), initialFilter = HistoryFilter.RONDE, today = today)
    }

    @Test fun watchDark() = tab(Tab.WATCH, dark = true) {
        WatchScreen(WatchUiState(watches = listOf("Pixel Watch 3"), lastSynced = "07:12", status = "Sent 3 workout(s) to the watch"), recordingItems, onSync = {})
    }

    @Test fun watchNone() = tab(Tab.WATCH) { WatchScreen(WatchUiState(lastSynced = "07:12"), emptyList(), onSync = {}) }

    @Test fun settingsDark() = tab(Tab.SETTINGS, dark = true) { SettingsScreen(SettingsUiState(settings = oauth)) }

    @Test fun loginDark() = paparazzi.snapshot { RondeTheme(dark = true) { LoginScreen(LoginUiState()) } }

    @Test fun ringsLight() = paparazzi.snapshot { RondeTheme { Rings() } }

    @Test fun ringsDark() = paparazzi.snapshot { RondeTheme(dark = true) { Rings() } }

    @Composable
    private fun Rings() = Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val steps = Samples.run.steps
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ResultRing(steps, null, size = 176.dp)
                ResultRing(steps, verdicts(steps, setOf(1, 3)), size = 176.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ResultRing(steps, verdicts(steps, setOf(1)), size = 48.dp)
                RingDisc(steps, size = 60.dp) { Text("1", style = RondeType.ringNumber, color = Color.White) }
                RingDisc(emptyList(), size = 48.dp)
                RondeBadge()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RondeFilterChip("All", true) {}
                RondeFilterChip("Run", false) {}
            }
            // Condensed numeral face vs system Roboto Bold at the same size.
            Text("8.70 1:28:40 History", style = RondeType.numberL, color = MaterialTheme.colorScheme.onBackground)
            Text("8.70 1:28:40 History", style = RondeType.numberL.copy(fontFamily = null), color = MaterialTheme.colorScheme.onBackground)
            Slab { Text("Slab", style = RondeType.numberL, color = RondeTheme.colors.onSlab) }
            EmptyState("No activities yet", "Workouts you finish will show up here.")
            ErrorState("Could not reach intervals.icu.")
        }
    }

    private val recordingItems = listOf(
        RecordingItem("1_a.fit", "Tempo run", 1_761_800_000, 48_000, UploadState.UPLOADED),
        RecordingItem("2_a.fit", "Open ride", 1_761_700_000, 120_000, UploadState.PENDING),
        RecordingItem("3_a.fit", "Hills", 1_761_600_000, 30_000, UploadState.FAILED, "rejected by intervals.icu"),
    )

    private val oauth = Settings(token = "x", authType = AuthType.OAUTH, athleteId = "i12345", athleteName = "Martijn van Exel")

    @Test fun synced() = tab(Tab.PLAN) {
        PlanScreen(PlanUiState(visible = Samples.workouts.mapIndexed { i, w -> PlanItem(w, pinned = i == 0) }, watches = listOf("Pixel Watch 3")), today = today)
    }

    @Test fun planWithHidden() = tab(Tab.PLAN) {
        val (h, v) = Samples.workouts.partition { it.id == Samples.workouts.last().id }
        PlanScreen(PlanUiState(visible = v.map { PlanItem(it) }, hidden = h.map { PlanItem(it, hidden = true) }, showHidden = true, watches = listOf("Pixel Watch 3")), today = today)
    }

    @Test fun plannedWorkoutDetail() = tab(Tab.PLAN) {
        PlannedWorkoutDetailScreen(PlannedWorkoutDetailUiState(PlanItem(Samples.workouts.first()), loaded = true), onBack = {})
    }

    @Test fun watch() = tab(Tab.WATCH) {
        WatchScreen(WatchUiState(watches = listOf("Pixel Watch 3"), lastSynced = "07:12", status = "Sent 3 workout(s) to the watch"), emptyList(), onSync = {})
    }

    @Test fun recordings() = tab(Tab.WATCH) {
        WatchScreen(
            WatchUiState(watches = listOf("Pixel Watch 3")),
            recordingItems,
            onSync = {},
        )
    }

    @Test fun historyEmpty() = tab(Tab.HISTORY) { HistoryScreen(HistoryUiState(loading = false)) }

    @Test fun history() = tab(Tab.HISTORY) {
        HistoryScreen(
            HistoryUiState(
                listOf(
                    HistoryRow(ActivityEntity("i2", "Tempo run", "Run", "2026-11-09T07:00:00", 10_000.0, 3_000, 3_100, isRonde = true, fetchedAt = 0)),
                    HistoryRow(ActivityEntity("i1", "Lunch ride", "Ride", "2026-11-08T12:00:00", 40_000.0, 5_400, 6_000, fetchedAt = 0)),
                ),
                loading = false,
            ),
            today = LocalDate.of(2026, 11, 10),
        )
    }

    @Test fun activityDetail() = tab(Tab.HISTORY) {
        ActivityDetailScreen(
            ActivityDetailUiState(
                ActivityEntity("i2", "Tempo run", "Run", "2026-11-09T07:00:00", 10_000.0, 3_000, 3_100, avgSpeed = 3.33, avgHr = 152, load = 64, isRonde = true, pairedEventId = 7, fetchedAt = 0),
                loaded = true,
            ),
            onBack = {},
        )
    }

    @Test fun setup() = paparazzi.snapshot { RunbumTheme { LoginScreen(LoginUiState()) } }

    @Test fun setupApiKey() = paparazzi.snapshot { RunbumTheme { LoginScreen(LoginUiState(apiKeyOpen = true)) } }

    @Test fun signedInOAuth() = tab(Tab.SETTINGS) { SettingsScreen(SettingsUiState(settings = oauth)) }

    @Test fun signedInApiKey() = tab(Tab.SETTINGS) { SettingsScreen(SettingsUiState(settings = Settings(token = "x"), editingSync = true)) }

    @Test fun loginAgain() = tab(Tab.SETTINGS) { SettingsScreen(SettingsUiState(settings = oauth, authExpired = true)) }

    @Test fun watchScreensRun() = tab(Tab.WATCH) {
        WatchScreensScreen(ScreenSettings(), watchConnected = true, onEdit = {}, onTestBuzz = {}, onBack = {})
    }
}
