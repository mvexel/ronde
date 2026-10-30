package net.mvexel.intervalswatch.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviourTest {
    @Test fun unitsFollowIntervalsUnlessOverridden() {
        val s = ScreenSettings()
        assertEquals(PaceUnit.MILE, s.effectiveUnit(PaceUnit.MILE))
        assertEquals(PaceUnit.KM, s.effectiveUnit(PaceUnit.KM))
        assertEquals(PaceUnit.KM, s.copy(units = UnitsSetting.KM).effectiveUnit(PaceUnit.MILE))
        assertEquals(PaceUnit.MILE, s.copy(units = UnitsSetting.MI).effectiveUnit(PaceUnit.KM))
    }

    @Test fun workoutUnitOverride() {
        val w = Workout.open(Sport.RUN, "2026-10-30T07:00:00")
        assertSame(w, w.withUnits(ScreenSettings()))
        assertEquals(PaceUnit.MILE, w.withUnits(ScreenSettings(units = UnitsSetting.MI)).paceUnit)
    }

    @Test fun autoPauseOnlyRunWarmupCooldown() {
        val on = ScreenSettings(autoPauseWarmupCooldown = true)
        assertTrue(on.autoPauseFor(Sport.RUN, StepKind.WARMUP))
        assertTrue(on.autoPauseFor(Sport.RUN, StepKind.COOLDOWN))
        assertFalse(on.autoPauseFor(Sport.RUN, StepKind.ACTIVE))
        assertFalse(on.autoPauseFor(Sport.RUN, StepKind.REST))
        assertFalse(on.autoPauseFor(Sport.RIDE, StepKind.WARMUP))
        assertFalse(ScreenSettings().autoPauseFor(Sport.RUN, StepKind.WARMUP))
    }

    @Test fun keepOnOnlyDuringWork() {
        val on = ScreenSettings(keepScreenOnDuringWork = true)
        assertTrue(on.keepScreenOn(StepKind.ACTIVE))
        assertFalse(on.keepScreenOn(StepKind.REST))
        assertFalse(ScreenSettings().keepScreenOn(StepKind.ACTIVE))
    }
}
