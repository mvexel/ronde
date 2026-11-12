package net.mvexel.intervalswatch.wear

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalInspectionMode
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.SnapshotHandler
import net.mvexel.intervalswatch.shared.Metric
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.StepProgress
import net.mvexel.intervalswatch.shared.Target
import net.mvexel.intervalswatch.shared.Workout
import net.mvexel.intervalswatch.shared.WorkoutStep
import net.mvexel.intervalswatch.wear.ui.RunbumTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Spec section 9: the ambient paced page lights at most 15 % of the disc's pixels (v5 measured 11.8 %). The test renders
 * [PacedAmbient] with Paparazzi and counts non-black pixels (any channel above 20) inside the inscribed circle.
 * It renders through a capturing [SnapshotHandler], so nothing is written to disk.
 */
abstract class PacedAmbientLitBase(device: DeviceConfig) {
    // Android unit tests compile against android.jar, which has no java.awt: the frame image (a BufferedImage) is handled by reflection.
    private val captured = ArrayList<Any>()

    private val handler: SnapshotHandler = run {
        val frameType = Class.forName("app.cash.paparazzi.SnapshotHandler\$FrameHandler")
        Proxy.newProxyInstance(SnapshotHandler::class.java.classLoader, arrayOf(SnapshotHandler::class.java)) { _, m, _ ->
            when (m.name) {
                "newFrameHandler" -> Proxy.newProxyInstance(frameType.classLoader, arrayOf(frameType)) { _, fm, fargs ->
                    if (fm.name == "handle") captured += fargs!![0]
                    null
                }
                else -> null
            }
        } as SnapshotHandler
    }

    @get:Rule val paparazzi = Paparazzi(deviceConfig = device, theme = "android:Theme.DeviceDefault", snapshotHandler = handler)

    private fun litRatio(s: Session, dx: Int = 0, dy: Int = 0): Double {
        captured.clear()
        val view = ComposeView(paparazzi.context).apply {
            setContent { CompositionLocalProvider(LocalInspectionMode provides true) { RunbumTheme { PacedAmbient(s, dx, dy) } } }
        }
        paparazzi.snapshot(view, offsetMillis = 1000L)
        val img = captured.last()
        val c = img.javaClass
        val w = c.getMethod("getWidth").invoke(img) as Int
        val h = c.getMethod("getHeight").invoke(img) as Int
        val getRGB = c.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val r = minOf(w, h) / 2.0
        var total = 0; var lit = 0
        for (y in 0 until h) for (x in 0 until w) {
            val dxp = x + 0.5 - w / 2.0; val dyp = y + 0.5 - h / 2.0
            if (dxp * dxp + dyp * dyp > r * r) continue
            total++
            val rgb = getRGB.invoke(img, x, y) as Int
            if (maxOf((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF) > 20) lit++
        }
        return lit.toDouble() / total
    }

    private fun step(low: Double?, high: Double?) = WorkoutStep(
        "Tempo", StepKind.ACTIVE, durationSec = 600, targets = listOf(Target(Metric.PACE, low ?: 0.0, high ?: 100.0)), primary = Metric.PACE, repeat = "2/3",
    )

    private fun session(st: WorkoutStep, paused: Boolean = false) = Session(
        workout = Workout(1, "t", Sport.RUN, startLocal = "2026-11-11T07:00:00", steps = listOf(st)), stepIndex = 0, step = st, next = null,
        progress = StepProgress(200.0, 372.0, 300.0, null, 0.3), activeSec = 1834.0, distanceM = 15230.0, heartRate = 171.0, speed = 3.5,
        power = null, cadence = null, powerStatus = null, gpsReady = true, paused = paused, finished = false, ended = false, stepAvgSpeed = 1000.0 / 286.0,
    )

    @Test fun ambientLightsUnder15PercentOfThePixels() {
        val cases = mapOf(
            "two-sided" to session(step(1000.0 / 300, 1000.0 / 280)),
            "two-sided, shifted by burn-in, paused" to session(step(1000.0 / 300, 1000.0 / 280), paused = true),
            "narrow range" to session(step(1000.0 / 291, 1000.0 / 289)),
            "one-sided cap" to session(step(null, 1000.0 / 330)),
        )
        for ((name, s) in cases) {
            val ratio = litRatio(s, if ("shifted" in name) 3 else 0, if ("shifted" in name) -3 else 0)
            println("AMBIENT lit pixels $name: ${"%.1f".format(ratio * 100)} %")
            assertTrue("$name lights ${ratio * 100} % of the disc", ratio < 0.15)
        }
    }
}

class PacedAmbientLit384Test : PacedAmbientLitBase(DeviceConfig.WEAR_OS_SMALL_ROUND)
class PacedAmbientLit456Test : PacedAmbientLitBase(DeviceConfig.WEAR_OS_SMALL_ROUND.copy(screenWidth = 456, screenHeight = 456))
