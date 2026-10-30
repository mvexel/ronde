package net.mvexel.intervalswatch.mobile

import net.mvexel.intervalswatch.mobile.ui.RunbumTheme
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import net.mvexel.intervalswatch.shared.Samples
import org.junit.Rule
import org.junit.Test

/** Run `./gradlew :mobile:recordPaparazziDebug`; PNGs land in mobile/src/test/snapshots/images. */
class PhoneScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    @Test fun synced() = paparazzi.snapshot {
        RunbumTheme {
            MainScreen(
                UiState(
                    settings = Settings(token = "x"),
                    workouts = Samples.workouts,
                    watches = listOf("Pixel Watch 3"),
                    status = "Sent 3 workout(s) to the watch",
                ),
                onSync = {}, onEdit = {}, onSave = {},
            )
        }
    }

    @Test fun setup() = paparazzi.snapshot {
        RunbumTheme {
            MainScreen(UiState(editing = true), onSync = {}, onEdit = {}, onSave = {})
        }
    }
}
