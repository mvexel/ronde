package net.mvexel.intervalswatch.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Turns the response of
 * GET /api/v1/athlete/{id}/events?category=WORKOUT&resolve=true
 * into flat, watch-ready [Workout]s.
 *
 * With resolve=true intervals.icu adds _power (W), _hr (bpm) and _pace (m/s) to each step,
 * already converted using the athlete's FTP / LTHR / threshold pace. Fixed targets come back
 * as a small range. We fall back to converting the raw values ourselves when those are missing.
 */
object IntervalsParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val RECOVERY_TEXT = Regex("\\b(recover\\w*|rest)\\b", RegexOption.IGNORE_CASE)

    /** An untexted step in a repeat block whose target is below this share of the block's hardest one is a recovery. */
    private const val EASY_RATIO = 0.85

    /** An event that did not become a workout, and why. */
    data class Dropped(val id: Long, val type: String?, val reason: String)

    data class Parsed(val received: Int, val workouts: List<Workout>, val dropped: List<Dropped>)

    fun parseEvents(body: String): List<Workout> = parse(body).workouts

    fun parse(body: String): Parsed {
        val events = json.decodeFromString<List<EventDto>>(body)
        val dropped = mutableListOf<Dropped>()
        val workouts = events.mapNotNull { e ->
            if (e.category != null && e.category != "WORKOUT") {
                dropped += Dropped(e.id, e.type, "category ${e.category}")
                null
            } else {
                toWorkout(e).also { if (it == null) dropped += Dropped(e.id, e.type, "no steps") }
            }
        }.sortedBy { it.startLocal }
        return Parsed(events.size, workouts, dropped)
    }

    fun toWorkout(e: EventDto): Workout? {
        val doc = e.workoutDoc ?: return plainWorkout(e)
        val steps = flatten(doc.steps.orEmpty(), doc, prefix = null)
        if (steps.isEmpty()) return plainWorkout(e)
        val sport = sportOf(e.type)
        return Workout(
            id = e.id,
            name = e.name?.takeIf { it.isNotBlank() } ?: "Workout",
            sport = sport,
            indoor = e.type?.startsWith("Virtual") == true || e.indoor == true,
            startLocal = e.startDateLocal ?: "",
            description = doc.description ?: e.description,
            steps = steps,
            totalDurationSec = doc.duration,
            totalDistanceM = doc.distance?.takeIf { it > 0 },
            paceUnit = if (doc.paceUnits?.contains("MILE") == true) PaceUnit.MILE else PaceUnit.KM,
        )
    }

    /** A planned event without structured steps: one step of its duration or distance, no target; neither means an open step. */
    private fun plainWorkout(e: EventDto): Workout {
        val distance = (e.workoutDoc?.distance ?: e.distance)?.takeIf { it > 0 }
        val duration = (e.workoutDoc?.duration ?: e.movingTime ?: e.elapsedTime)?.takeIf { it > 0 }
        val name = e.name?.takeIf { it.isNotBlank() } ?: "Workout"
        return Workout(
            id = e.id,
            name = name,
            sport = sportOf(e.type),
            indoor = e.type?.startsWith("Virtual") == true || e.indoor == true,
            startLocal = e.startDateLocal ?: "",
            description = e.description,
            steps = listOf(
                WorkoutStep(
                    label = name,
                    kind = StepKind.ACTIVE,
                    durationSec = if (distance != null) null else duration,
                    distanceM = distance,
                ),
            ),
            totalDurationSec = duration,
            totalDistanceM = distance,
        )
    }

    fun sportOf(type: String?): Sport = when {
        type == null -> Sport.OTHER
        type.contains("Run") -> Sport.RUN
        type.contains("Ride") -> Sport.RIDE
        type.contains("Walk") -> Sport.WALK
        else -> Sport.OTHER
    }

    private fun flatten(
        steps: List<StepDto>,
        doc: WorkoutDocDto,
        prefix: String?,
        easy: Set<Int> = emptySet(),
    ): List<WorkoutStep> {
        val out = mutableListOf<WorkoutStep>()
        steps.forEachIndexed { i, s ->
            val children = s.steps
            if (!children.isNullOrEmpty()) {
                val reps = (s.reps ?: 1).coerceAtLeast(1)
                val easyChildren = easyIndices(children, doc)
                for (r in 1..reps) {
                    val label = listOfNotNull(prefix, "$r/$reps").joinToString(" · ")
                    out += flatten(children, doc, label, easyChildren)
                }
            } else {
                out += toStep(s, doc, prefix, forceRest = i in easy)
            }
        }
        return out
    }

    private fun targetsOf(s: StepDto, doc: WorkoutDocDto): List<Target> {
        val ramp = s.ramp == true
        return listOfNotNull(
            resolve(Metric.POWER, s.power, s.resolvedPower, doc, ramp),
            resolve(Metric.PACE, s.pace, s.resolvedPace, doc, ramp),
            resolve(Metric.HEART_RATE, s.hr, s.resolvedHr, doc, ramp),
            resolve(Metric.CADENCE, s.cadence, null, doc, ramp),
        )
    }

    private fun primaryOf(doc: WorkoutDocDto, targets: List<Target>): Metric? {
        val preferred = when (doc.target) {
            "POWER" -> Metric.POWER
            "HR" -> Metric.HEART_RATE
            "PACE" -> Metric.PACE
            else -> null
        }
        return preferred?.takeIf { p -> targets.any { it.metric == p } }
            ?: targets.firstOrNull { it.metric != Metric.CADENCE }?.metric
    }

    /**
     * Indices of steps in a repeat block that are recoveries by target alone: no text, no intensity,
     * and a primary target under [EASY_RATIO] of the block's hardest step on the same metric.
     * Higher is harder for power, HR and speed, so a lower midpoint is an easier target.
     * A single-step block has nothing to compare with and is left alone.
     */
    private fun easyIndices(children: List<StepDto>, doc: WorkoutDocDto): Set<Int> {
        if (children.size < 2) return emptySet()
        val primaries = children.map { c ->
            val targets = targetsOf(c, doc)
            primaryOf(doc, targets)?.let { m -> targets.first { it.metric == m } }
        }
        val hardest = primaries.filterNotNull().groupBy { it.metric }
            .mapValues { (_, ts) -> ts.maxOf { mid(it) } }
        return children.indices.filter { i ->
            val c = children[i]
            val t = primaries[i]
            c.text.isNullOrBlank() && c.intensity == null && t != null &&
                mid(t) < EASY_RATIO * hardest.getValue(t.metric)
        }.toSet()
    }

    private fun mid(t: Target): Double = (t.low + t.high) / 2

    private fun toStep(s: StepDto, doc: WorkoutDocDto, repeat: String?, forceRest: Boolean = false): WorkoutStep {
        val text = s.text?.takeIf { it.isNotBlank() }
        val saysRecovery = text != null && RECOVERY_TEXT.containsMatchIn(text)
        val kind = when {
            s.warmup == true -> StepKind.WARMUP
            s.cooldown == true -> StepKind.COOLDOWN
            s.intensity == "rest" || s.intensity == "recovery" -> StepKind.REST
            s.intensity == "warmup" -> StepKind.WARMUP
            s.intensity == "cooldown" -> StepKind.COOLDOWN
            saysRecovery || forceRest -> StepKind.REST
            else -> StepKind.ACTIVE
        }
        val targets = targetsOf(s, doc)
        val primary = primaryOf(doc, targets)
        val distance = (s.resolvedDistance ?: s.distance)?.takeIf { it > 0 }
        val duration = s.duration?.takeIf { it > 0 }
        // A rest step without its own text is shown as Recovery; intervals.icu text always wins.
        val label = when (kind) {
            StepKind.REST -> text ?: "Recovery"
            StepKind.WARMUP -> text ?: "Warm up"
            StepKind.COOLDOWN -> text ?: "Cool down"
            StepKind.ACTIVE -> text ?: "Work"
        }
        return WorkoutStep(
            label = label,
            kind = kind,
            // Distance-based steps also carry an estimated duration; distance wins.
            durationSec = if (distance != null) null else duration,
            distanceM = distance,
            targets = targets,
            primary = primary,
            repeat = repeat,
        ).let { if (s.untilLapPress == true) it.copy(durationSec = null, distanceM = null) else it }
    }

    private fun resolve(metric: Metric, raw: ValueDto?, resolved: ValueDto?, doc: WorkoutDocDto, ramp: Boolean): Target? {
        var lo: Double?
        var hi: Double?
        if (resolved != null && (resolved.start != null || resolved.value != null)) {
            lo = resolved.start ?: resolved.value
            hi = resolved.end ?: resolved.value
        } else if (raw != null) {
            val conv = converter(metric, raw.units, doc) ?: return null
            val a = (raw.start ?: raw.value)?.let(conv)
            val b = (raw.end ?: raw.value)?.let(conv)
            lo = a; hi = b
            // Fixed value without resolve: widen into a range like intervals.icu does.
            if (!ramp && raw.start == null && raw.end == null && a != null) {
                val tol = when (metric) {
                    Metric.HEART_RATE -> 0.015
                    Metric.CADENCE -> 0.03
                    else -> 0.025
                }
                lo = a * (1 - tol); hi = a * (1 + tol)
            }
        } else {
            return null
        }
        if (lo == null || hi == null || lo <= 0 && hi <= 0) return null
        return if (ramp) {
            Target(metric, minOf(lo, hi), maxOf(lo, hi), rampFrom = lo, rampTo = hi)
        } else {
            Target(metric, minOf(lo, hi), maxOf(lo, hi))
        }
    }

    private fun converter(metric: Metric, units: String?, doc: WorkoutDocDto): ((Double) -> Double)? {
        val u = units?.lowercase()
        return when (metric) {
            Metric.POWER -> when (u) {
                null, "%ftp" -> doc.ftp?.let { ftp -> { v: Double -> v * ftp / 100.0 } }
                "w", "watts" -> { v -> v }
                else -> null
            }
            Metric.HEART_RATE -> when (u) {
                "bpm" -> { v -> v }
                "%lthr" -> doc.lthr?.let { lthr -> { v: Double -> v * lthr / 100.0 } }
                else -> null
            }
            Metric.PACE -> when (u) {
                "%pace" -> doc.thresholdPace?.let { tp -> { v: Double -> v * tp / 100.0 } }
                "secs/km" -> { v -> 1000.0 / v }
                "secs/mile" -> { v -> 1609.344 / v }
                "m/s" -> { v -> v }
                else -> null
            }
            Metric.CADENCE -> { v -> v }
        }
    }
}

@Serializable
data class EventDto(
    val id: Long,
    val name: String? = null,
    val type: String? = null,
    val category: String? = null,
    val indoor: Boolean? = null,
    val description: String? = null,
    val distance: Double? = null,
    @SerialName("moving_time") val movingTime: Int? = null,
    @SerialName("elapsed_time") val elapsedTime: Int? = null,
    @SerialName("start_date_local") val startDateLocal: String? = null,
    @SerialName("workout_doc") val workoutDoc: WorkoutDocDto? = null,
)

@Serializable
data class WorkoutDocDto(
    val description: String? = null,
    val duration: Int? = null,
    val distance: Double? = null,
    val ftp: Int? = null,
    val lthr: Int? = null,
    @SerialName("threshold_pace") val thresholdPace: Double? = null,
    @SerialName("pace_units") val paceUnits: String? = null,
    val target: String? = null,
    val steps: List<StepDto>? = null,
)

@Serializable
data class StepDto(
    val text: String? = null,
    val duration: Int? = null,
    val distance: Double? = null,
    @SerialName("until_lap_press") val untilLapPress: Boolean? = null,
    val reps: Int? = null,
    val warmup: Boolean? = null,
    val cooldown: Boolean? = null,
    val intensity: String? = null,
    val steps: List<StepDto>? = null,
    val ramp: Boolean? = null,
    val freeride: Boolean? = null,
    val power: ValueDto? = null,
    val hr: ValueDto? = null,
    val pace: ValueDto? = null,
    val cadence: ValueDto? = null,
    @SerialName("_power") val resolvedPower: ValueDto? = null,
    @SerialName("_hr") val resolvedHr: ValueDto? = null,
    @SerialName("_pace") val resolvedPace: ValueDto? = null,
    @SerialName("_distance") val resolvedDistance: Double? = null,
)

@Serializable
data class ValueDto(
    val value: Double? = null,
    val start: Double? = null,
    val end: Double? = null,
    val units: String? = null,
)
