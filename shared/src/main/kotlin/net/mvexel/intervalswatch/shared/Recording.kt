package net.mvexel.intervalswatch.shared

/** One second of a workout. [t] is wall-clock seconds since the recording started; missing sensors are null. */
data class RecSample(
    val t: Double,
    val distanceM: Double,
    val lat: Double? = null,
    val lon: Double? = null,
    val altM: Double? = null,
    val speed: Double? = null,
    val hr: Int? = null,
    val power: Int? = null,
    val cadence: Int? = null,
)

/** Wall-clock seconds since start. */
data class RecLap(val startSec: Double, val endSec: Double, val startM: Double, val endM: Double, val kind: StepKind?)

data class RecPause(val startSec: Double, val endSec: Double)

data class Recording(
    val name: String,
    val sport: Sport,
    val indoor: Boolean,
    val startEpochMs: Long,
    val samples: List<RecSample>,
    val laps: List<RecLap>,
    val pauses: List<RecPause>,
    /** Wall-clock length (FIT total_elapsed_time). */
    val elapsedSec: Double,
    val totalM: Double,
) {
    /** Moving/timer time: elapsed minus pauses (FIT total_timer_time). */
    val timerSec: Double get() = timerBetween(0.0, elapsedSec)

    fun timerBetween(from: Double, to: Double): Double =
        (to - from) - pauses.sumOf { (minOf(it.endSec, to) - maxOf(it.startSec, from)).coerceAtLeast(0.0) }
}

/** Collects samples, step boundaries and pauses while a workout runs. All times are wall-clock seconds since start. */
class RecordingBuilder(
    private val name: String,
    private val sport: Sport,
    private val indoor: Boolean,
    private val startEpochMs: Long,
) {
    private val samples = ArrayList<RecSample>()
    private val laps = ArrayList<RecLap>()
    private val pauses = ArrayList<RecPause>()
    private var pausedAt: Double? = null
    private var lapIndex = -1
    private var lapKind: StepKind? = null
    private var lapStartSec = 0.0
    private var lapStartM = 0.0

    fun add(s: RecSample) {
        // FIT timestamps have 1 s resolution: keep one record per second.
        if (samples.lastOrNull()?.let { it.t.toLong() >= s.t.toLong() } == true) return
        samples += s
    }

    fun pause(t: Double) { if (pausedAt == null) pausedAt = t }

    fun resume(t: Double) {
        val p = pausedAt ?: return
        pausedAt = null
        if (t > p) pauses += RecPause(p, t)
    }

    /** Call on every tick; closes the previous lap when the step index changed. */
    fun onStep(index: Int, step: WorkoutStep?, t: Double, d: Double) {
        if (index == lapIndex) return
        if (lapIndex >= 0) closeLap(t, d)
        lapIndex = index
        lapKind = step?.kind
        lapStartSec = t
        lapStartM = d
    }

    fun build(t: Double, d: Double): Recording {
        resume(t)
        if (lapIndex >= 0) closeLap(t, d)
        lapIndex = -1
        return Recording(name, sport, indoor, startEpochMs, samples.toList(), laps.toList(), pauses.toList(), t, d)
    }

    private fun closeLap(t: Double, d: Double) {
        if (t > lapStartSec) laps += RecLap(lapStartSec, t, lapStartM, d, lapKind)
    }
}

/** What the watch shows after a workout ends. */
sealed interface SaveStatus {
    data object Saving : SaveStatus
    data object Saved : SaveStatus
    data class Failed(val reason: String) : SaveStatus
}

/** Watch ↔ phone protocol for finished recordings. Nothing here carries the API key (NFR2). */
object RecordingProtocol {
    const val CHANNEL_PREFIX = "/recording/"          // watch → phone, file name follows
    const val RESULT_PATH = "/recording-result"       // phone → watch message
    const val OK = "ok"
    const val FAIL = "fail"
    private const val MAX_NAME_CHARS = 60

    /** `<epochSec>_<base64url(workout name)>.fit`: safe as a path segment and carries the real name. */
    fun fileName(startEpochMs: Long, name: String): String {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(name.take(MAX_NAME_CHARS).toByteArray(Charsets.UTF_8))
        return "${startEpochMs / 1000}_$enc.fit"
    }

    /** The workout name carried by [fileName]; falls back to "Workout". */
    fun displayName(fileName: String): String = runCatching {
        String(java.util.Base64.getUrlDecoder().decode(fileName.removeSuffix(".fit").substringAfter('_')), Charsets.UTF_8)
    }.getOrNull()?.ifBlank { null } ?: "Workout"

    fun externalId(fileName: String): String = "intervalswatch-${fileName.substringBefore('_')}"

    /** [retry] false means the phone gave up on this file (it was rejected for good). */
    data class Result(val ok: Boolean, val fileName: String, val reason: String, val retry: Boolean)

    fun result(ok: Boolean, fileName: String, reason: String = "", retry: Boolean = true): ByteArray =
        listOf(if (ok) OK else FAIL, fileName, if (retry) "1" else "0", reason).joinToString("\n").toByteArray()

    fun parseResult(data: ByteArray): Result {
        val p = String(data).split("\n", limit = 4)
        return Result(p.getOrNull(0) == OK, p.getOrElse(1) { "" }, p.getOrElse(3) { "" }, p.getOrNull(2) != "0")
    }
}
