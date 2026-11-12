package net.mvexel.intervalswatch.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * What a slot on the watch's Step page (or a Totals quadrant) can show: settings.md §2.1.
 * [TIME_LEFT] is the rule-driven Hero/Partner value and is never offered in a picker.
 */
@Serializable
enum class Field(val selectable: Boolean = true) {
    HEART_RATE, PACE, STEP_AVG_PACE, SPEED, POWER, CADENCE, DISTANCE, TOTAL_TIME, CLOCK,
    STEP_TIME, WORKOUT_LEFT, AVG_HR, AVG_PACE, AVG_POWER,
    TIME_LEFT(selectable = false);

    /** Whether the catalogue offers this field for [sport] (Cadence on Ride is deferred, RF01). */
    fun offeredFor(sport: Sport): Boolean = when (this) {
        PACE, STEP_AVG_PACE, AVG_PACE, CADENCE -> sport != Sport.RIDE
        SPEED -> sport == Sport.RIDE
        else -> true
    }
}

@Serializable
enum class BigNumber { LIVE_VALUE, TIME_LEFT }

@Serializable
enum class BuzzStrength { NORMAL, STRONG }

@Serializable
enum class UnitsSetting { FROM_INTERVALS, KM, MI }

/** "Cue row" on the paced step page (v5 section 1): how an off-pace verdict is said. Slot swap only, no layout change. */
@Serializable
enum class CueRow { NUMBER, WORDS }

/** "Colour band when off pace": the lower-cap wash during a judged alert (v5 section 1, brand ruling 2). */
@Serializable
enum class ColourBand { OFF, ON }

/**
 * Build flag for the owner's first outdoor build: set true so the colour band defaults to ON (the log decides afterwards).
 * Release default is false. It only changes the default of a setting nobody has touched.
 */
object PacedRunDefaults {
    const val FIRST_RUN_DEFAULTS = false
    val colourBand: ColourBand get() = if (FIRST_RUN_DEFAULTS) ColourBand.ON else ColourBand.OFF
}

/** Settings scoped to a sport profile (Run or Ride; Walk and Other use Run). */
@Serializable
data class SportSettings(
    val bigNumber: BigNumber = BigNumber.LIVE_VALUE,
    val flankLeft: Field = Field.HEART_RATE,
    val flankRight: Field,
    val bottomEdge: Field = Field.TOTAL_TIME,
    val totalsPage: Boolean = true,
    val totalsFields: List<Field>,
    val workoutRingPage: Boolean = true,
    val offTargetBuzz: Boolean = true,
    /** Rolling-mean window for displayed pace and range alerts on non-paced steps: 15, 20 or 30 s (RF01 FR10 a); the stored default stays 20. */
    val paceWindowSec: Int = 20,
    /** Display mean window of the paced step page (v5 section 7: 15, 20 or 30 s, default 30). Its own setting so [paceWindowSec] keeps its meaning. */
    val pacedWindowSec: Int = DEFAULT_PACED_WINDOW,
) {
    companion object {
        val RUN_DEFAULT = SportSettings(
            flankRight = Field.STEP_AVG_PACE,
            totalsFields = listOf(Field.TOTAL_TIME, Field.DISTANCE, Field.AVG_PACE, Field.AVG_HR),
        )
        val RIDE_DEFAULT = SportSettings(
            flankRight = Field.SPEED,
            totalsFields = listOf(Field.TOTAL_TIME, Field.DISTANCE, Field.AVG_POWER, Field.AVG_HR),
        )
    }
}

/** Everything the Data Layer item `/settings` carries. Never holds credentials (NFR2). */
@Serializable
data class ScreenSettings(
    val run: SportSettings = SportSettings.RUN_DEFAULT,
    val ride: SportSettings = SportSettings.RIDE_DEFAULT,
    val buzzStrength: BuzzStrength = BuzzStrength.NORMAL,
    val units: UnitsSetting = UnitsSetting.FROM_INTERVALS,
    /** Run only: toggle auto-pause for warm-up and cool-down steps. */
    val autoPauseWarmupCooldown: Boolean = false,
    val keepScreenOnDuringWork: Boolean = false,
    /** Paced step page: Number (default) or Words in the cue row (v5 section 1). */
    val cueRow: CueRow = CueRow.NUMBER,
    /** Paced step page: lower-cap wash while off pace; Off in release (v5 section 1). */
    val colourBand: ColourBand = PacedRunDefaults.colourBand,
    /** Last write wins on sync. */
    val updatedAt: Long = 0,
) {
    fun forSport(sport: Sport): SportSettings = if (sport == Sport.RIDE) ride else run

    fun toJson(): String = Sync.json.encodeToString(this)

    companion object {
        fun fromJson(text: String): ScreenSettings = Sync.json.decodeFromString(text)
    }
}

/** Which data exists in this workout right now (indoor: no GPS; no power meter; no cadence source). */
data class Availability(
    val gps: Boolean = true,
    val power: Boolean = true,
    val heartRate: Boolean = true,
    val cadence: Boolean = true,
) {
    fun has(field: Field): Boolean = when (field) {
        Field.PACE, Field.STEP_AVG_PACE, Field.SPEED, Field.DISTANCE, Field.AVG_PACE -> gps
        Field.POWER, Field.AVG_POWER -> power
        Field.HEART_RATE, Field.AVG_HR -> heartRate
        Field.CADENCE -> cadence
        else -> true
    }
}

/** The fields on the Step page. [hero] and [partner] are rule-driven; the other three are the user slots. */
data class ResolvedSlots(
    val hero: Field,
    val partner: Field,
    val flankLeft: Field,
    val flankRight: Field,
    val bottomEdge: Field,
)

private fun backups(sport: Sport): List<Field> = listOf(
    Field.HEART_RATE,
    if (sport == Sport.RIDE) Field.SPEED else Field.PACE,
    Field.TOTAL_TIME,
    Field.CLOCK,
)

/** The live field for a step target's metric, as shown in the Hero. */
private fun heroField(metric: Metric, sport: Sport): Field = when (metric) {
    Metric.POWER -> Field.POWER
    Metric.HEART_RATE -> Field.HEART_RATE
    Metric.CADENCE -> Field.CADENCE
    Metric.PACE -> if (sport == Sport.RIDE) Field.SPEED else Field.PACE
}

/**
 * Settings.md §2: the Hero is the step's primary target metric (or time left when the step has
 * no target), the Partner is the other of the two. A user slot whose field is already on screen,
 * or has no data, shows the first of HR → Pace/Speed → Total time → Clock (then Step time) that is neither.
 */
fun resolveSlots(
    step: WorkoutStep,
    sport: Sport,
    availability: Availability,
    settings: ScreenSettings,
): ResolvedSlots {
    val profile = settings.forSport(sport)
    val target = step.primaryTarget
    val occupied = mutableSetOf<Field>()

    fun pick(wanted: Field): Field {
        val ok = wanted.offeredFor(sport) && availability.has(wanted) && wanted !in occupied
        // Indoors (no pace) with HR, Total time and Clock already on screen the four backups run out:
        // Step time always has a value, and only past that does a slot repeat what it asked for.
        val chosen = if (ok) wanted else (backups(sport) + Field.STEP_TIME).firstOrNull { it !in occupied && availability.has(it) }
            ?: wanted.takeIf { it.offeredFor(sport) } ?: Field.STEP_TIME  // never a field the sport must not show (km/h on a Run)
        occupied += chosen
        return chosen
    }

    val hero: Field
    val partner: Field
    if (target != null) {
        val live = heroField(target.metric, sport)
        if (profile.bigNumber == BigNumber.TIME_LEFT) {
            hero = Field.TIME_LEFT; partner = live
        } else {
            hero = live; partner = Field.TIME_LEFT
        }
        occupied += hero; occupied += partner
    } else {
        // No target: Hero is time left (+m:ss on lap steps) whatever the big-number setting,
        // and the Partner shows the left flank's field, so the left flank moves on to a backup.
        hero = Field.TIME_LEFT
        occupied += hero
        partner = pick(profile.flankLeft)
    }
    val flankLeft = pick(profile.flankLeft)
    val flankRight = pick(profile.flankRight)
    val bottom = pick(profile.bottomEdge)
    return ResolvedSlots(hero, partner, flankLeft, flankRight, bottom)
}

/** Data Layer item `/settings`, written by either side; last write wins (settings.md §4.1). */
object SettingsSync {
    const val PATH = "/settings"
    const val KEY_JSON = "json"

    /** Phone → watch message: play the sample buzz. Payload is the [BuzzStrength] name. */
    const val TEST_BUZZ_PATH = "/test-buzz"

    fun encodeTestBuzz(strength: BuzzStrength): ByteArray = strength.name.toByteArray()
    fun decodeTestBuzz(data: ByteArray?): BuzzStrength =
        data?.let { b -> BuzzStrength.entries.firstOrNull { it.name == String(b) } } ?: BuzzStrength.NORMAL

    /** The copy to keep: [remote] only when it is strictly newer, so a tie or an echo changes nothing. */
    fun merge(local: ScreenSettings, remote: ScreenSettings): ScreenSettings =
        if (remote.updatedAt > local.updatedAt) remote else local

    /** An unreadable item (older or newer app version) is ignored, not fatal. */
    fun decodeOrNull(json: String?): ScreenSettings? =
        json?.let { runCatching { ScreenSettings.fromJson(it) }.getOrNull() }
}
