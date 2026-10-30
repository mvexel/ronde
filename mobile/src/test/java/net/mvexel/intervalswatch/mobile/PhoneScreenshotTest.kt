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

    @Test fun recordings() = paparazzi.snapshot {
        RunbumTheme {
            MainScreen(
                UiState(
                    settings = Settings(token = "x"),
                    recordings = listOf(
                        RecordingItem("1_a.fit", "Tempo run", 1_761_800_000, 48_000, UploadState.UPLOADED),
                        RecordingItem("2_a.fit", "Open ride", 1_761_700_000, 120_000, UploadState.PENDING),
                        RecordingItem("3_a.fit", "Hills", 1_761_600_000, 30_000, UploadState.FAILED, "rejected by intervals.icu"),
                    ),
                ),
                onSync = {}, onEdit = {}, onSave = {},
            )
        }
    }

    @Test fun setup() = paparazzi.snapshot {
        RunbumTheme {
            MainScreen(UiState(), onSync = {}, onEdit = {}, onSave = {})
        }
    }

    @Test fun setupApiKey() = paparazzi.snapshot {
        RunbumTheme {
            MainScreen(UiState(editing = true), onSync = {}, onEdit = {}, onSave = {})
        }
    }

    @Test fun signedInOAuth() = paparazzi.snapshot {
        RunbumTheme {
            MainScreen(
                UiState(
                    settings = Settings(token = "x", authType = AuthType.OAUTH, athleteId = "i12345", athleteName = "Martijn van Exel"),
                    workouts = Samples.workouts,
                ),
                onSync = {}, onEdit = {}, onSave = {},
            )
        }
    }

    @Test fun loginAgain() = paparazzi.snapshot {
        RunbumTheme {
            MainScreen(
                UiState(
                    settings = Settings(token = "x", authType = AuthType.OAUTH, athleteId = "i12345", athleteName = "Martijn van Exel"),
                    authExpired = true, status = "Failed: intervals.icu access expired or revoked, log in again",
                ),
                onSync = {}, onEdit = {}, onSave = {},
            )
        }
    }

    @Test fun watchScreensRun() = paparazzi.snapshot {
        RunbumTheme {
            MainScreen(
                UiState(settings = Settings(token = "x"), watches = listOf("Pixel Watch 3"), watchScreens = true),
                onSync = {}, onEdit = {}, onSave = {},
            )
        }
    }
}
