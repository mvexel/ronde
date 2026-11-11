package net.mvexel.intervalswatch.mobile.feature.history.detail

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import net.mvexel.intervalswatch.mobile.app.MainTabsScaffold
import net.mvexel.intervalswatch.mobile.app.Tab
import net.mvexel.intervalswatch.mobile.data.activity.ActivityDetail
import net.mvexel.intervalswatch.mobile.ui.RondeTheme
import org.junit.Rule
import org.junit.Test

/**
 * Workout Detail, design B. Paparazzi scales every snapshot to 1000 px tall, so the long page is shot as screen-sized
 * frames scrolled to a given item: Top (hero, numbers, steps), Charts, Map. The map is the Canvas sketch
 * (MapLibre's native renderer does not run in Paparazzi). The Strava stub and loading frames are in PhoneScreenshotTest.
 * `./gradlew :mobile:recordPaparazziDebug`, PNGs in mobile/src/test/snapshots/images.
 */
class ActivityDetailScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, theme = "android:Theme.Material.Light.NoActionBar")

    private fun page(d: ActivityDetail, dark: Boolean = false, item: Int = 0, error: String? = null, zones: List<Int>? = null) {
        val st = ActivityDetailUiState(loading = false, content = DetailModel.build(d, zones), error = error)
        paparazzi.snapshot { frame(dark) { ActivityDetailScreen(st, onBack = {}, listState = LazyListState(item)) } }
    }

    @Composable
    private fun frame(dark: Boolean, content: @Composable () -> Unit) =
        // Inspection mode swaps MapLibre for the Canvas route sketch.
        CompositionLocalProvider(LocalInspectionMode provides true) {
            RondeTheme(dark = dark) { MainTabsScaffold(selected = Tab.HISTORY, onSelect = {}, content = content) }
        }

    private val run get() = DetailFixtures.intervalRun()
    private val zones = DetailFixtures.hrZones

    @Test fun detailIntervalsLight() = page(run, zones = zones)
    @Test fun detailIntervalsLightSteps() = page(run, item = 3, zones = zones)
    @Test fun detailIntervalsLightCharts() = page(run, item = 5, zones = zones)
    @Test fun detailIntervalsDark() = page(run, dark = true, zones = zones)
    @Test fun detailIntervalsDarkSteps() = page(run, dark = true, item = 3, zones = zones)
    @Test fun detailIntervalsDarkCharts() = page(run, dark = true, item = 5, zones = zones)

    @Test fun detailUnstructured() = page(DetailFixtures.unstructuredRun())
    @Test fun detailUnstructuredMap() = page(DetailFixtures.unstructuredRun(), item = 5)
    @Test fun detailRide() = page(DetailFixtures.ride(), dark = true)
    @Test fun detailRefreshFailed() = page(DetailFixtures.unstructuredRun(), error = "No connection.")
    @Test fun detailStravaStub() = page(DetailFixtures.stravaStub())
    @Test fun detailStravaStubDark() = page(DetailFixtures.stravaStub(), dark = true)
    @Test fun detailLoading() = state(ActivityDetailUiState(loading = true))
    @Test fun detailLoadingDark() = state(ActivityDetailUiState(loading = true), dark = true)
    @Test fun detailFailed() = state(ActivityDetailUiState(loading = false, error = "No connection to intervals.icu"))

    private fun state(st: ActivityDetailUiState, dark: Boolean = false) =
        paparazzi.snapshot { frame(dark) { ActivityDetailScreen(st, onBack = {}) } }
}
