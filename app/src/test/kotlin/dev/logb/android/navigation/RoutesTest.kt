package dev.logb.android.navigation

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoutesTest {
    @Test
    fun `a drill-down keeps its parent destination lit`() {
        assertEquals(Destination.Objects, activeDestination("dev.logb.android.navigation.Objects"))
        assertEquals(Destination.Objects, activeDestination("dev.logb.android.navigation.ObjectDetail/{uuid}?tab={tab}"))
        assertEquals(Destination.Objects, activeDestination("dev.logb.android.navigation.Stats"))
        assertEquals(Destination.Search, activeDestination("dev.logb.android.navigation.Search"))
        assertEquals(Destination.Settings, activeDestination("dev.logb.android.navigation.Settings"))
        assertEquals(Destination.Settings, activeDestination("dev.logb.android.navigation.Appearance"))
        assertNull(activeDestination(null))
        assertNull(activeDestination("something.Else"))
    }
}
