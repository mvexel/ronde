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

class SettingsEditPhoneTest {
    @Test fun totalsFieldReplacesOnlyThatIndex() {
        val s = SportSettings.RUN_DEFAULT.withTotalsField(2, Field.AVG_HR)
        assertEquals(listOf(Field.TOTAL_TIME, Field.DISTANCE, Field.AVG_HR, Field.AVG_HR), s.totalsFields)
    }

    @Test fun testBuzzPayloadRoundTrips() {
        assertEquals(BuzzStrength.STRONG, SettingsSync.decodeTestBuzz(SettingsSync.encodeTestBuzz(BuzzStrength.STRONG)))
        assertEquals(BuzzStrength.NORMAL, SettingsSync.decodeTestBuzz(null))
        assertEquals(BuzzStrength.NORMAL, SettingsSync.decodeTestBuzz("junk".toByteArray()))
    }
}

class PaceWindowTest {
    @Test fun cyclesThroughAllowedValues() {
        assertEquals(20, nextPaceWindow(15))
        assertEquals(30, nextPaceWindow(20))
        assertEquals(15, nextPaceWindow(30))
        assertEquals(20, nextPaceWindow(7))
    }

    @Test fun rejectsOtherValuesAndSyncs() {
        assertEquals(20, SportSettings.RUN_DEFAULT.withPaceWindow(45).paceWindowSec)
        val s = ScreenSettings().withSport(Sport.RIDE) { it.withPaceWindow(30) }
        assertEquals(30, ScreenSettings.fromJson(s.toJson()).ride.paceWindowSec)
        assertEquals(20, s.run.paceWindowSec)
    }
}
