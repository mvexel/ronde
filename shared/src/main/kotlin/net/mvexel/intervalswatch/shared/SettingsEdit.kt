package net.mvexel.intervalswatch.shared

/** The three user slots on the Step page (settings.md §2). */
enum class Slot { FLANK_LEFT, FLANK_RIGHT, BOTTOM_EDGE }

/** Fields a picker offers for [sport], in catalogue order (no rule-driven TIME_LEFT, no Cadence on Ride). */
fun pickerFields(sport: Sport): List<Field> = Field.entries.filter { it.selectable && it.offeredFor(sport) }

fun SportSettings.slot(slot: Slot): Field = when (slot) {
    Slot.FLANK_LEFT -> flankLeft
    Slot.FLANK_RIGHT -> flankRight
    Slot.BOTTOM_EDGE -> bottomEdge
}

fun SportSettings.withSlot(slot: Slot, field: Field): SportSettings = when (slot) {
    Slot.FLANK_LEFT -> copy(flankLeft = field)
    Slot.FLANK_RIGHT -> copy(flankRight = field)
    Slot.BOTTOM_EDGE -> copy(bottomEdge = field)
}

fun SportSettings.withTotalsField(index: Int, field: Field): SportSettings =
    copy(totalsFields = totalsFields.mapIndexed { i, f -> if (i == index) field else f })

fun ScreenSettings.withSport(sport: Sport, edit: (SportSettings) -> SportSettings): ScreenSettings =
    if (sport == Sport.RIDE) copy(ride = edit(ride)) else copy(run = edit(run))

/** One crown detent: [delta] steps through [pickerFields], stopping at either end (no wrap, so the tick means something). */
fun stepField(current: Field, sport: Sport, delta: Int): Field {
    val list = pickerFields(sport)
    val i = list.indexOf(current).let { if (it < 0) 0 else it }
    return list[(i + delta).coerceIn(0, list.lastIndex)]
}

/** Display name for the editor; [short] fits the 14 sp curved edge. */
val Field.label: String get() = when (this) {
    Field.HEART_RATE -> "Heart rate"
    Field.PACE -> "Pace (now)"
    Field.STEP_AVG_PACE -> "Step avg pace"
    Field.SPEED -> "Speed"
    Field.POWER -> "Power"
    Field.CADENCE -> "Cadence"
    Field.DISTANCE -> "Distance"
    Field.TOTAL_TIME -> "Total time"
    Field.CLOCK -> "Clock"
    Field.STEP_TIME -> "Step time"
    Field.WORKOUT_LEFT -> "Workout left"
    Field.AVG_HR -> "Avg HR"
    Field.AVG_PACE -> "Avg pace"
    Field.AVG_POWER -> "Avg power"
    Field.TIME_LEFT -> "Time left"
}

/** Placeholder value shown live in the editor slot (settings.md §5.1: "sample value"). */
fun Field.sample(sport: Sport): String = when (this) {
    Field.HEART_RATE, Field.AVG_HR -> "152"
    Field.PACE, Field.STEP_AVG_PACE, Field.AVG_PACE -> "4:28"
    Field.SPEED -> "31.4"
    Field.POWER, Field.AVG_POWER -> "245"
    Field.CADENCE -> "172"
    Field.DISTANCE -> if (sport == Sport.RIDE) "42.1" else "8.4"
    Field.TOTAL_TIME -> "31:14"
    Field.CLOCK -> "07:12"
    Field.STEP_TIME -> "1:20"
    Field.WORKOUT_LEFT -> "18:40"
    Field.TIME_LEFT -> "2:10"
}

/** The allowed pace windows (RF01 FR10 a). */
val PACE_WINDOWS: List<Int> = listOf(15, 20, 30)

const val DEFAULT_PACE_WINDOW = 30

/** Next window in [PACE_WINDOWS], wrapping; an unknown value snaps to the default 30. */
fun nextPaceWindow(current: Int): Int {
    val i = PACE_WINDOWS.indexOf(current)
    return if (i < 0) DEFAULT_PACE_WINDOW else PACE_WINDOWS[(i + 1) % PACE_WINDOWS.size]
}

fun SportSettings.withPaceWindow(sec: Int): SportSettings = copy(paceWindowSec = if (sec in PACE_WINDOWS) sec else DEFAULT_PACE_WINDOW)

fun CueRow.next(): CueRow = if (this == CueRow.NUMBER) CueRow.WORDS else CueRow.NUMBER

fun ColourBand.next(): ColourBand = if (this == ColourBand.OFF) ColourBand.ON else ColourBand.OFF

fun CueRow.label(): String = if (this == CueRow.NUMBER) "Number" else "Words"

fun ColourBand.label(): String = if (this == ColourBand.ON) "On" else "Off"
