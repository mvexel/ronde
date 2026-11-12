package net.mvexel.intervalswatch.mobile.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabSelectionTest {
    @Test fun everyRouteHighlightsTheTabItWasReachedFrom() {
        assertEquals(Tab.PLAN, Routes.tabOf(Routes.PLAN))
        assertEquals(Tab.PLAN, Routes.tabOf(Routes.PLANNED_WORKOUT))
        assertEquals(Tab.HISTORY, Routes.tabOf(Routes.HISTORY))
        assertEquals(Tab.HISTORY, Routes.tabOf(Routes.ACTIVITY))
        assertEquals(Tab.WATCH, Routes.tabOf(Routes.WATCH))
        assertEquals(Tab.WATCH, Routes.tabOf(Routes.WATCH_SCREENS))
        assertEquals(Tab.SETTINGS, Routes.tabOf(Routes.SETTINGS))
        // Settings -> Watch screens stays on Settings (it used to light up Watch and trap the user).
        assertEquals(Tab.SETTINGS, Routes.tabOf(Routes.SETTINGS_WATCH_SCREENS))
        assertNull(Routes.tabOf(null))
        assertNull(Routes.tabOf("elsewhere"))
    }
}
