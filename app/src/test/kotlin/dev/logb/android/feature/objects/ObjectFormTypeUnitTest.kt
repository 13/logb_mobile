package dev.logb.android.feature.objects

import dev.logb.android.core.db.entity.ObjectTypeEntity
import dev.logb.android.core.domain.TypeRegistry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `adoptedCounterUnit`: what selecting a type should leave the form's counter unit as. */
class ObjectFormTypeUnitTest {
    private val boat = ObjectTypeEntity("u1", 1, "Boat", "tool", """["repair","fuel","other"]""", "h", "t", "t", null)
    private val registry = TypeRegistry(listOf(boat))
    private val key = "custom:u1"

    @Test fun `a new form with no unit chosen yet adopts the own type's unit`() {
        val state = ObjectFormState(editing = false, counterUnit = "km", counterUnitTouched = false, registry = registry)
        assertEquals("h", adoptedCounterUnit(state, key))
    }

    @Test fun `a unit the person picked this session survives selecting an own type`() {
        val state = ObjectFormState(editing = false, counterUnit = "km", counterUnitTouched = true, registry = registry)
        assertEquals("km", adoptedCounterUnit(state, key))
    }

    @Test fun `editing an object that already carries km keeps km when an own type is picked`() {
        val state = ObjectFormState(editing = true, counterUnit = "km", counterUnitTouched = false, registry = registry)
        assertEquals("km", adoptedCounterUnit(state, key))
    }

    @Test fun `editing an object with no stored unit adopts the own type's unit`() {
        val state = ObjectFormState(editing = true, counterUnit = null, counterUnitTouched = false, registry = registry)
        assertEquals("h", adoptedCounterUnit(state, key))
    }

    @Test fun `a built-in type never changes the counter unit`() {
        val new = ObjectFormState(editing = false, counterUnit = "km", counterUnitTouched = false, registry = registry)
        assertEquals("km", adoptedCounterUnit(new, "car"))
        val editing = ObjectFormState(editing = true, counterUnit = null, counterUnitTouched = false, registry = registry)
        assertNull(adoptedCounterUnit(editing, "car"))
    }
}
