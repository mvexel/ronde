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

    fun parseEvents(body: String): List<Workout> =
        json.decodeFromString<List<EventDto>>(body)
            .filter { it.category == null || it.category == "WORKOUT" }
            .mapNotNull(::toWorkout)
            .sortedBy { it.startLocal }

    fun toWorkout(e: EventDto): Workout? {
        val doc = e.workoutDoc ?: return null
        val rawSteps = doc.steps.orEmpty()
        if (rawSteps.isEmpty()) return null
        val sport = sportOf(e.type)
        val steps = flatten(rawSteps, doc, prefix = null)
        if (steps.isEmpty()) return null
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

    fun sportOf(type: String?): Sport = when {
        type == null -> Sport.OTHER
        type.contains("Run") -> Sport.RUN
        type.contains("Ride") -> Sport.RIDE
        else -> Sport.OTHER
    }

    private fun flatten(steps: List<StepDto>, doc: WorkoutDocDto, prefix: String?): List<WorkoutStep> {
        val out = mutableListOf<WorkoutStep>()
        for (s in steps) {
            val children = s.steps
            if (!children.isNullOrEmpty()) {
                val reps = (s.reps ?: 1).coerceAtLeast(1)
                for (r in 1..reps) {
                    val label = listOfNotNull(prefix, "$r/$reps").joinToString(" · ")
                    out += flatten(children, doc, label)
                }
            } else {
                out += toStep(s, doc, prefix)
            }
        }
        return out
    }

    private fun toStep(s: StepDto, doc: WorkoutDocDto, repeat: String?): WorkoutStep {
        val kind = when {
            s.warmup == true -> StepKind.WARMUP
            s.cooldown == true -> StepKind.COOLDOWN
            s.intensity == "rest" || s.intensity == "recovery" -> StepKind.REST
            s.intensity == "warmup" -> StepKind.WARMUP
            s.intensity == "cooldown" -> StepKind.COOLDOWN
            else -> StepKind.ACTIVE
        }
        val ramp = s.ramp == true
        val targets = listOfNotNull(
            resolve(Metric.POWER, s.power, s.resolvedPower, doc, ramp),
            resolve(Metric.PACE, s.pace, s.resolvedPace, doc, ramp),
            resolve(Metric.HEART_RATE, s.hr, s.resolvedHr, doc, ramp),
            resolve(Metric.CADENCE, s.cadence, null, doc, ramp),
        )
        val preferred = when (doc.target) {
            "POWER" -> Metric.POWER
            "HR" -> Metric.HEART_RATE
            "PACE" -> Metric.PACE
            else -> null
        }
        val primary = preferred?.takeIf { p -> targets.any { it.metric == p } }
            ?: targets.firstOrNull { it.metric != Metric.CADENCE }?.metric
        val distance = (s.resolvedDistance ?: s.distance)?.takeIf { it > 0 }
        val duration = s.duration?.takeIf { it > 0 }
        val label = s.text?.takeIf { it.isNotBlank() } ?: when (kind) {
            StepKind.WARMUP -> "Warm up"
            StepKind.COOLDOWN -> "Cool down"
            StepKind.REST -> "Recover"
            StepKind.ACTIVE -> "Work"
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
