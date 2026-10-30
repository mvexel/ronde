package net.mvexel.intervalswatch.shared

/** Behaviour settings (settings.md §4 rows 7-9), as pure rules the watch applies. */

/** The unit to show: the override, else what the workout came with from intervals.icu. */
fun ScreenSettings.effectiveUnit(fromIntervals: PaceUnit): PaceUnit = when (units) {
    UnitsSetting.FROM_INTERVALS -> fromIntervals
    UnitsSetting.KM -> PaceUnit.KM
    UnitsSetting.MI -> PaceUnit.MILE
}

fun Workout.withUnits(settings: ScreenSettings): Workout {
    val unit = settings.effectiveUnit(paceUnit)
    return if (unit == paceUnit) this else copy(paceUnit = unit)
}

/** Auto-pause is for runs, and only during warm-up and cool-down: standing in a recovery is the plan. */
fun ScreenSettings.autoPauseFor(sport: Sport, kind: StepKind?): Boolean =
    autoPauseWarmupCooldown && sport == Sport.RUN && (kind == StepKind.WARMUP || kind == StepKind.COOLDOWN)

/** The screen stays on while a work step is running, when asked. */
fun ScreenSettings.keepScreenOn(kind: StepKind?): Boolean = keepScreenOnDuringWork && kind == StepKind.ACTIVE
