package net.mvexel.intervalswatch.mobile

import androidx.compose.runtime.Composable
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
import net.mvexel.intervalswatch.mobile.ui.RunbumTheme
import net.mvexel.intervalswatch.shared.Samples
import net.mvexel.intervalswatch.shared.ScreenSettings
import org.junit.Rule
import org.junit.Test

/** Run `./gradlew :mobile:recordPaparazziDebug`; PNGs land in mobile/src/test/snapshots/images. */
class PhoneScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    private fun tab(t: Tab, content: @Composable () -> Unit) = paparazzi.snapshot {
        RunbumTheme { MainTabsScaffold(selected = t, onSelect = {}, content = content) }
    }

    private val oauth = Settings(token = "x", authType = AuthType.OAUTH, athleteId = "i12345", athleteName = "Martijn van Exel")

    @Test fun synced() = tab(Tab.PLAN) {
        PlanScreen(PlanUiState(visible = Samples.workouts.mapIndexed { i, w -> PlanItem(w, pinned = i == 0) }))
    }

    @Test fun planWithHidden() = tab(Tab.PLAN) {
        val (h, v) = Samples.workouts.partition { it.id == Samples.workouts.last().id }
        PlanScreen(PlanUiState(visible = v.map { PlanItem(it) }, hidden = h.map { PlanItem(it, hidden = true) }, showHidden = true))
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
            listOf(
                RecordingItem("1_a.fit", "Tempo run", 1_761_800_000, 48_000, UploadState.UPLOADED),
                RecordingItem("2_a.fit", "Open ride", 1_761_700_000, 120_000, UploadState.PENDING),
                RecordingItem("3_a.fit", "Hills", 1_761_600_000, 30_000, UploadState.FAILED, "rejected by intervals.icu"),
            ),
            onSync = {},
        )
    }

    @Test fun historyEmpty() = tab(Tab.HISTORY) { HistoryScreen(HistoryUiState(loading = false)) }

    @Test fun history() = tab(Tab.HISTORY) {
        HistoryScreen(
            HistoryUiState(
                listOf(
                    ActivityEntity("i2", "Tempo run", "Run", "2026-11-09T07:00:00", 10_000.0, 3_000, 3_100, isRonde = true, fetchedAt = 0),
                    ActivityEntity("i1", "Lunch ride", "Ride", "2026-11-08T12:00:00", 40_000.0, 5_400, 6_000, fetchedAt = 0),
                ),
                loading = false,
            ),
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
