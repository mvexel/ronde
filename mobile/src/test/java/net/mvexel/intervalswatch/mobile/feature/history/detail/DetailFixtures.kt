package net.mvexel.intervalswatch.mobile.feature.history.detail

import net.mvexel.intervalswatch.mobile.data.activity.ActivityDetail
import net.mvexel.intervalswatch.mobile.data.activity.LatLon
import net.mvexel.intervalswatch.shared.ActivityStreams
import net.mvexel.intervalswatch.shared.Metric
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.Target
import net.mvexel.intervalswatch.shared.TimeSpan
import net.mvexel.intervalswatch.shared.WorkoutStep
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The design's run (design.md): 10′ warm-up, 5×(3′ @ 4:10–4:20, 2′ easy), 10′ cool-down. Rep 2 is too fast
 * (about 4:05, quick first minute), rep 4 too slow (fades after 1:30), so it is "3 of 5 rounds in range".
 * Synthetic and seeded, 1 Hz.
 */
object DetailFixtures {
    private fun pace(min: Int, sec: Int) = 1000.0 / (min * 60 + sec)

    val repTarget = Target(Metric.PACE, low = pace(4, 20), high = pace(4, 10))

    val steps: List<WorkoutStep> = buildList {
        add(WorkoutStep("Warm-up", StepKind.WARMUP, durationSec = 600))
        repeat(5) { i ->
            add(WorkoutStep("Rep", StepKind.ACTIVE, durationSec = 180, targets = listOf(repTarget), primary = Metric.PACE, repeat = "${i + 1}/5"))
            add(WorkoutStep("Easy", StepKind.REST, durationSec = 120, repeat = "${i + 1}/5"))
        }
        add(WorkoutStep("Cool-down", StepKind.COOLDOWN, durationSec = 600))
    }

    val boundaries: List<TimeSpan> = run {
        var t = 0.0
        steps.map { s -> TimeSpan(t, t + s.durationSec!!).also { t += s.durationSec!! } }
    }

    /** Pace (s/km) at second [sec] of step [k]. */
    private fun paceAt(k: Int, sec: Double): Double = when {
        k == 0 -> 329.0
        k == steps.lastIndex -> 340.0
        steps[k].kind == StepKind.REST -> 375.0
        else -> when ((k - 1) / 2 + 1) {
            1 -> 255.0
            2 -> if (sec < 60) 233.0 else 251.0
            3 -> 258.0
            4 -> if (sec < 90) 256.0 else 277.0
            else -> 255.0
        }
    }

    fun streams(withPower: Boolean = true, seed: Int = 7): ActivityStreams {
        val rnd = Random(seed)
        val n = 2701
        val t = DoubleArray(n) { it.toDouble() }
        val v = DoubleArray(n)
        val hr = DoubleArray(n)
        val w = DoubleArray(n)
        val d = DoubleArray(n)
        var h = 112.0
        for (i in 0 until n) {
            val k = boundaries.indexOfLast { t[i] >= it.startSec }.coerceAtLeast(0)
            val into = t[i] - boundaries[k].startSec
            val secKm = paceAt(k, into) + (rnd.nextDouble() - 0.5) * 8
            v[i] = 1000.0 / secKm
            val goal = when (steps[k].kind) {
                StepKind.ACTIVE -> 156.0 + (k / 2) * 3.0
                StepKind.REST -> 140.0
                StepKind.WARMUP -> 128.0
                StepKind.COOLDOWN -> 138.0
            }
            h += (goal - h) * (1 - exp(-1.0 / 25))
            hr[i] = h + (rnd.nextDouble() - 0.5) * 2
            w[i] = (if (steps[k].kind == StepKind.ACTIVE) 300.0 + (255 - paceAt(k, into)) * 2 else 205.0) + (rnd.nextDouble() - 0.5) * 12
            if (i > 0) d[i] = d[i - 1] + v[i]
        }
        return ActivityStreams(t, velocityMps = v, heartrate = hr, watts = if (withPower) w else null, distanceM = d)
    }

    /** A loop around a park, placed by distance so the reps land where the streams say. */
    fun route(s: ActivityStreams): List<LatLon> {
        val total = s.distanceM!!.last()
        return s.distanceM!!.map { m ->
            val a = 2 * PI * m / total
            LatLon(40.7447 + 0.0105 * sin(a) + 0.002 * sin(3 * a), -111.8742 - 0.0165 * cos(a))
        }
    }

    fun intervalRun(): ActivityDetail {
        val s = streams()
        return ActivityDetail(
            id = "i1001", name = "5×3′ threshold", sport = Sport.RUN, startLocal = "2026-11-10T07:12:00",
            distanceM = s.distanceM!!.last(), movingTimeSec = 2700, elapsedTimeSec = 2700,
            avgHr = 146.0, maxHr = 172.0, avgPower = null, load = 62, isRonde = true, deviceName = "Pixel Watch 3",
            detailsAvailable = true, streams = s, route = route(s), plannedSteps = steps, boundaries = boundaries,
        )
    }

    fun unstructuredRun(): ActivityDetail = intervalRun().copy(
        id = "i1002", name = "Lunch run", plannedSteps = null, boundaries = null, isRonde = false,
        deviceName = "Garmin Forerunner 265", streams = streams(withPower = false, seed = 3),
    ).let { it.copy(route = route(it.streams!!)) }

    fun stravaStub(): ActivityDetail = ActivityDetail(
        id = "i1003", name = "Morning Run", sport = Sport.RUN, startLocal = "2026-11-08T08:40:00",
        distanceM = null, movingTimeSec = null, elapsedTimeSec = null, avgHr = null, maxHr = null, avgPower = null,
        load = null, isRonde = false, deviceName = null, detailsAvailable = false, streams = null, route = null,
        plannedSteps = null, boundaries = null,
    )

    fun ride(): ActivityDetail {
        val s = streams(seed = 11).let { st ->
            ActivityStreams(st.timeSec, DoubleArray(st.size) { st.velocityMps!![it] * 2.4 }, st.heartrate, st.watts, distanceM = DoubleArray(st.size) { st.distanceM!![it] * 2.4 })
        }
        return ActivityDetail(
            id = "i1004", name = "Morning ride", sport = Sport.RIDE, startLocal = "2026-11-07T09:15:00",
            distanceM = s.distanceM!!.last(), movingTimeSec = 2700, elapsedTimeSec = 2760, avgHr = 132.0, maxHr = 161.0,
            avgPower = 241.0, load = 58, isRonde = false, deviceName = null, detailsAvailable = true,
            streams = s, route = route(s), plannedSteps = null, boundaries = null,
        )
    }

    /** intervals.icu-style HR zone tops for Z1..Z4. */
    val hrZones = listOf(132, 145, 155, 166)
}
