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
    /** intervals.icu id of the planned event this was recorded for; null for open/free workouts. */
    val eventId: Long? = null,
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
    private val eventId: Long? = null,
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
        // FIT timestamps have 1 s resolution: keep one record per second, filling gaps in the
        // existing one from the new one. Late points (a batch after a tick sample) slot in by time.
        val sec = s.t.toLong()
        var i = samples.size
        while (i > 0 && samples[i - 1].t.toLong() > sec) i--
        val prev = samples.getOrNull(i - 1)
        if (prev != null && prev.t.toLong() == sec) {
            samples[i - 1] = prev.copy(
                distanceM = prev.distanceM,
                lat = prev.lat ?: s.lat, lon = prev.lon ?: s.lon, altM = prev.altM ?: s.altM,
                speed = prev.speed ?: s.speed, hr = prev.hr ?: s.hr,
                power = prev.power ?: s.power, cadence = prev.cadence ?: s.cadence,
            )
        } else samples.add(i, s)
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
        return Recording(name, sport, indoor, startEpochMs, samples.toList(), laps.toList(), pauses.toList(), t, d, eventId)
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
    /** Keeps the encoded file name well under the 255-byte limit (base64 grows 4/3). */
    private const val MAX_NAME_BYTES = 110
    const val PHONE_CAPABILITY = "intervalswatch_phone"

    /**
     * `<epochSec>_<base64url(workout name)>[.e<eventId>].fit`: safe as a path segment and carries the
     * real name and, when there is one, the planned event id (base64url has no '.', so it can't clash).
     * Files without the id part (older pending ones) stay valid.
     */
    fun fileName(startEpochMs: Long, name: String, eventId: Long? = null): String {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(truncateUtf8(name, MAX_NAME_BYTES).toByteArray(Charsets.UTF_8))
        return "${startEpochMs / 1000}_$enc${eventId?.let { ".e$it" }.orEmpty()}.fit"
    }

    /** Cuts [s] at a code point boundary so its UTF-8 form is at most [maxBytes]. */
    fun truncateUtf8(s: String, maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < s.length) {
            val cp = s.codePointAt(end)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + n > maxBytes) break
            bytes += n
            end += Character.charCount(cp)
        }
        return s.substring(0, end)
    }

    /** The workout name carried by [fileName]; falls back to "Workout". */
    fun displayName(fileName: String): String = runCatching {
        String(java.util.Base64.getUrlDecoder().decode(fileName.removeSuffix(".fit").substringBefore('.').substringAfter('_')), Charsets.UTF_8)
    }.getOrNull()?.ifBlank { null } ?: "Workout"

    /** The planned event id carried by [fileName], or null (no id, or an older file name). */
    fun eventId(fileName: String): Long? =
        Regex("\\.e(\\d+)\\.fit$").find(fileName)?.groupValues?.get(1)?.toLongOrNull()

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
