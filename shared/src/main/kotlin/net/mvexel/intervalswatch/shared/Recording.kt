package net.mvexel.intervalswatch.shared

/** One second of a workout. Active time (pauses excluded) is [t]; missing sensors are null. */
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

data class RecLap(val startSec: Double, val endSec: Double, val startM: Double, val endM: Double, val kind: StepKind?)

data class Recording(
    val name: String,
    val sport: Sport,
    val indoor: Boolean,
    val startEpochMs: Long,
    val samples: List<RecSample>,
    val laps: List<RecLap>,
    val totalSec: Double,
    val totalM: Double,
)

/** Collects samples and step boundaries while a workout runs. */
class RecordingBuilder(
    private val name: String,
    private val sport: Sport,
    private val indoor: Boolean,
    private val startEpochMs: Long,
) {
    private val samples = ArrayList<RecSample>()
    private val laps = ArrayList<RecLap>()
    private var lapIndex = -1
    private var lapKind: StepKind? = null
    private var lapStartSec = 0.0
    private var lapStartM = 0.0

    fun add(s: RecSample) { samples += s }

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
        if (lapIndex >= 0) closeLap(t, d)
        lapIndex = -1
        return Recording(name, sport, indoor, startEpochMs, samples.toList(), laps.toList(), t, d)
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

    fun result(ok: Boolean, fileName: String, reason: String = ""): ByteArray =
        listOf(if (ok) OK else FAIL, fileName, reason).joinToString("\n").toByteArray()

    /** @return Triple(ok, fileName, reason) */
    fun parseResult(data: ByteArray): Triple<Boolean, String, String> {
        val p = String(data).split("\n", limit = 3)
        return Triple(p.getOrNull(0) == OK, p.getOrElse(1) { "" }, p.getOrElse(2) { "" })
    }
}
