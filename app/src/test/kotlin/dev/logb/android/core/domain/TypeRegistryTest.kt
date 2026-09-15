package dev.logb.android.core.domain

import dev.logb.android.core.db.entity.ObjectTypeEntity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TypeRegistryTest {
    private val boat = ObjectTypeEntity("u1", 1, "Boat", "tool", """["repair","fuel","other"]""", "h", "t", "t", null)
    private val registry = TypeRegistry(listOf(boat))

    @Test fun `built-in keys keep their icon and categories`() {
        assertEquals("car", registry.icon("car"))
        assertEquals("object", registry.icon("other"))
        assertEquals(ObjectTypes.categoriesFor("body"), registry.categoriesFor("body"))
    }

    @Test fun `own types use their own icon, categories and unit`() {
        assertEquals("tool", registry.icon("custom:u1"))
        assertEquals(listOf("repair", "fuel", "other"), registry.categoriesFor("custom:u1"))
        assertEquals("h", registry.counterUnit("custom:u1"))
    }

    @Test fun `an unknown own type falls back to object and every category`() {
        assertEquals("object", registry.icon("custom:gone"))
        assertEquals(ObjectTypes.CATEGORIES, registry.categoriesFor("custom:gone"))
        assertNull(registry.find("custom:gone"))
    }

    @Test fun `the current category is always offered`() =
        assertEquals(listOf("repair", "fuel", "other", "medication"), registry.categoriesFor("custom:u1", "medication"))
}
