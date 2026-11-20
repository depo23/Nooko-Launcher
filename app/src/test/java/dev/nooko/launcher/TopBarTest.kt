package dev.nooko.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class TopBarTest {
    private val known = listOf("wifi", "sources", "apps", "system", "atv")

    @Test fun nothingSavedShowsTheDefault() {
        assertEquals(known, TopBar.parse(null, known, known, "atv"))
    }

    @Test fun aSavedListKeepsItsOrderAndDropsWhatIsUnknownOrRepeated() {
        assertEquals(listOf("apps", "wifi", "atv"), TopBar.parse("apps,wifi,gone,apps,atv", known, known, "atv"))
    }

    @Test fun atvSettingsIsNeverMissing() {
        assertEquals(listOf("wifi", "atv"), TopBar.parse("wifi", known, known, "atv"))
        assertEquals(listOf("atv"), TopBar.parse("", known, known, "atv")) // the bar is never empty
    }

    @Test fun anIconSlidesPastTheOnesOnScreen() {
        val order = listOf("wifi", "sources", "apps", "atv")
        val shown = listOf("wifi", "apps", "atv") // no input: Sources is not on screen
        assertEquals(listOf("wifi", "sources", "atv", "apps"), TopBar.slide(order, shown, "apps", 1))
        assertEquals(listOf("apps", "sources", "wifi", "atv"), TopBar.slide(order, shown, "apps", -1))
    }

    @Test fun anIconAtAnEndStaysThere() {
        val order = listOf("wifi", "apps", "atv")
        assertEquals(order, TopBar.slide(order, order, "wifi", -1))
        assertEquals(order, TopBar.slide(order, order, "atv", 1))
        assertEquals(order, TopBar.slide(order, order, "system", 1)) // not on the bar
    }

    @Test fun anAddedIconLandsRightAfterTheOneYouHeld() {
        val order = listOf("wifi", "apps", "atv")
        assertEquals(listOf("wifi", "system", "apps", "atv"), TopBar.addAfter(order, "system", "wifi"))
        assertEquals(listOf("wifi", "apps", "atv", "system"), TopBar.addAfter(order, "system", "atv"))
        assertEquals(listOf("wifi", "apps", "atv", "system"), TopBar.addAfter(order, "system", "missing"))
    }

    @Test fun theBarShowsAtMostFiveAndAlwaysAtvSettings() {
        val six = listOf("wifi", "sources", "apps", "system", "timer", "atv")
        assertEquals(listOf("wifi", "sources", "apps", "system", "atv"), TopBar.cap(six, "atv"))
        assertEquals(listOf("atv", "wifi", "sources", "apps", "system"), TopBar.cap(listOf("atv") + (six - "atv"), "atv"))
        assertEquals(listOf("atv"), TopBar.cap(listOf("atv"), "atv"))
    }

    @Test fun anIconAddedToAFullBarTakesThePlaceOfOne() {
        assertEquals(listOf("wifi", "power", "atv"), TopBar.swap(listOf("wifi", "apps", "atv"), "apps", "power"))
    }

    @Test fun aDefaultCanLeaveAnIconOut() { // Power is not on the bar until someone adds it
        val all = known + "power"
        assertEquals(known, TopBar.parse(null, all, all - "power", "atv"))
        assertEquals(known + "power", TopBar.parse("wifi,sources,apps,system,atv,power", all, all - "power", "atv"))
    }
}
