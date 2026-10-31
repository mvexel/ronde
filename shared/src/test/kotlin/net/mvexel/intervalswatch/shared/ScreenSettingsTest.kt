package net.mvexel.intervalswatch.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSettingsTest {
    private val defaults = ScreenSettings()

    private fun paceStep() = WorkoutStep(
        "Tempo", StepKind.ACTIVE, durationSec = 300,
        targets = listOf(Target(Metric.PACE, 3.5, 3.9)), primary = Metric.PACE,
    )
    private fun hrStep() = WorkoutStep(
        "Z2", StepKind.ACTIVE, durationSec = 300,
        targets = listOf(Target(Metric.HEART_RATE, 140.0, 150.0)), primary = Metric.HEART_RATE,
    )
    private fun powerStep() = WorkoutStep(
        "VO2", StepKind.ACTIVE, durationSec = 300,
        targets = listOf(Target(Metric.POWER, 300.0, 316.0)), primary = Metric.POWER,
    )
    private fun openStep() = WorkoutStep("Warm-up", StepKind.WARMUP, durationSec = 600)
    private fun lapStep() = WorkoutStep("Easy", StepKind.WARMUP)

    @Test fun runPaceStepUsesDefaults() {
        val r = resolveSlots(paceStep(), Sport.RUN, Availability(), defaults)
        assertEquals(ResolvedSlots(Field.PACE, Field.TIME_LEFT, Field.HEART_RATE, Field.STEP_AVG_PACE, Field.TOTAL_TIME), r)
    }

    @Test fun runHrStepMovesLeftFlankToPace() {
        val r = resolveSlots(hrStep(), Sport.RUN, Availability(), defaults)
        assertEquals(Field.HEART_RATE, r.hero)
        assertEquals(Field.PACE, r.flankLeft)
        assertEquals(Field.STEP_AVG_PACE, r.flankRight)
        assertEquals(Field.TOTAL_TIME, r.bottomEdge)
    }

    @Test fun outdoorRidePowerStep() {
        val r = resolveSlots(powerStep(), Sport.RIDE, Availability(), defaults)
        assertEquals(ResolvedSlots(Field.POWER, Field.TIME_LEFT, Field.HEART_RATE, Field.SPEED, Field.TOTAL_TIME), r)
    }

    @Test fun indoorRideFallsBackToTotalTimeThenClock() {
        val r = resolveSlots(powerStep(), Sport.RIDE, Availability(gps = false), defaults)
        assertEquals(Field.HEART_RATE, r.flankLeft)
        assertEquals(Field.TOTAL_TIME, r.flankRight)
        assertEquals(Field.CLOCK, r.bottomEdge)
    }

    @Test fun noTargetStepShowsTimeLeftHeroAndLeftFlankAsPartner() {
        val r = resolveSlots(openStep(), Sport.RUN, Availability(), defaults)
        assertEquals(Field.TIME_LEFT, r.hero)
        assertEquals(Field.HEART_RATE, r.partner)
        assertEquals(Field.PACE, r.flankLeft) // HR is taken by the partner
        assertEquals(Field.STEP_AVG_PACE, r.flankRight)
        assertEquals(Field.TOTAL_TIME, r.bottomEdge)
    }

    @Test fun lapStepWithoutTargetBehavesLikeNoTarget() {
        val r = resolveSlots(lapStep(), Sport.RUN, Availability(), defaults)
        assertEquals(Field.TIME_LEFT, r.hero)
        assertEquals(Field.HEART_RATE, r.partner)
    }

    @Test fun bigNumberTimeLeftSwapsHeroAndPartner() {
        val s = defaults.copy(run = SportSettings.RUN_DEFAULT.copy(bigNumber = BigNumber.TIME_LEFT))
        val r = resolveSlots(paceStep(), Sport.RUN, Availability(), s)
        assertEquals(Field.TIME_LEFT, r.hero)
        assertEquals(Field.PACE, r.partner)
        assertEquals(Field.HEART_RATE, r.flankLeft)
    }

    @Test fun noHeartRateSourceSkipsHrBackup() {
        val r = resolveSlots(hrStep(), Sport.RUN, Availability(heartRate = false), defaults)
        assertEquals(Field.PACE, r.flankLeft)
    }

    @Test fun cadenceOnRideIsReplacedByBackup() {
        val s = defaults.copy(ride = SportSettings.RIDE_DEFAULT.copy(flankRight = Field.CADENCE))
        val r = resolveSlots(powerStep(), Sport.RIDE, Availability(), s)
        assertEquals(Field.SPEED, r.flankRight) // HR taken by left flank, speed is next
    }

    @Test fun noDuplicatesWhenAllSlotsAskForSameField() {
        val s = defaults.copy(run = SportSettings.RUN_DEFAULT.copy(
            flankLeft = Field.POWER, flankRight = Field.POWER, bottomEdge = Field.POWER))
        val r = resolveSlots(paceStep(), Sport.RUN, Availability(power = false), s)
        val all = listOf(r.hero, r.partner, r.flankLeft, r.flankRight, r.bottomEdge)
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun indoorHrAndNoTargetStepsWithDefaultsDoNotRunOutOfBackups() {
        for (sport in listOf(Sport.RUN, Sport.RIDE)) for (step in listOf(hrStep(), openStep(), lapStep())) {
            val r = resolveSlots(step, sport, Availability(gps = false), defaults)
            val all = listOf(r.hero, r.partner, r.flankLeft, r.flankRight, r.bottomEdge)
            assertEquals("$sport ${step.label}", all.size, all.toSet().size)
            assertEquals(Field.STEP_TIME, r.bottomEdge)
        }
    }

    @Test fun anySettingsOnAnyStepResolveWithoutThrowing() {
        val fields = Field.entries.filter { it.selectable }
        val steps = listOf(paceStep(), hrStep(), powerStep(), openStep(), lapStep())
        val availabilities = (0 until 16).map { b -> Availability(b and 1 != 0, b and 2 != 0, b and 4 != 0, b and 8 != 0) }
        for (sport in listOf(Sport.RUN, Sport.RIDE)) for (big in BigNumber.entries) for (l in fields) for (r in fields) for (b in fields) {
            val p = SportSettings.RUN_DEFAULT.copy(bigNumber = big, flankLeft = l, flankRight = r, bottomEdge = b)
            val s = defaults.copy(run = p, ride = p)
            for (step in steps) for (a in availabilities) resolveSlots(step, sport, a, s)
        }
    }

    @Test fun runAndWalkNeverShowSpeed() {
        val fields = Field.entries.filter { it.selectable }
        val steps = listOf(paceStep(), hrStep(), powerStep(), openStep(), lapStep())
        val availabilities = (0 until 16).map { b -> Availability(b and 1 != 0, b and 2 != 0, b and 4 != 0, b and 8 != 0) }
        for (sport in listOf(Sport.RUN, Sport.WALK)) for (big in BigNumber.entries) for (l in fields) for (r in fields) for (b in fields) {
            val p = SportSettings.RUN_DEFAULT.copy(bigNumber = big, flankLeft = l, flankRight = r, bottomEdge = b)
            val s = defaults.copy(run = p, ride = p)
            for (step in steps) for (a in availabilities) {
                val slots = resolveSlots(step, sport, a, s)
                val all = listOf(slots.hero, slots.partner, slots.flankLeft, slots.flankRight, slots.bottomEdge)
                assertFalse("SPEED on $sport: $slots from $p", Field.SPEED in all)
            }
        }
    }

    @Test fun walkAndOtherUseRunProfile() {
        assertEquals(defaults.run, defaults.forSport(Sport.WALK))
        assertEquals(defaults.run, defaults.forSport(Sport.OTHER))
        assertEquals(defaults.ride, defaults.forSport(Sport.RIDE))
    }

    @Test fun defaultsMatchSpec() {
        assertEquals(listOf(Field.TOTAL_TIME, Field.DISTANCE, Field.AVG_PACE, Field.AVG_HR), defaults.run.totalsFields)
        assertEquals(listOf(Field.TOTAL_TIME, Field.DISTANCE, Field.AVG_POWER, Field.AVG_HR), defaults.ride.totalsFields)
        assertTrue(defaults.run.totalsPage && defaults.run.workoutRingPage && defaults.ride.offTargetBuzz)
        assertFalse(Field.TIME_LEFT.selectable)
    }

    @Test fun jsonRoundTripAndEmptyObjectGivesDefaults() {
        val s = defaults.copy(buzzStrength = BuzzStrength.STRONG, updatedAt = 42)
        assertEquals(s, ScreenSettings.fromJson(s.toJson()))
        assertEquals(defaults, ScreenSettings.fromJson("{}"))
    }
}
