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
        val s = ScreenSettings().withSport(Sport.RIDE) { it.withPaceWindow(15) }
        assertEquals(15, ScreenSettings.fromJson(s.toJson()).ride.paceWindowSec)
        assertEquals(20, s.run.paceWindowSec)
    }
}

class PacedRunSettingsTest {
    @Test fun releaseDefaultsAreNumberCueAndNoWash() {
        val d = ScreenSettings()
        assertEquals(CueRow.NUMBER, d.cueRow)
        assertEquals(if (PacedRunDefaults.FIRST_RUN_DEFAULTS) ColourBand.ON else ColourBand.OFF, d.colourBand)
        assertEquals(false, PacedRunDefaults.FIRST_RUN_DEFAULTS)
        assertEquals("the old setting keeps its stored default", 20, d.run.paceWindowSec)
        assertEquals(30, d.run.pacedWindowSec)
    }

    @Test fun cycleAndSync() {
        assertEquals(CueRow.WORDS, CueRow.NUMBER.next()); assertEquals(CueRow.NUMBER, CueRow.WORDS.next())
        assertEquals(ColourBand.ON, ColourBand.OFF.next()); assertEquals(ColourBand.OFF, ColourBand.ON.next())
        val s = ScreenSettings(cueRow = CueRow.WORDS, colourBand = ColourBand.ON)
        val back = ScreenSettings.fromJson(s.toJson())
        assertEquals(CueRow.WORDS, back.cueRow); assertEquals(ColourBand.ON, back.colourBand)
    }

    @Test fun pacedWindowIsItsOwnSettingAndLeavesTheOldOneAlone() {
        assertEquals(15, nextPacedWindow(30)); assertEquals(20, nextPacedWindow(15)); assertEquals(30, nextPacedWindow(20)); assertEquals(30, nextPacedWindow(7))
        val s = ScreenSettings().withSport(Sport.RUN) { it.withPacedWindow(15) }
        val back = ScreenSettings.fromJson(s.toJson())
        assertEquals(15, back.run.pacedWindowSec); assertEquals(20, back.run.paceWindowSec)
        assertEquals(30, SportSettings.RUN_DEFAULT.withPacedWindow(45).pacedWindowSec)
        // a user who explicitly chose 20 before the field existed (default 20 is not written) still has 20
        assertEquals(20, ScreenSettings.fromJson("""{"run":{"flankRight":"STEP_AVG_PACE","totalsFields":["TOTAL_TIME"]}}""").run.paceWindowSec)
    }

    @Test fun oldJsonWithoutTheNewFieldsDecodesToDefaults() {
        val old = ScreenSettings.fromJson("""{"buzzStrength":"STRONG","updatedAt":5}""")
        assertEquals(CueRow.NUMBER, old.cueRow)
        assertEquals(PacedRunDefaults.colourBand, old.colourBand)
    }
}
