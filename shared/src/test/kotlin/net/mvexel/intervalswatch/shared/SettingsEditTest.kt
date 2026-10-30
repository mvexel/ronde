package net.mvexel.intervalswatch.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsEditTest {
    @Test fun pickerHidesRuleFieldAndSportMismatches() {
        assertFalse(Field.TIME_LEFT in pickerFields(Sport.RUN))
        assertFalse(Field.CADENCE in pickerFields(Sport.RIDE))
        assertFalse(Field.PACE in pickerFields(Sport.RIDE))
        assertTrue(Field.SPEED in pickerFields(Sport.RIDE))
        assertFalse(Field.SPEED in pickerFields(Sport.RUN))
    }

    @Test fun crownStopsAtEnds() {
        val list = pickerFields(Sport.RUN)
        assertEquals(list.first(), stepField(list.first(), Sport.RUN, -1))
        assertEquals(list.last(), stepField(list.last(), Sport.RUN, 3))
        assertEquals(list[1], stepField(list[0], Sport.RUN, 1))
    }

    @Test fun crownRecoversFromFieldNotOfferedForSport() {
        // A synced Ride value (Speed) opened on Run starts the dial at the top.
        assertEquals(pickerFields(Sport.RUN)[1], stepField(Field.SPEED, Sport.RUN, 1))
    }

    @Test fun slotEditTouchesOnlyThatSlotAndSport() {
        val s = ScreenSettings().withSport(Sport.RIDE) { it.withSlot(Slot.BOTTOM_EDGE, Field.CLOCK) }
        assertEquals(Field.CLOCK, s.ride.bottomEdge)
        assertEquals(SportSettings.RUN_DEFAULT, s.run)
        assertEquals(SportSettings.RIDE_DEFAULT.flankLeft, s.ride.flankLeft)
    }

    @Test fun walkEditsRunProfile() {
        val s = ScreenSettings().withSport(Sport.WALK) { it.withSlot(Slot.FLANK_LEFT, Field.CLOCK) }
        assertEquals(Field.CLOCK, s.forSport(Sport.WALK).flankLeft)
    }
}
